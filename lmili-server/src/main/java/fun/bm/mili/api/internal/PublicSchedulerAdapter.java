package fun.bm.mili.api.internal;

import fun.bm.mili.api.EntityScheduler;
import fun.bm.mili.api.EntityTaskContext;
import fun.bm.mili.api.MiliUsageTracker;
import fun.bm.mili.api.Scheduler;
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

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
 * </ul>
 */
public final class PublicSchedulerAdapter implements Scheduler {

    private static final String VERSION = "5.0.0-public";

    private final MiliScheduler internalScheduler;

    public PublicSchedulerAdapter(@NotNull final MiliScheduler internalScheduler) {
        this.internalScheduler = internalScheduler;
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

        ServerLevel level = toNmsLevel(location.getWorld());
        if (level == null) return;

        Vec3 vec = new Vec3(location.getX(), location.getY(), location.getZ());
        long regionId = resolveRegionId(level, vec);
        String pluginName = resolveCallingPlugin();

        internalScheduler.submit(RegionTask.builder(regionId)
                .name("mili-public-runAt")
                .task((Runnable) () -> {
                    MiliUsageTracker.setCurrentPlugin(pluginName);
                    try {
                        EntityTaskContext publicCtx = new EntityTaskContext(
                                UUID.randomUUID(), Bukkit.getServer(), level.getWorld().getName());
                        task.accept(publicCtx);
                    } finally {
                        MiliUsageTracker.clearCurrentPlugin();
                    }
                })
                .build());
    }

    @Override
    public void runAsync(@NotNull final Runnable task) {
        Objects.requireNonNull(task, "task");
        String pluginName = resolveCallingPlugin();
        internalScheduler.submit(RegionTask.builder(-1L)
                .name("mili-public-runAsync")
                .task((Runnable) () -> {
                    MiliUsageTracker.setCurrentPlugin(pluginName);
                    MiliUsageTracker.markUsage();
                    try {
                        task.run();
                    } finally {
                        MiliUsageTracker.clearCurrentPlugin();
                    }
                })
                .build());
    }

    @Override
    public @NotNull String version() { return VERSION; }

    private final class PublicEntityScheduler implements EntityScheduler {
        private final WeakReference<org.bukkit.entity.Entity> entityRef;
        private final UUID entityUUID;

        PublicEntityScheduler(@NotNull final org.bukkit.entity.Entity entity) {
            this.entityRef = new WeakReference<>(entity);
            this.entityUUID = entity.getUniqueId();
        }

        @Override
        public void run(@NotNull final Consumer<EntityTaskContext> task) {
            org.bukkit.entity.Entity entity = entityRef.get();
            if (entity == null || !entity.isValid()) return;
            String pluginName = resolveCallingPlugin();

            Entity nmsEntity = ((CraftEntity) entity).getHandle();
            // C-10 修复：从实体位置计算 regionId（entity → position → chunk → region）
            long regionId = resolveRegionId(nmsEntity);
            fun.bm.mili.lmili.thread.scheduler.api.EntityScheduler.EntityRef ref =
                    fun.bm.mili.lmili.thread.scheduler.api.EntityScheduler.EntityRef.of(
                            nmsEntity.getId(), nmsEntity.level(), regionId);

            internalScheduler.forEntity(ref).submit(
                    fun.bm.mili.lmili.thread.scheduler.api.EntityTask.ofRunnable(
                            "mili-public-entity-run",
                            () -> {
                                MiliUsageTracker.setCurrentPlugin(pluginName);
                                MiliUsageTracker.markUsage();
                                try {
                                    EntityTaskContext publicCtx = new EntityTaskContext(
                                            entityUUID, Bukkit.getServer(), entity.getWorld().getName());
                                    task.accept(publicCtx);
                                } finally {
                                    MiliUsageTracker.clearCurrentPlugin();
                                }
                            }
                    )
            );
        }

        @Override
        public void runDelayed(@NotNull final Consumer<EntityTaskContext> task, final long delayTicks) {
            if (delayTicks < 0) throw new IllegalArgumentException("delayTicks must be >= 0");
            org.bukkit.entity.Entity entity = entityRef.get();
            if (entity == null || !entity.isValid()) return;
            String pluginName = resolveCallingPlugin();

            Entity nmsEntity = ((CraftEntity) entity).getHandle();
            // C-10 修复：从实体位置计算 regionId（entity → position → chunk → region）
            long regionId = resolveRegionId(nmsEntity);
            fun.bm.mili.lmili.thread.scheduler.api.EntityScheduler.EntityRef ref =
                    fun.bm.mili.lmili.thread.scheduler.api.EntityScheduler.EntityRef.of(
                            nmsEntity.getId(), nmsEntity.level(), regionId);

            internalScheduler.forEntity(ref).submitDelayed(
                    fun.bm.mili.lmili.thread.scheduler.api.EntityTask.ofRunnable(
                            "mili-public-entity-runDelayed",
                            () -> {
                                MiliUsageTracker.setCurrentPlugin(pluginName);
                                MiliUsageTracker.markUsage();
                                try {
                                    EntityTaskContext publicCtx = new EntityTaskContext(
                                            entityUUID, Bukkit.getServer(), entity.getWorld().getName());
                                    task.accept(publicCtx);
                                } finally {
                                    MiliUsageTracker.clearCurrentPlugin();
                                }
                            }
                    ),
                    delayTicks
            );
        }

        /**
         * C-10 修复：从 NMS Entity 位置计算 regionId。
         *
         * <p>映射链：entity → position → chunk coordinates → regionId。
         * 这确保同一 region 的所有实体共享同一队列。
         */
        private static long resolveRegionId(@NotNull final Entity nmsEntity) {
            int cx = net.minecraft.core.BlockPos.containing(nmsEntity.getX(), nmsEntity.getY(), nmsEntity.getZ()).getX() >> 4;
            int cz = net.minecraft.core.BlockPos.containing(nmsEntity.getX(), nmsEntity.getY(), nmsEntity.getZ()).getZ() >> 4;
            return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
        }
    }

    private static ServerLevel toNmsLevel(final World world) {
        if (world == null) return null;
        MinecraftServer server = ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).getServer();
        return server.getLevel(((org.bukkit.craftbukkit.CraftWorld) world).getHandle().dimension());
    }

    private static long resolveRegionId(final ServerLevel level, final Vec3 position) {
        if (position == null) return -1;
        int cx = net.minecraft.core.BlockPos.containing(position).getX() >> 4;
        int cz = net.minecraft.core.BlockPos.containing(position).getZ() >> 4;
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    private static String resolveCallingPlugin() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(stack.length, 15); i++) {
                String className = stack[i].getClassName();
                if (className.startsWith("fun.bm.mili.") || className.startsWith("org.bukkit.")) continue;
                for (org.bukkit.plugin.Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                    if (className.startsWith(plugin.getClass().getPackage().getName())) {
                        return plugin.getName();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return "unknown";
    }
}
