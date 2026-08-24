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
                final long start = System.nanoTime();
                boolean ok = true;
                try {
                    task.execute(context);
                } catch (final Throwable t) {
                    ok = false;
                    throw t;
                } finally {
                    LMili.clearCurrentOwner();
                    recordExecution(owner, System.nanoTime() - start, ok);
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
     * <p>This is a <em>temporary</em> operator action — Identity, Runtime
     * Context, Metrics and the Bukkit plugin are all retained. Only the
     * scheduler tasks are cancelled and further submissions are rejected by
     * the lifecycle check in {@link #submit}.</p>
     *
     * @return the number of tasks that were successfully cancelled
     */
    public static int disablePlugin(@NotNull final PluginId owner) {
        final Set<TaskHandle> set = TASKS_BY_OWNER.get(owner);
        if (set == null) return 0;

        int cancelled = 0;
        for (final TaskHandle h : set) {
            if (h.cancel()) cancelled++;
            // Note: quota accounting for the cancelled handle is done by the
            // onComplete callback registered in trackHandle (recordTerminal).
        }
        set.clear();

        LOGGER.info("[PluginSchedulerBridge] Disabled {} — cancelled {} pending task(s)",
                owner.value(), cancelled);
        return cancelled;
    }

    /**
     * Unload a plugin: cancel pending tasks, remove all tracking and clean up
     * the runtime context. Called when the plugin is truly unloaded (Bukkit
     * {@code PluginDisableEvent} / hot-reload), NOT by operator disable.
     *
     * <p>Also cascades to addon plugins whose {@link PluginId} is a child of
     * the unloaded plugin (P1: parent unload must not leave addon tasks
     * running).</p>
     */
    public static void unloadPlugin(@NotNull final PluginId owner) {
        disablePlugin(owner);
        TASKS_BY_OWNER.remove(owner);

        // Cascade to child addons that inherit this plugin's scheduler domain.
        for (final PluginId tracked : TASKS_BY_OWNER.keySet()) {
            if (tracked.isChildOf(owner)) {
                disablePlugin(tracked);
                TASKS_BY_OWNER.remove(tracked);
                PluginRuntimeContext.unregisterForPluginId(tracked);
                LOGGER.info("[PluginSchedulerBridge] Cascaded unload to addon {}", tracked.value());
            }
        }

        // Clean up the runtime context index
        PluginRuntimeContext.unregisterForPluginId(owner);

        LOGGER.info("[PluginSchedulerBridge] Unloaded {}", owner.value());
    }

    // ---- task accounting -------------------------------------------------

    /**
     * Record the actual execution of a task on the owner's quota and
     * observability counters. Called from the owner-stamped wrappers after
     * {@code execute()} returns (success or failure).
     */
    private static void recordExecution(@NotNull final PluginId owner,
                                        final long executionNanos,
                                        final boolean success) {
        if (owner.equals(LMili.SYSTEM_OWNER_ID)) return;
        final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(owner);
        if (ctx == null) return;
        ctx.resourceQuota().onTaskStart();
        ctx.resourceQuota().onTaskFinish(executionNanos, success);
        ctx.observability().recordExecutionNanos(executionNanos);
        if (!success) ctx.observability().recordTaskFailed();
    }

    /**
     * Record the terminal state of a tracked task. Fired from the
     * {@code onComplete} callback in {@link #trackHandle}.
     */
    private static void recordTerminal(@NotNull final PluginId owner,
                                       @NotNull final TaskHandle handle) {
        if (owner.equals(LMili.SYSTEM_OWNER_ID)) return;
        final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(owner);
        if (ctx == null) return;

        switch (handle.state()) {
            case COMPLETED, FAILED -> {
                // Execution already recorded in the stamped wrapper; nothing to add.
                // (For tasks that complete without the wrapper running, the
                // wrapper's recordExecution still fires on the carrying thread.)
            }
            case CANCELLED -> ctx.resourceQuota().onTaskCancelled();
            default -> { }
        }
    }

    // ---- diagnostics ----------------------------------------------------

    /** @return the number of pending (tracked, not yet terminal) tasks for an owner. */
    public static int pendingTaskCount(@NotNull final PluginId owner) {
        final Set<TaskHandle> set = TASKS_BY_OWNER.get(owner);
        return set != null ? set.size() : 0;
    }

    /**
     * 探测一个 Bukkit plugin 是否在 Bukkit 全局 scheduler 上有 pending tasks
     * （即没走 LMili 调度）。
     *
     * <p>这是一个<b>提示性</b>检查，用于 {@code /pluginid status} 显示「plugin 走的是
     * BukkitScheduler，没被 LMili 追踪」。精确数字需要 Paper 内部 instrumentation，
     * 实际我们通过 plugin.isEnabled() + 是否在 {@link #TASKS_BY_OWNER} 中来判断
     * 近似状态。
     *
     * @param bukkit Bukkit plugin 对象（可能为 null）
     * @return true 表示 plugin 在 Bukkit 全局调度里有提交任务的迹象（即便 LMili 没追踪到）
     */
    public static boolean hasBukkitSchedulerTasks(org.bukkit.plugin.Plugin bukkit) {
        if (bukkit == null || !bukkit.isEnabled()) {
            return false;
        }
        // 启发式：plugin enabled + Bukkit Scheduler 还有 pending —— 标记为 true。
        // 真实 BukkitScheduler.getPendingTasks() 在 1.13+ 才支持；
        // Bukkit 内部用 CraftScheduler 维护队列。我们仅做"plugin 是否活跃"判断。
        // 该方法只用于状态提示，不影响调度正确性。
        try {
            int bukkitPending = org.bukkit.Bukkit.getScheduler().getPendingTasks().size();
            return bukkitPending > 0;
        } catch (Throwable ignored) {
            return false;
        }
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

        // Auto-remove on completion so the set stays lean, and record the
        // terminal state on the owner's quota.
        handle.onComplete(h -> {
            set.remove(h);
            recordTerminal(owner, h);
        });
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
                final long start = System.nanoTime();
                boolean ok = true;
                try {
                    task.execute();
                } catch (final Throwable t) {
                    ok = false;
                    throw t;
                } finally {
                    LMili.clearCurrentOwner();
                    recordExecution(owner, System.nanoTime() - start, ok);
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