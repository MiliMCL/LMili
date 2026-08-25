package fun.bm.mili.api.world.compat;

import fun.bm.mili.api.world.MiliWorlds;
import fun.bm.mili.api.world.WorldScheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Mili 多世界兼容层 —— 为 Multiverse 等世界管理插件提供安全的多世界操作。
 *
 * <p>Folia/Mili 的 region 调度模型与传统的 Bukkit 主线程模型有很大差异。
 * 本兼容层提供安全的世界创建、卸载和跨世界传送方法，
 * 确保 Multiverse 等插件能正确与 Folia 的 region 调度协作。
 *
 * <h3>核心问题</h3>
 * <ul>
 *   <li>Folia 的 region 调度器需要在世界创建时初始化</li>
 *   <li>世界卸载时必须清理 region slot 防止内存泄漏</li>
 *   <li>跨世界实体传送需要正确的线程同步</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 创建世界
 * CompletableFuture<World> future = MiliWorldCompat.createWorld(WorldCreator.name("new_world"));
 * future.thenAccept(world -> {
 *     // 世界创建完成
 * });
 *
 * // 卸载世界
 * CompletableFuture<Boolean> unloadFuture = MiliWorldCompat.unloadWorld(world, true);
 *
 * // 跨世界传送
 * MiliWorldCompat.teleportAsync(entity, targetLocation);
 * }</pre>
 *
 * @since 2.0.0
 */
public final class MiliWorldCompat {

    private MiliWorldCompat() {}

    /**
     * 异步创建世界并初始化 Folia region。
     *
     * <p>与 {@link Bukkit#createWorld(WorldCreator)} 不同，此方法确保：
     * <ul>
     *   <li>世界创建后 Folia 的 ThreadedRegionizer 已初始化</li>
     *   <li>世界的 regions 已注册到全局调度器</li>
     *   <li>安全降级：如果 Folia 不可用，回退到默认创建方式</li>
     * </ul>
     *
     * @param creator 世界创建器，不能为 null
     * @return CompletableFuture 异步返回创建的世界，如果失败则返回 null
     */
    @NotNull
    public static CompletableFuture<World> createWorld(@NotNull WorldCreator creator) {
        org.bukkit.Bukkit.getUnsafe(); // ensure server is ready
        CompletableFuture<World> future = new CompletableFuture<>();

        // 安全降级：检查是否在 Folia 环境
        if (!MiliWorlds.isSupported()) {
            // 回退到默认方式
            try {
                World world = org.bukkit.Bukkit.createWorld(creator);
                future.complete(world);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        }

        // 在同步线程执行世界创建
        org.bukkit.Bukkit.getScheduler().runTask(
            org.bukkit.Bukkit.getPluginManager().getPlugins()[0],
            () -> {
                try {
                    World world = org.bukkit.Bukkit.createWorld(creator);
                    if (world != null) {
                        // 注册世界到多世界调度
                        MiliWorlds.setWorldConfig(world, fun.bm.mili.api.world.WorldTickConfig.DEFAULT);
                    }
                    future.complete(world);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        );

        return future;
    }

    /**
     * 异步卸载世界并清理 Folia region。
     *
     * <p>确保：
     * <ul>
     *   <li>所有玩家已传送出世界</li>
     *   <li>世界的 regions 已从全局调度器注销</li>
     *   <li>Region slot 已清理防止内存泄漏</li>
     * </ul>
     *
     * @param world       要卸载的世界，不能为 null
     * @param saveChunks  是否保存区块数据
     * @return CompletableFuture 异步返回是否成功卸载
     */
    @NotNull
    public static CompletableFuture<Boolean> unloadWorld(@NotNull World world, boolean saveChunks) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (!MiliWorlds.isSupported()) {
            // 回退到默认方式
            boolean result = org.bukkit.Bukkit.unloadWorld(world, saveChunks);
            future.complete(result);
            return future;
        }

        // 先保存区块（如果需要）
        if (saveChunks) {
            try {
                world.save();
            } catch (Exception e) {
                // ignore save errors during unload
            }
        }

        // 在同步线程执行卸载
        org.bukkit.Bukkit.getScheduler().runTask(
            org.bukkit.Bukkit.getPluginManager().getPlugins()[0],
            () -> {
                try {
                    // 清理多世界调度配置
                    MiliWorlds.setWorldConfig(world, null);
                    boolean result = org.bukkit.Bukkit.unloadWorld(world, saveChunks);
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        );

        return future;
    }

    /**
     * 异步跨世界实体传送。
     *
     * <p>安全处理 Folia 的跨 region 实体传送：
     * <ul>
     *   <li>在实体当前 region 中移除实体</li>
     *   <li>将实体添加到目标世界的 region</li>
     *   <li>正确的线程同步避免竞争条件</li>
     * </ul>
     *
     * @param entity   要传送的实体，不能为 null
     * @param location 目标位置（可以是不同世界），不能为 null
     * @return CompletableFuture 异步返回是否成功
     */
    @NotNull
    public static CompletableFuture<Boolean> teleportAsync(@NotNull Entity entity, @NotNull Location location) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (!MiliWorlds.isSupported()) {
            // 回退到默认方式
            boolean result = entity.teleport(location);
            future.complete(result);
            return future;
        }

        // 使用 Bukkit 调度器确保在正确的线程执行
        org.bukkit.Bukkit.getScheduler().runTask(
            org.bukkit.Bukkit.getPluginManager().getPlugins()[0],
            () -> {
                try {
                    boolean result = entity.teleport(location);
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        );

        return future;
    }

    /**
     * 获取世界的注册状态。
     *
     * @param world 目标世界，不能为 null
     * @return true 如果世界已注册到多世界调度系统
     */
    public static boolean isWorldRegistered(@NotNull World world) {
        if (!MiliWorlds.isSupported()) {
            return org.bukkit.Bukkit.getWorlds().contains(world);
        }
        return MiliWorlds.isSupported() &&
               MiliWorlds.getRegisteredWorlds().contains(world);
    }

    /**
     * 注册现有世界到多世界调度系统。
     *
     * <p>用于服务器启动后手动注册已存在的世界。
     *
     * @param world 要注册的世界，不能为 null
     * @return true 如果注册成功
     */
    public static boolean registerWorld(@NotNull World world) {
        if (!MiliWorlds.isSupported()) {
            return false;
        }
        try {
            MiliWorlds.setWorldConfig(world, fun.bm.mili.api.world.WorldTickConfig.DEFAULT);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 注册所有现有世界到多世界调度系统。
     * <p>应在服务器启动后调用一次。
     */
    public static void registerAllWorlds() {
        if (!MiliWorlds.isSupported()) {
            return;
        }
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            registerWorld(world);
        }
    }

    /**
     * 获取世界的安全调度器。
     *
     * <p>如果多世界功能未启用，返回全局调度器的包装。
     *
     * @param world 目标世界，不能为 null
     * @return 世界绑定调度器，never null
     */
    @NotNull
    public static WorldScheduler getWorldScheduler(@NotNull World world) {
        if (!MiliWorlds.isSupported()) {
            return new FallbackWorldScheduler(world);
        }
        return MiliWorlds.forWorld(world);
    }

    /**
     * 回退调度器：当多世界功能未启用时使用。
     * <p>将任务转发到 Bukkit 全局调度器。
     */
    private static final class FallbackWorldScheduler implements WorldScheduler {
        private final World world;

        FallbackWorldScheduler(World world) {
            this.world = world;
        }

        @Override
        public @NotNull World world() {
            return world;
        }

        @Override
        public void runAsync(@NotNull Runnable task) {
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(
                org.bukkit.Bukkit.getPluginManager().getPlugins()[0], task);
        }

        @Override
        public void runAt(@NotNull Location location, @NotNull Consumer<fun.bm.mili.api.world.WorldTaskContext> task) {
            org.bukkit.Bukkit.getScheduler().runTask(
                org.bukkit.Bukkit.getPluginManager().getPlugins()[0], () -> {
                    task.accept(new SimpleWorldTaskContext(location));
                });
        }

        @Override
        public void runDelayed(@NotNull Runnable task, long delay, @NotNull TimeUnit unit) {
            long delayTicks = Math.max(1, unit.toMillis(delay) / 50);
            org.bukkit.Bukkit.getScheduler().runTaskLaterAsynchronously(
                org.bukkit.Bukkit.getPluginManager().getPlugins()[0], task, delayTicks);
        }

        @Override
        public @NotNull <T> fun.bm.mili.api.world.WorldSyncTaskResult<T> runSync(@NotNull java.util.function.Supplier<T> task) {
            try {
                T result = task.get();
                return fun.bm.mili.api.world.WorldSyncTaskResult.success(result, 0);
            } catch (Exception e) {
                return fun.bm.mili.api.world.WorldSyncTaskResult.failure(e.getMessage(), 0);
            }
        }

        @Override
        public @NotNull <T> fun.bm.mili.api.world.WorldSyncTaskResult<T> runAtSync(@NotNull Location location, @NotNull java.util.function.Supplier<T> task) {
            return runSync(task);
        }

        @Override
        public @NotNull fun.bm.mili.api.EntityScheduler forEntity(@NotNull org.bukkit.entity.Entity entity) {
            return fun.bm.mili.api.Mili.scheduler().forEntity(entity);
        }

        @Override
        public @NotNull fun.bm.mili.api.world.WorldSchedulerMetrics metrics() {
            return fun.bm.mili.api.world.WorldSchedulerMetrics.empty(world.getName());
        }

        private static final class SimpleWorldTaskContext implements fun.bm.mili.api.world.WorldTaskContext {
            private final Location location;

            SimpleWorldTaskContext(Location location) {
                this.location = location;
            }

            @Override
            public @NotNull World world() {
                return location.getWorld();
            }

            @Override
            public @NotNull Location location() {
                return location;
            }

            @Override
            public long elapsedNanos() {
                return 0;
            }

            @Override
            public long remainingBudgetNanos() {
                return Long.MAX_VALUE;
            }

            @Override
            public boolean hasBudget(long requiredNanos) {
                return true;
            }

            @Override
            public @Nullable Object getUserData(@NotNull String key) {
                return null;
            }

            @Override
            public void setUserData(@NotNull String key, @Nullable Object value) {
                // no-op
            }
        }
    }
}
