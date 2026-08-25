package fun.bm.mili.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 同步任务执行结果。
 *
 * <p>封装同步任务的执行结果，包含成功值或失败原因。
 * 使用 {@link #isSuccess()} 检查结果状态，避免 null 检查。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * SyncTaskResult<String> result = scheduler.runSync(() -> "hello");
 * if (result.isSuccess()) {
 *     System.out.println("Got: " + result.value());
 * } else {
 *     System.out.println("Failed: " + result.failureReason());
 * }
 *
 * // 或使用 Optional 风格
 * result.valueOpt().ifPresent(v -> System.out.println("Got: " + v));
 * }</pre>
 *
 * @param <T> 返回值类型
 * @since 2.0.0
 */
public sealed interface SyncTaskResult<T> {

    /**
     * 创建成功结果。
     *
     * @param value 值（可为 null）
     * @param <T>   类型
     * @return 成功结果
     */
    @NotNull
    static <T> SyncTaskResult<T> success(@Nullable T value) {
        return new Success<>(value, 0, null);
    }

    /**
     * 创建成功结果（带执行时间）。
     *
     * @param value           值
     * @param executionTimeNs 执行时间（纳秒）
     * @param <T>             类型
     * @return 成功结果
     */
    @NotNull
    static <T> SyncTaskResult<T> success(@Nullable T value, long executionTimeNs) {
        return new Success<>(value, executionTimeNs, null);
    }

    /**
     * 创建失败结果。
     *
     * @param reason 失败原因
     * @param <T>    类型
     * @return 失败结果
     */
    @NotNull
    static <T> SyncTaskResult<T> failure(@NotNull String reason) {
        return new Failure<>(reason, null, 0, false);
    }

    /**
     * 创建失败原因。
     *
     * @param reason    失败原因
     * @param throwable 异常（可为 null）
     * @param <T>       类型
     * @return 失败结果
     */
    @NotNull
    static <T> SyncTaskResult<T> failure(@NotNull String reason, @Nullable Throwable throwable) {
        return new Failure<>(reason, throwable, 0, false);
    }

    /**
     * 创建超时结果。
     *
     * @param timeoutMs 超时时间（毫秒）
     * @param <T>       类型
     * @return 超时结果
     */
    @NotNull
    static <T> SyncTaskResult<T> timeout(long timeoutMs) {
        return new Failure<>("Sync task timed out after " + timeoutMs + "ms", null, timeoutMs * 1_000_000, true);
    }

    /**
     * 检查是否成功。
     *
     * @return true 如果任务成功完成
     */
    boolean isSuccess();

    /**
     * 检查是否失败。
     *
     * @return true 如果任务失败
     */
    default boolean isFailure() {
        return !isSuccess();
    }

    /**
     * 检查是否超时。
     *
     * @return true 如果任务因超时失败
     */
    boolean isTimeout();

    /**
     * 获取值（成功时）。
     *
     * @return 值
     * @throws IllegalStateException 如果任务失败
     */
    @Nullable
    T value();

    /**
     * 获取值作为 Optional。
     *
     * @return 包含值的 Optional，失败时为 empty
     */
    @NotNull
    Optional<T> valueOpt();

    /**
     * 获取失败原因（失败时）。
     *
     * @return 失败原因
     * @throws IllegalStateException 如果任务成功
     */
    @NotNull
    String failureReason();

    /**
     * 获取异常（如果有）。
     *
     * @return 异常，可能为 null
     */
    @Nullable
    Throwable exception();

    /**
     * 获取执行时间。
     *
     * @return 执行时间
     */
    @NotNull
    Duration executionTime();

    /**
     * 记录执行时间。
     *
     * @param nanos 纳秒
     */
    void recordExecutionTime(long nanos);

    // ---- 内部实现记录 ----

    record Success<T>(@Nullable T value, long executionTimeNanos, @Nullable TimeUnit unit) implements SyncTaskResult<T> {
        @Override public boolean isSuccess() { return true; }
        @Override public boolean isTimeout() { return false; }
        @Override public @Nullable T value() { return value; }
        @Override public @NotNull Optional<T> valueOpt() { return Optional.ofNullable(value); }
        @Override public @NotNull String failureReason() { throw new IllegalStateException("Result is successful"); }
        @Override public @Nullable Throwable exception() { return null; }
        @Override public @NotNull Duration executionTime() { return new Duration(executionTimeNanos); }
        @Override public void recordExecutionTime(long nanos) { /* no-op */ }
    }

    record Failure<T>(@NotNull String reason, @Nullable Throwable throwable, long executionTimeNanos, boolean timedOut) implements SyncTaskResult<T> {
        @Override public boolean isSuccess() { return false; }
        @Override public boolean isTimeout() { return timedOut; }
        @Override public @Nullable T value() { throw new IllegalStateException("Result failed: " + reason); }
        @Override public @NotNull Optional<T> valueOpt() { return Optional.empty(); }
        @Override public @NotNull String failureReason() { return reason; }
        @Override public @Nullable Throwable exception() { return throwable; }
        @Override public @NotNull Duration executionTime() { return new Duration(executionTimeNanos); }
        @Override public void recordExecutionTime(long nanos) { /* no-op */ }
    }

    /**
     * 执行时间。
     */
    record Duration(long nanos) {
        public long toNanos() { return nanos; }
        public long toMicros() { return nanos / 1_000; }
        public long toMillis() { return nanos / 1_000_000; }
        public double toMillisF() { return nanos / 1_000_000.0; }

        @Override
        @NotNull
        public String toString() {
            if (nanos < 1_000) return nanos + "ns";
            if (nanos < 1_000_000) return (nanos / 1_000) + "μs";
            return String.format("%.2fms", toMillisF());
        }
    }
}
