package fun.bm.mili.api.world;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 世界同步任务结果 —— 包含执行结果和上下文信息。
 *
 * <p>与 {@link fun.bm.mili.api.SyncTaskResult} 类似，但额外包含世界上下文信息。
 *
 * @param <T> 返回值类型
 * @since 2.0.0
 */
public interface WorldSyncTaskResult<T> {

    /**
     * 检查任务是否成功完成。
     *
     * @return true 如果任务成功
     */
    boolean isSuccess();

    /**
     * 获取任务结果值。
     *
     * @return 结果值，如果失败返回 empty
     */
    @NotNull Optional<T> result();

    /**
     * 获取错误信息。
     *
     * @return 错误信息，如果成功返回 empty
     */
    @NotNull Optional<String> error();

    /**
     * 获取任务执行耗时。
     *
     * @return 执行耗时
     */
    long executionTimeNanos();

    /**
     * 获取任务执行耗时的毫秒数。
     *
     * @return 执行耗时 (ms)
     */
    default double executionTimeMs() {
        return executionTimeNanos() / 1_000_000.0;
    }

    /**
     * 创建成功结果。
     *
     * @param <T>     返回值类型
     * @param value   结果值
     * @param elapsed 执行耗时 (纳秒)
     * @return 成功结果实例
     */
    @NotNull
    static <T> WorldSyncTaskResult<T> success(@Nullable T value, long elapsed) {
        return new WorldSyncTaskResultImpl<>(value, null, elapsed, true);
    }

    /**
     * 创建失败结果。
     *
     * @param <T>     返回值类型
     * @param error   错误信息
     * @param elapsed 执行耗时 (纳秒)
     * @return 失败结果实例
     */
    @NotNull
    static <T> WorldSyncTaskResult<T> failure(@NotNull String error, long elapsed) {
        return new WorldSyncTaskResultImpl<>(null, error, elapsed, false);
    }

    /**
     * 创建超时结果。
     *
     * @param <T>          返回值类型
     * @param timeoutMs    超时毫秒数
     * @param actualElapsed 实际执行耗时 (纳秒)
     * @return 超时结果实例
     */
    @NotNull
    static <T> WorldSyncTaskResult<T> timeout(long timeoutMs, long actualElapsed) {
        return new WorldSyncTaskResultImpl<>(null,
            "Task timed out after " + timeoutMs + "ms", actualElapsed, false);
    }

    /**
     * 内部实现类。
     */
    final class WorldSyncTaskResultImpl<T> implements WorldSyncTaskResult<T> {
        private final T value;
        private final String error;
        private final long elapsedNanos;
        private final boolean success;

        WorldSyncTaskResultImpl(T value, String error, long elapsedNanos, boolean success) {
            this.value = value;
            this.error = error;
            this.elapsedNanos = elapsedNanos;
            this.success = success;
        }

        @Override
        public boolean isSuccess() { return success; }

        @Override
        @NotNull
        public Optional<T> result() { return Optional.ofNullable(value); }

        @Override
        @NotNull
        public Optional<String> error() { return Optional.ofNullable(error); }

        @Override
        public long executionTimeNanos() { return elapsedNanos; }
    }
}
