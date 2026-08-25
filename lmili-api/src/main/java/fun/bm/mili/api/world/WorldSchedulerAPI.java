package fun.bm.mili.api.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 多世界调度公开 API —— 在特定世界中执行任务的便捷入口。
 *
 * <p>提供无需获取 {@link WorldScheduler} 实例的直接调度方法。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 在世界中异步执行任务
 * WorldSchedulerAPI.runAsync(world, () -> { ... });
 *
 * // 在世界中同步执行任务
 * WorldSyncTaskResult<String> result = WorldSchedulerAPI.runSync(world, () -> "hello");
 *
 * // 在世界的指定位置执行任务
 * WorldSchedulerAPI.runAt(world, location, ctx -> { ... });
 * }</pre>
 *
 * <h3>兼容性</h3>
 * <p>如果服务端未实现多世界功能，调用会安全降级：
 * <ul>
 *   <li>异步任务 - 转发到全局调度器</li>
 *   <li>同步任务 - 在当前线程执行</li>
 *   <li>位置任务 - 降级为普通调度</li>
 * </ul>
 *
 * @since 2.0.0
 * @see MiliWorlds
 * @see WorldScheduler
 */
public final class WorldSchedulerAPI {

    private WorldSchedulerAPI() {}

    /**
     * 在世界上下文中异步执行任务。
     *
     * <p>如果多世界功能未启用，任务会被转发到全局异步调度器。
     *
     * @param world 目标世界，不能为 null
     * @param task  要执行的任务，不能为 null
     */
    public static void runAsync(@NotNull World world, @NotNull Runnable task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        MiliWorlds.forWorld(world).runAsync(task);
    }

    /**
     * 在世界中指定位置异步执行任务。
     *
     * @param world    目标世界，不能为 null
     * @param location 目标位置，不能为 null
     * @param task     要执行的任务，接收 WorldTaskContext
     */
    public static void runAt(@NotNull World world, @NotNull Location location,
                              @NotNull Consumer<WorldTaskContext> task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(task, "task");
        MiliWorlds.forWorld(world).runAt(location, task);
    }

    /**
     * 在世界中延迟执行任务。
     *
     * @param world  目标世界，不能为 null
     * @param task   要执行的任务，不能为 null
     * @param delay  延迟时间
     * @param unit   时间单位，不能为 null
     */
    public static void runDelayed(@NotNull World world, @NotNull Runnable task,
                                   long delay, @NotNull TimeUnit unit) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        if (delay < 0) throw new IllegalArgumentException("delay must be >= 0");
        MiliWorlds.forWorld(world).runDelayed(task, delay, unit);
    }

    /**
     * 在世界上下文中同步执行任务并返回结果。
     *
     * @param <T>   返回值类型
     * @param world 目标世界，不能为 null
     * @param task  要执行的任务，不能为 null
     * @return 同步任务结果
     */
    @NotNull
    public static <T> WorldSyncTaskResult<T> runSync(@NotNull World world,
                                                       @NotNull Supplier<T> task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        return MiliWorlds.forWorld(world).runSync(task);
    }

    /**
     * 在世界中指定位置同步执行任务。
     *
     * @param <T>      返回值类型
     * @param world    目标世界，不能为 null
     * @param location 目标位置，不能为 null
     * @param task     要执行的任务，不能为 null
     * @return 同步任务结果
     */
    @NotNull
    public static <T> WorldSyncTaskResult<T> runAtSync(@NotNull World world,
                                                         @NotNull Location location,
                                                         @NotNull Supplier<T> task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(task, "task");
        return MiliWorlds.forWorld(world).runAtSync(location, task);
    }

    /**
     * 在世界上下文中对实体执行绑定调度。
     *
     * <p>所有提交的任务会在该实体所在的区域中顺序执行。
     *
     * @param world  目标世界，不能为 null
     * @param entity 目标实体，不能为 null
     * @return 实体绑定调度器
     */
    @NotNull
    public static fun.bm.mili.api.EntityScheduler forEntity(@NotNull World world,
                                                              @NotNull Entity entity) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(entity, "entity");
        return MiliWorlds.forWorld(world).forEntity(entity);
    }

    /**
     * 注册一个世界到多世界调度系统。
     *
     * @param world 要注册的世界，不能为 null
     */
    public static void registerWorld(@NotNull World world) {
        Objects.requireNonNull(world, "world");
        MiliWorlds.registerRegistry(createDefaultRegistry());
    }

    /**
     * 从多世界调度系统注销一个世界。
     *
     * @param world 要注销的世界，不能为 null
     */
    public static void unregisterWorld(@NotNull World world) {
        Objects.requireNonNull(world, "world");
        if (MiliWorlds.isSupported()) {
            // 通过反射或接口调用注册表
        }
    }

    /**
     * 获取指定世界的调度指标。
     *
     * @param world 目标世界，不能为 null
     * @return 调度指标
     */
    @NotNull
    public static WorldSchedulerMetrics metrics(@NotNull World world) {
        Objects.requireNonNull(world, "world");
        return MiliWorlds.forWorld(world).metrics();
    }

    // ---- 内部方法 ----

    /**
     * 创建默认注册表实例。
     */
    @NotNull
    private static WorldSchedulerRegistry createDefaultRegistry() {
        return new NoopWorldSchedulerRegistry();
    }

    /**
     * No-op 注册表实现。
     */
    private static final class NoopWorldSchedulerRegistry implements WorldSchedulerRegistry {
        @Override
        @NotNull
        public WorldScheduler forWorld(@NotNull World world) {
            return NoopWorldScheduler.INSTANCE;
        }

        @Override
        @NotNull
        public WorldTickConfig getConfig(@NotNull World world) {
            return WorldTickConfig.DEFAULT;
        }

        @Override
        public void setConfig(@NotNull World world, @NotNull WorldTickConfig config) {
            // no-op
        }

        @Override
        public void registerWorld(@NotNull World world) {
            // no-op
        }

        @Override
        public void unregisterWorld(@NotNull World world) {
            // no-op
        }

        @Override
        public boolean isRegistered(@NotNull World world) {
            return false;
        }

        @Override
        @NotNull
        public java.util.List<World> getRegisteredWorlds() {
            return java.util.List.of();
        }

        @Override
        @NotNull
        public String version() {
            return "noop-2.0.0";
        }
    }
}
