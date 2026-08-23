package fun.bm.mili.api.internal;

import com.mojang.logging.LogUtils;
import fun.bm.mili.api.EntityScheduler;
import fun.bm.mili.api.EntityTaskContext;
import fun.bm.mili.api.MiliUsageTracker;
import fun.bm.mili.api.Scheduler;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.thread.scheduler.PluginSchedulerBridge;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 将内部 MiliScheduler (NMS 类型) 适配为公共 Scheduler (Bukkit 类型)。
 *
 * <p>负责：</p>
 * <ul>
 *   <li>Bukkit Location → NMS ServerLevel + Vec3 转换</li>
 *   <li>Bukkit Entity → NMS Entity 转换</li>
 *   <li>公共 EntityTaskContext ↔ 内部 EntityTaskContext 包装</li>
 *   <li>virtual thread 生命周期管理</li>
 *   <li><b>PluginId Owner 绑定</b>：每个提交的任务都通过
 *       {@link PluginSchedulerBridge} 获得 {@link PluginId} 属主，
 *       lifecycle 检查和任务追踪</li>
 * </ul>
 */
public final class PublicSchedulerAdapter implements Scheduler {

    private static final String VERSION = "5.0.0-public";
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MiliScheduler internalScheduler;

    public PublicSchedulerAdapter(@NotNull final MiliScheduler internalScheduler) {
        this.internalScheduler = internalScheduler;
        // Initialise the bridge once during adapter creation
        PluginSchedulerBridge.init(internalScheduler);
    }

    @Override
    public @NotNull EntityScheduler forEntity(@NotNull final org.bukkit.entity.Entity entity) {
        return new PublicEntityScheduler(entity);
    }

    @Override
    public void runAt(@NotNull final Location location,
                      @NotNull final Consumer<EntityTaskContext> task) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(task, "task");

        final ServerLevel level = toNmsLevel(location.getWorld());
        if (level == null) return;

        final Vec3 vec = new Vec3(location.getX(), location.getY(), location.getZ());
        final long regionId = resolveRegionId(level, vec);
        final PluginId owner = resolveOwner();
        // Keep legacy Bukkit name for MiliUsageTracker compatibility
        final String pluginName = resolveCallingPluginName();

        // Wrap the task body with MiliUsageTracker (legacy compat) and forward
        // to the bridge which handles owner-stamping, lifecycle check & tracking.
        try {
            PluginSchedulerBridge.submit(owner, RegionTask.builder(regionId)
                    .name("mili-public-runAt")
                    .task((Runnable) () -> {
                        if (pluginName != null) MiliUsageTracker.setCurrentPlugin(pluginName);
                        try {
                            final EntityTaskContext publicCtx = new EntityTaskContext(
                                    UUID.randomUUID(), Bukkit.getServer(), level.getWorld().getName());
                            task.accept(publicCtx);
                        } finally {
                            MiliUsageTracker.clearCurrentPlugin();
                        }
                    })
                    .build());
        } catch (final SecurityException e) {
            // Lifecycle denial — log and swallow (the caller's task is not submitted)
            LOGGER.warn("[MiliScheduler] runAt rejected: {}", e.getMessage());
        }
    }

    @Override
    public void runAsync(@NotNull final Runnable task) {
        Objects.requireNonNull(task, "task");
        final PluginId owner = resolveOwner();
        final String pluginName = resolveCallingPluginName();

        try {
            PluginSchedulerBridge.submit(owner, RegionTask.builder(-1L)
                    .name("mili-public-runAsync")
                    .task((Runnable) () -> {
                        if (pluginName != null) MiliUsageTracker.setCurrentPlugin(pluginName);
                        MiliUsageTracker.markUsage();
                        try {
                            task.run();
                        } finally {
                            MiliUsageTracker.clearCurrentPlugin();
                        }
                    })
                    .build());
        } catch (final SecurityException e) {
            LOGGER.warn("[MiliScheduler] runAsync rejected: {}", e.getMessage());
        }
    }

    @Override
    public @NotNull String version() { return VERSION; }

    // ---- entity scheduler (inner class) ----------------------------------

    private final class PublicEntityScheduler implements EntityScheduler {
        private final WeakReference<org.bukkit.entity.Entity> entityRef;
        private final UUID entityUUID;

        PublicEntityScheduler(@NotNull final org.bukkit.entity.Entity entity) {
            this.entityRef = new WeakReference<>(entity);
            this.entityUUID = entity.getUniqueId();
        }

        @Override
        public void run(@NotNull final Consumer<EntityTaskContext> task) {
            final org.bukkit.entity.Entity entity = entityRef.get();
            if (entity == null || !entity.isValid()) return;
            final PluginId owner = resolveOwner();
            final String pluginName = resolveCallingPluginName();

            final Entity nmsEntity = ((CraftEntity) entity).getHandle();
            final long regionId = resolveRegionId(nmsEntity);
            final fun.bm.mili.lmili.thread.scheduler.api.EntityScheduler.EntityRef ref =
                    fun.bm.mili.lmili.thread.scheduler.api.EntityScheduler.EntityRef.of(
                            nmsEntity.getId(), nmsEntity.level(), regionId);

            try {
                PluginSchedulerBridge.submitEntity(owner, ref,
                        fun.bm.mili.lmili.thread.scheduler.api.EntityTask.ofRunnable(
                                "mili-public-entity-run",
                                () -> {
                                    if (pluginName != null) MiliUsageTracker.setCurrentPlugin(pluginName);
                                    MiliUsageTracker.markUsage();
                                    try {
                                        final EntityTaskContext publicCtx = new EntityTaskContext(
                                                entityUUID, Bukkit.getServer(), entity.getWorld().getName());
                                        task.accept(publicCtx);
                                    } finally {
                                        MiliUsageTracker.clearCurrentPlugin();
                                    }
                                }
                        ));
            } catch (final SecurityException e) {
                LOGGER.warn("[MiliScheduler] entity-run rejected: {}", e.getMessage());
            }
        }

        @Override
        public void runDelayed(@NotNull final Consumer<EntityTaskContext> task, final long delayTicks) {
            if (delayTicks < 0) throw new IllegalArgumentException("delayTicks must be >= 0");
            final org.bukkit.entity.Entity entity = entityRef.get();
            if (entity == null || !entity.isValid()) return;
            final PluginId owner = resolveOwner();
            final String pluginName = resolveCallingPluginName();

            final Entity nmsEntity = ((CraftEntity) entity).getHandle();
            final long regionId = resolveRegionId(nmsEntity);
            final fun.bm.mili.lmili.thread.scheduler.api.EntityScheduler.EntityRef ref =
                    fun.bm.mili.lmili.thread.scheduler.api.EntityScheduler.EntityRef.of(
                            nmsEntity.getId(), nmsEntity.level(), regionId);

            try {
                // For delayed entity tasks, we use the bridge's submitEntity but the
                // internal scheduler's submitDelayed pattern. Since the bridge doesn't
                // directly expose a delayed entity path, we submit to forEntity directly
                // but still check lifecycle first.
                // Lifecycle check
                if (!owner.equals(LMili.SYSTEM_OWNER_ID)) {
                    final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
                    final fun.bm.mili.lmili.api.identity.PluginStatus status =
                            mgr.getStatus(owner).orElse(null);
                    if (status == null || !status.canSchedule()) {
                        throw new SecurityException(
                                "Plugin " + owner.value() + " (status=" + status
                                        + ") cannot submit scheduler tasks");
                    }
                }
                final fun.bm.mili.lmili.thread.scheduler.api.EntityTask stampedTask =
                        new fun.bm.mili.lmili.thread.scheduler.api.EntityTask() {
                            @Override
                            public void execute(
                                    @NotNull final fun.bm.mili.lmili.thread.scheduler.api.EntityTask.EntityTaskContext ctx)
                                    throws Exception {
                                LMili.bindCurrentOwner(owner);
                                if (pluginName != null) MiliUsageTracker.setCurrentPlugin(pluginName);
                                MiliUsageTracker.markUsage();
                                try {
                                    final fun.bm.mili.api.EntityTaskContext publicCtx =
                                            new fun.bm.mili.api.EntityTaskContext(
                                            entityUUID, Bukkit.getServer(), entity.getWorld().getName());
                                    task.accept(publicCtx);
                                } finally {
                                    MiliUsageTracker.clearCurrentPlugin();
                                    LMili.clearCurrentOwner();
                                }
                            }

                            @Override
                            public @NotNull String name() {
                                return "[" + owner.value() + "] mili-public-entity-runDelayed";
                            }
                        };

                internalScheduler.forEntity(ref).submitDelayed(stampedTask, delayTicks);
            } catch (final SecurityException e) {
                LOGGER.warn("[MiliScheduler] entity-runDelayed rejected: {}", e.getMessage());
            }
        }

        private static long resolveRegionId(@NotNull final Entity nmsEntity) {
            final int cx = net.minecraft.core.BlockPos.containing(
                    nmsEntity.getX(), nmsEntity.getY(), nmsEntity.getZ()).getX() >> 4;
            final int cz = net.minecraft.core.BlockPos.containing(
                    nmsEntity.getX(), nmsEntity.getY(), nmsEntity.getZ()).getZ() >> 4;
            return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static ServerLevel toNmsLevel(final World world) {
        if (world == null) return null;
        final MinecraftServer server = ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).getServer();
        return server.getLevel(((org.bukkit.craftbukkit.CraftWorld) world).getHandle().dimension());
    }

    private static long resolveRegionId(final ServerLevel level, final Vec3 position) {
        if (position == null) return -1;
        final int cx = net.minecraft.core.BlockPos.containing(position).getX() >> 4;
        final int cz = net.minecraft.core.BlockPos.containing(position).getZ() >> 4;
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    /**
     * Resolve the owning {@link PluginId} for the current submission.
     *
     * <p>Priority:</p>
     * <ol>
     *   <li>{@link LMili#currentOwnerOrSystem()} — the authoritative
     *       thread-local owner (set by {@code LMili.bindCurrentOwner()})</li>
     *   <li>If the above returns {@code lmili.system}, try the legacy
     *       stack-trace scan to identify the calling Bukkit plugin and map
     *       it to its registered {@link PluginId}.</li>
     *   <li>Fallback to {@code lmili.system} (framework-level task).</li>
     * </ol>
     */
    private static PluginId resolveOwner() {
        final PluginId bound = LMili.currentOwner();
        if (bound != null) return bound;

        // Legacy stack-trace scan → Bukkit plugin name → PluginId
        final String pluginName = resolveCallingPluginName();
        if (pluginName != null && !pluginName.equals("unknown")) {
            final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            return mgr.findByBukkitName(pluginName)
                    .map(identity -> identity.id())
                    .orElseGet(() -> {
                        // Not registered; try normalised fallback
                        final PluginId fallback = PluginId.tryNormalize(pluginName);
                        return fallback != null ? fallback : LMili.SYSTEM_OWNER_ID;
                    });
        }

        return LMili.SYSTEM_OWNER_ID;
    }

    /** Legacy stack-trace-based caller detection, returns the Bukkit plugin name. */
    private static String resolveCallingPluginName() {
        try {
            final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(stack.length, 15); i++) {
                final String className = stack[i].getClassName();
                if (className.startsWith("fun.bm.mili.") || className.startsWith("org.bukkit.")) {
                    continue;
                }
                for (final org.bukkit.plugin.Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                    if (className.startsWith(plugin.getClass().getPackage().getName())) {
                        return plugin.getName();
                    }
                }
            }
        } catch (final Throwable ignored) {
            // fall through
        }
        return null;
    }
}