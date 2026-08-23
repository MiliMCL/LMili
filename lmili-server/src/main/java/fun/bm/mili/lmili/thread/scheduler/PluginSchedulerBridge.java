package fun.bm.mili.lmili.thread.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import fun.bm.mili.lmili.api.identity.PluginStatus;
import fun.bm.mili.lmili.thread.scheduler.api.EntityScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.EntityTask;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import fun.bm.mili.lmili.thread.scheduler.api.TaskHandle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bridge between the LMili Plugin Identity system and the Mili scheduler.
 *
 * <p>Every task submitted through the public {@code Mili.scheduler()} API
 * must carry a {@link PluginId} owner. This bridge:</p>
 * <ul>
 *   <li>Checks the submitting plugin's lifecycle status before admission</li>
 *   <li>Tracks submitted {@link TaskHandle}s per owner so they can be
 *       cancelled when the plugin is disabled or unloaded</li>
 *   <li>Wraps every task body with {@link LMili#bindCurrentOwner} /
 *       {@link LMili#clearCurrentOwner()} so nested scheduler calls
 *       inherit the same owner</li>
 *   <li>Records quota and observability counters on the owner's
 *       {@link PluginRuntimeContext}</li>
 * </ul>
 *
 * <p>Initialised once during server bootstrap by
 * {@link fun.bm.mili.api.internal.PublicSchedulerAdapter}.</p>
 */
public final class PluginSchedulerBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile MiliScheduler SCHEDULER;

    /** Per-owner set of live (pending) task handles. */
    private static final ConcurrentMap<PluginId, Set<TaskHandle>> TASKS_BY_OWNER =
            new ConcurrentHashMap<>();

    private PluginSchedulerBridge() {}

    // ---- lifecycle -------------------------------------------------------

    /**
     * Initialise the bridge with the internal scheduler. Idempotent —
     * subsequent calls are silently ignored.
     */
    public static void init(@NotNull final MiliScheduler scheduler) {
        if (SCHEDULER != null) return;
        SCHEDULER = scheduler;
        LOGGER.info("[PluginSchedulerBridge] Initialised");
    }

    /** @return true after {@link #init} has been called. */
    public static boolean isReady() {
        return SCHEDULER != null;
    }

    // ---- task submission -------------------------------------------------

    /**
     * Submit a {@link RegionTask} tracked under the given owner.
     *
     * @param owner the submitting plugin's id
     * @param task  the raw region task
     * @return the task handle returned by the internal scheduler
     * @throws SecurityException if the owner is not {@code lmili.system} and
     *         its current status does not allow scheduling
     * @throws IllegalStateException if the bridge has not been initialised
     */
    @NotNull
    public static TaskHandle submit(@NotNull final PluginId owner,
                                    @NotNull final RegionTask task) {
        final MiliScheduler sched = SCHEDULER;
        if (sched == null) {
            throw new IllegalStateException(
                    "PluginSchedulerBridge not initialised");
        }

        // 1. Lifecycle admission check
        if (!owner.equals(LMili.SYSTEM_OWNER_ID)) {
            final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            final PluginStatus status = mgr.getStatus(owner).orElse(null);
            if (status == null || !status.canSchedule()) {
                final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(owner);
                if (ctx != null) {
                    ctx.observability().recordPermissionDenied();
                }
                throw new SecurityException(
                        "Plugin " + owner.value() + " (status=" + status
                                + ") cannot submit scheduler tasks");
            }

            // 2. Quota admission (best-effort: uses the context's built-time domain)
            final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(owner);
            if (ctx != null) {
                ctx.requireScheduleSlot();
                ctx.observability().recordSchedulerRequest();
                ctx.resourceQuota().onTaskSubmit();
            }
        }

        // 3. Wrap with owner-stamped execution context
        final RegionTask stamped = ownerStamped(owner, task);

        // 4. Submit to internal scheduler
        final TaskHandle handle = sched.submit(stamped);

        // 5. Track the handle; remove from tracking on completion
        trackHandle(owner, handle);

        return handle;
    }

    /**
     * Submit an entity task tracked under the given owner.
     *
     * @param owner the submitting plugin's id
     * @param ref   entity reference
     * @param task  the entity task
     * @return the task handle
     * @throws SecurityException if the owner is not allowed to schedule
     * @throws IllegalStateException if the bridge has not been initialised
     */
    @NotNull
    public static TaskHandle submitEntity(@NotNull final PluginId owner,
                                          @NotNull final EntityScheduler.EntityRef ref,
                                          @NotNull final EntityTask task) {
        final MiliScheduler sched = SCHEDULER;
        if (sched == null) {
            throw new IllegalStateException(
                    "PluginSchedulerBridge not initialised");
        }

        // Lifecycle admission (same as submit)
        if (!owner.equals(LMili.SYSTEM_OWNER_ID)) {
            final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            final PluginStatus status = mgr.getStatus(owner).orElse(null);
            if (status == null || !status.canSchedule()) {
                final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(owner);
                if (ctx != null) ctx.observability().recordPermissionDenied();
                throw new SecurityException(
                        "Plugin " + owner.value() + " (status=" + status
                                + ") cannot submit entity tasks");
            }
            final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(owner);
            if (ctx != null) {
                ctx.observability().recordSchedulerRequest();
                ctx.resourceQuota().onTaskSubmit();
            }
        }

        final EntityTask stamped = new EntityTask() {
            @Override
            public void execute(@NotNull final EntityTask.EntityTaskContext context) throws Exception {
                LMili.bindCurrentOwner(owner);
                try {
                    task.execute(context);
                } finally {
                    LMili.clearCurrentOwner();
                }
            }

            @Override
            public @NotNull String name() {
                return "[" + owner.value() + "] " + task.name();
            }

            @Override
            public void onCancel() {
                task.onCancel();
            }
        };

        final TaskHandle handle = sched.forEntity(ref).submit(stamped);
        trackHandle(owner, handle);
        return handle;
    }

    // ---- operator actions ------------------------------------------------

    /**
     * Disable a plugin: cancel all pending (queued or running) tasks.
     *
     * @return the number of tasks that were successfully cancelled
     */
    public static int disablePlugin(@NotNull final PluginId owner) {
        final Set<TaskHandle> set = TASKS_BY_OWNER.get(owner);
        if (set == null) return 0;

        int cancelled = 0;
        for (final TaskHandle h : set) {
            if (h.cancel()) {
                cancelled++;
                // Record quota cancellation
                final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(owner);
                if (ctx != null) {
                    ctx.resourceQuota().onTaskCancelled();
                }
            }
        }
        set.clear();

        LOGGER.info("[PluginSchedulerBridge] Disabled {} — cancelled {} pending task(s)",
                owner.value(), cancelled);
        return cancelled;
    }

    /**
     * Unload a plugin: cancel pending tasks and remove all tracking.
     * Called during {@link PluginIdentityBootstrap} plugin disable.
     */
    public static void unloadPlugin(@NotNull final PluginId owner) {
        disablePlugin(owner);
        TASKS_BY_OWNER.remove(owner);

        // Clean up the runtime context index
        PluginRuntimeContext.unregisterForPluginId(owner);

        LOGGER.info("[PluginSchedulerBridge] Unloaded {}", owner.value());
    }

    // ---- diagnostics ----------------------------------------------------

    /** @return the number of pending (tracked, not yet terminal) tasks for an owner. */
    public static int pendingTaskCount(@NotNull final PluginId owner) {
        final Set<TaskHandle> set = TASKS_BY_OWNER.get(owner);
        return set != null ? set.size() : 0;
    }

    /** @return a snapshot of all owners with at least one tracked task. */
    @NotNull
    public static Collection<PluginId> trackedOwners() {
        return Collections.unmodifiableSet(TASKS_BY_OWNER.keySet());
    }

    /** @return the internal scheduler reference (for direct access). */
    @Nullable
    public static MiliScheduler internalScheduler() {
        return SCHEDULER;
    }

    // ---- internals ------------------------------------------------------

    private static void trackHandle(@NotNull final PluginId owner,
                                    @NotNull final TaskHandle handle) {
        // Skip tracking for system tasks
        if (owner.equals(LMili.SYSTEM_OWNER_ID)) return;

        final Set<TaskHandle> set = TASKS_BY_OWNER.computeIfAbsent(
                owner, k -> ConcurrentHashMap.newKeySet());
        set.add(handle);

        // Auto-remove on completion so the set stays lean
        handle.onComplete(h -> set.remove(h));
    }

    /**
     * Wrap a RegionTask so that the owner's PluginId is bound on the
     * executing thread (propagating to nested scheduler calls) and is
     * visible in the task name.
     */
    private static RegionTask ownerStamped(@NotNull final PluginId owner,
                                           @NotNull final RegionTask task) {
        return new RegionTask() {
            @Override
            public void execute() throws Exception {
                LMili.bindCurrentOwner(owner);
                try {
                    task.execute();
                } finally {
                    LMili.clearCurrentOwner();
                }
            }

            @Override
            public long regionId() { return task.regionId(); }

            @Override
            public boolean isBlocking() { return task.isBlocking(); }

            @Override
            public long timeoutMillis() { return task.timeoutMillis(); }

            @Override
            public @NotNull String name() {
                return "[" + owner.value() + "] " + task.name();
            }

            @Override
            public void onCancel() { task.onCancel(); }
        };
    }
}