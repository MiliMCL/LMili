package fun.bm.mili.lmili.thread.scheduler.internal;

import fun.bm.mili.lmili.thread.scheduler.api.TaskHandle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@link TaskHandle} 的默认实现。
 *
 * <p>基于 {@link CompletableFuture}，提供非阻塞回调和阻塞等待能力。
 * 支持多子任务追踪：当所有子任务完成后，handle 自动标记为 completed。
 *
 * <h3>线程安全</h3>
 * <p>所有状态转换都是原子性的，多线程可以安全地调用 {@link #complete()} /
 * {@link #completeExceptionally(Throwable)} / {@link #onComplete(Consumer)}。
 */
public final class DefaultTaskHandle implements TaskHandle {

    /** 所有子任务完成的 Future */
    private final CompletableFuture<Void> future;

    /** 追踪的子任务总数 */
    private final int taskCount;

    /** 已完成的子任务数 */
    private final AtomicInteger completedCount;

    /** 失败原因（如果有） */
    private final AtomicReference<Throwable> failureCause;

    /** 当前状态 */
    private final AtomicReference<State> state;

    /** 完成回调列表 */
    private final java.util.List<Consumer<TaskHandle>> callbacks;

    /**
     * 创建单个任务的句柄。
     */
    public DefaultTaskHandle() {
        this(1);
    }

    /**
     * 创建追踪多个子任务的句柄。
     *
     * @param taskCount 子任务数量
     */
    public DefaultTaskHandle(int taskCount) {
        this.taskCount = Math.max(1, taskCount);
        this.future = new CompletableFuture<>();
        this.completedCount = new AtomicInteger(0);
        this.failureCause = new AtomicReference<>(null);
        this.state = new AtomicReference<>(State.PENDING);
        this.callbacks = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    /**
     * 从已有的 CompletableFuture 创建 TaskHandle。
     *
     * @param future 底层的 CompletableFuture
     */
    public DefaultTaskHandle(@NotNull CompletableFuture<Void> future) {
        this.taskCount = 1;
        this.future = future;
        this.completedCount = new AtomicInteger(0);
        this.failureCause = new AtomicReference<>(null);
        this.state = new AtomicReference<>(State.PENDING);
        this.callbacks = new java.util.concurrent.CopyOnWriteArrayList<>();

        // 注册回调以自动更新状态
        future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                completeExceptionally(throwable);
            } else {
                complete();
            }
        });
    }

    /**
     * 标记一个子任务完成。
     *
     * <p>当所有子任务都完成后，handle 自动标记为 COMPLETED。
     */
    public void completeChild() {
        int completed = completedCount.incrementAndGet();
        if (completed >= taskCount) {
            if (failureCause.get() != null) {
                doComplete(State.FAILED);
            } else {
                doComplete(State.COMPLETED);
            }
        }
    }

    /**
     * 标记整个任务完成（所有子任务都成功）。
     */
    public void complete() {
        completedCount.set(taskCount);
        doComplete(State.COMPLETED);
    }

    /**
     * 标记任务异常终止。
     *
     * @param throwable 失败原因
     */
    public void completeExceptionally(@NotNull Throwable throwable) {
        failureCause.compareAndSet(null, throwable);
        doComplete(State.FAILED);
    }

    /**
     * 标记任务取消。
     *
     * <p>C-18 修复：如果设置了 cancel action（如 ScheduledFuture），会先执行它。
     *
     * @return 总是返回 true（任务已被取消）
     */
    @Override
    public boolean cancel() {
        Runnable action = cancelAction.getAndSet(null);
        if (action != null) {
            try {
                action.run();
            } catch (Exception e) {
                // 忽略取消操作的异常
            }
        }
        return doComplete(State.CANCELLED);
    }

    /**
     * C-18 修复：取消动作 —— 用于真正取消底层调度。
     */
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>();

    /**
     * C-18 修复：设置取消动作。
     *
     * <p>当调用 cancel() 时，此动作会被执行，用于取消底层 ScheduledFuture 或从队列中移除任务。
     *
     * @param action 取消动作（如 () -> scheduledFuture.cancel(false)）
     */
    public void setCancelAction(@NotNull Runnable action) {
        cancelAction.set(action);
    }

    /**
     * C-18 修复：检查任务是否已取消。
     */
    public boolean isCancelled() {
        return state.get() == State.CANCELLED;
    }

    // ---- TaskHandle 接口实现 ----

    @Override
    @NotNull
    public State state() {
        return state.get();
    }

    @Override
    public void onComplete(@NotNull Consumer<TaskHandle> callback) {
        if (state.get() != State.PENDING) {
            // 已完成，立即回调
            callback.accept(this);
        } else {
            callbacks.add(callback);
            // 双重检查：可能在添加回调前状态已改变
            if (state.get() != State.PENDING) {
                callbacks.remove(callback);
                callback.accept(this);
            }
        }
    }

    @Override
    public boolean await(long timeout, @NotNull TimeUnit unit) throws TimeoutException, InterruptedException {
        try {
            future.get(timeout, unit);
            return state.get() == State.COMPLETED;
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        }
    }

    @Override
    @Nullable
    public Throwable failureCause() {
        return failureCause.get();
    }

    @Override
    public int taskCount() {
        return taskCount;
    }

    @Override
    public int completedCount() {
        return Math.min(completedCount.get(), taskCount);
    }

    @Override
    @NotNull
    public CompletableFuture<Void> toCompletableFuture() {
        return future;
    }

    // ---- 内部方法 ----

    /**
     * 完成状态转换并触发回调。
     *
     * @return true 如果状态成功从 PENDING 转换
     */
    private boolean doComplete(State newState) {
        if (state.compareAndSet(State.PENDING, newState)) {
            // 更新 Future
            if (newState == State.COMPLETED) {
                future.complete(null);
            } else if (newState == State.FAILED) {
                Throwable cause = failureCause.get();
                future.completeExceptionally(cause != null ? cause : new RuntimeException("Task failed"));
            } else {
                future.cancel(true);
            }

            // 触发回调
            for (Consumer<TaskHandle> callback : callbacks) {
                try {
                    callback.accept(this);
                } catch (Throwable t) {
                    // 回调异常不应影响其他回调
                }
            }
            return true;
        }
        return false;
    }
}
