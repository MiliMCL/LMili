package fun.bm.mili.api.internal;

import fun.bm.mili.api.EntityScheduler;
import fun.bm.mili.api.EntityTaskContext;
import fun.bm.mili.api.MiliUsageTracker;
import fun.bm.mili.api.Scheduler;
import fun.bm.mili.lmili.thread.regiontick.api.MiliScheduler;
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
import java.util.function.Consumer;

/**
 * 将内部 VirtualThreadScheduler (NMS 类型) 适配为公共 Scheduler (Bukkit 类型)。
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

    private static final String VERSION = "4.0.0-public";

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
        String pluginName = resolveCallingPlugin();

        internalScheduler.runAt(level, vec, internalCtx -> {
            MiliUsageTracker.setCurrentPlugin(pluginName);
            try {
                EntityTaskContext publicCtx = new EntityTaskContext(
                        UUID.randomUUID(), Bukkit.getServer(), level.getWorld().getName());
                task.accept(publicCtx);
            } finally {
                MiliUsageTracker.clearCurrentPlugin();
            }
        });
    }

    @Override
    public void runAsync(@NotNull final Runnable task) {
        Objects.requireNonNull(task, "task");
        String pluginName = resolveCallingPlugin();
        internalScheduler.runAsync(() -> {
            MiliUsageTracker.setCurrentPlugin(pluginName);
            MiliUsageTracker.markUsage();
            try {
                task.run();
            } finally {
                MiliUsageTracker.clearCurrentPlugin();
            }
        });
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
            internalScheduler.forEntity(nmsEntity).run(internalCtx -> {
                MiliUsageTracker.setCurrentPlugin(pluginName);
                MiliUsageTracker.markUsage();
                try {
                    EntityTaskContext publicCtx = new EntityTaskContext(
                            entityUUID, Bukkit.getServer(), entity.getWorld().getName());
                    task.accept(publicCtx);
                } finally {
                    MiliUsageTracker.clearCurrentPlugin();
                }
            });
        }

        @Override
        public void runDelayed(@NotNull final Consumer<EntityTaskContext> task, final long delayTicks) {
            if (delayTicks < 0) throw new IllegalArgumentException("delayTicks must be >= 0");
            org.bukkit.entity.Entity entity = entityRef.get();
            if (entity == null || !entity.isValid()) return;
            String pluginName = resolveCallingPlugin();

            Entity nmsEntity = ((CraftEntity) entity).getHandle();
            internalScheduler.forEntity(nmsEntity).runDelayed(internalCtx -> {
                MiliUsageTracker.setCurrentPlugin(pluginName);
                MiliUsageTracker.markUsage();
                try {
                    EntityTaskContext publicCtx = new EntityTaskContext(
                            entityUUID, Bukkit.getServer(), entity.getWorld().getName());
                    task.accept(publicCtx);
                } finally {
                    MiliUsageTracker.clearCurrentPlugin();
                }
            }, delayTicks);
        }
    }

    private static ServerLevel toNmsLevel(final World world) {
        if (world == null) return null;
        MinecraftServer server = ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).getServer();
        return server.getLevel(((org.bukkit.craftbukkit.CraftWorld) world).getHandle().dimension());
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
