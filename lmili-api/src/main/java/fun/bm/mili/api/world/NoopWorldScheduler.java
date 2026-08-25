package fun.bm.mili.api.world;

import fun.bm.mili.api.EntityScheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * No-op 世界调度器 —— 当多世界功能未启用时的安全降级实现。
 *
 * <p>所有方法均为空操作或返回默认值，不会抛出异常。
 *
 * @since 2.0.0
 */
final class NoopWorldScheduler implements WorldScheduler {

    static final NoopWorldScheduler INSTANCE = new NoopWorldScheduler();

    private NoopWorldScheduler() {}

    @Override
    @NotNull
    public World world() {
        throw new UnsupportedOperationException("MiliWorlds not supported on this server");
    }

    @Override
    public void runAsync(@NotNull Runnable task) {
        // no-op: 多世界功能未启用
    }

    @Override
    public void runAt(@NotNull Location location, @NotNull Consumer<WorldTaskContext> task) {
        // no-op: 多世界功能未启用
    }

    @Override
    public void runDelayed(@NotNull Runnable task, long delay, @NotNull TimeUnit unit) {
        // no-op: 多世界功能未启用
    }

    @Override
    @NotNull
    public <T> WorldSyncTaskResult<T> runSync(@NotNull Supplier<T> task) {
        try {
            T result = task.get();
            return WorldSyncTaskResult.success(result, 0);
        } catch (Exception e) {
            return WorldSyncTaskResult.failure(e.getMessage(), 0);
        }
    }

    @Override
    @NotNull
    public <T> WorldSyncTaskResult<T> runAtSync(@NotNull Location location, @NotNull Supplier<T> task) {
        return runSync(task);
    }

    @Override
    @NotNull
    public EntityScheduler forEntity(@NotNull Entity entity) {
        return fun.bm.mili.api.Mili.scheduler().forEntity(entity);
    }

    @Override
    @NotNull
    public WorldSchedulerMetrics metrics() {
        return WorldSchedulerMetrics.empty("noop");
    }
}
