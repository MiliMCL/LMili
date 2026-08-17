package fun.bm.mili.lmili.thread.scheduler.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 任务句柄 —— 用于追踪异步任务的完成状态。
 *
 * <p>核心设计原则：{@link TaskHandle} 从 {@link MiliScheduler#submit(RegionTask)} 立即返回，
 * 不阻塞调用线程。可以通过 {@link #onComplete(Consumer)} 注册非阻塞回调，或通过
 * {@link #await()} 阻塞等待完成（仅在必要时使用）。
 *
 * <h3>线程安全</h3>
 * <p>本类是线程安全的。多个线程可以同时调用 {@link #onComplete(Consumer)} 注册回调，
 * 所有回调会在任务完成后被调用。
 *
 * <h3>生命周期</h3>
 * <p>每个 TaskHandle 追踪一个或多个子任务的完成状态。当所有子任务完成后，
 * handle 标记为 completed。如果任一子任务异常终止，handle 标记为 failed，
 * 可以通过 {@link #failureCause()} 获取异常原因。
 */
public interface TaskHandle {

    /**
     * 任务状态枚举。
     */
    enum State {
        /** 任务已提交，正在执行或等待执行。 */
        PENDING,
        /** 任务已全部成功完成。 */
        COMPLETED,
        /** 任务执行过程中发生异常。 */
        FAILED,
        /** 任务已被取消（通常是 scheduler 关闭）。 */
        CANCELLED
    }

    /**
     * 获取当前任务状态。
     */
    @NotNull State state();

    /**
     * 当任务完成（成功或失败）时注册回调。
     *
     * <p>如果任务已完成，回调会立即在调用线程上执行。
     * 如果任务尚未完成，回调会在任务完成时异步执行。
     *
     * @param callback 完成回调，接收此 handle
     */
    void onComplete(@NotNull Consumer<TaskHandle> callback);

    /**
     * 注册成功完成回调。
     *
     * <p>仅在任务成功完成时调用，失败或取消时不会触发。
     *
     * @param callback 成功回调
     */
    default void onSuccess(@NotNull Consumer<TaskHandle> callback) {
        onComplete(handle -> {
            if (handle.state() == State.COMPLETED) callback.accept(handle);
        });
    }

    /**
     * 注册失败回调。
     *
     * <p>仅在任务失败时调用。
     *
     * @param callback 失败回调，接收异常原因
     */
    default void onFailure(@NotNull Consumer<Throwable> callback) {
        onComplete(handle -> {
            if (handle.state() == State.FAILED) callback.accept(handle.failureCause());
        });
    }

    /**
     * 阻塞等待任务完成（带超时）。
     *
     * <p><b>注意</b>：此方法会阻塞调用线程。在 region tick 线程上应避免使用。
     * 仅在平台线程或测试代码中使用。
     *
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return true 如果任务成功完成
     * @throws TimeoutException 如果超时
     * @throws InterruptedException 如果等待被中断
     */
    boolean await(long timeout, @NotNull TimeUnit unit) throws TimeoutException, InterruptedException;

    /**
     * 阻塞等待任务完成（无超时）。
     *
     * @return true 如果任务成功完成
     * @throws InterruptedException 如果等待被中断
     */
    default boolean await() throws InterruptedException {
        try {
            return await(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return false; // 不可达
        }
    }

    /**
     * 获取任务失败的原因。
     *
     * @return 异常原因，如果任务未失败则返回 null
     */
    @Nullable Throwable failureCause();

    /**
     * 获取此 handle 追踪的子任务数量。
     */
    int taskCount();

    /**
     * 获取已完成的子任务数量。
     */
    int completedCount();

    /**
     * 获取追踪的底层 CompletableFuture。
     *
     * <p>用于与现有 CompletableFuture 链集成。
     */
    @NotNull CompletableFuture<Void> toCompletableFuture();
}
