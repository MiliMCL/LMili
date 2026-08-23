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
 *
 * <h3>RISK-04 / RISK-09 修复</h3>
 * <ul>
 *   <li>state 状态机严格 PENDING → COMPLETED/FAILED/CANCELLED 的 CAS 互斥</li>
 *   <li>{@link #completeChild()} 增加原子合并（successCount/failedCount/cancelledCount），
 *       最终态由三者决定而非简单按 failureCause 推断</li>
 * </ul>
 */
public final class DefaultTaskHandle implements TaskHandle {

    /** 所有子任务完成的 Future */
    private final CompletableFuture<Void> future;

    /** 追踪的子任务总数 */
    private final int taskCount;

    /** 已完成的子任务数（success + failed + cancelled） */
    private final AtomicInteger completedCount;

    /** 已成功的子任务数（RISK-04 修复：用于区分 SUCCESS vs PARTIAL_FAILURE） */
    private final AtomicInteger successCount;

    /** 已失败的子任务数（RISK-04 修复） */
    private final AtomicInteger failedCount;

    /** 已取消的子任务数（RISK-04 修复） */
    private final AtomicInteger cancelledCount;

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
        this.successCount = new AtomicInteger(0);
        this.failedCount = new AtomicInteger(0);
        this.cancelledCount = new AtomicInteger(0);
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
        this.successCount = new AtomicInteger(0);
        this.failedCount = new AtomicInteger(0);
        this.cancelledCount = new AtomicInteger(0);
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
     * RISK-04 修复：标记一个子任务完成（按 finalState 分类计数）。
     *
     * <p>父 handle 的最终态由"所有子任务 finalState 分布"决定：
     * <ul>
     *   <li>全部 success → COMPLETED</li>
     *   <li>有 cancelled 但无 failed → CANCELLED（RISK-04：batch 全部被取消）</li>
     *   <li>有 failed（无论是否 cancelled）→ FAILED</li>
     *   <li>混合 success + failed/cancelled → FAILED（PARTIAL_FAILURE 也归类为 FAILED，
     *       但调用者可通过 {@link #failureCause()} + 子句柄列表区分）</li>
     * </ul>
     *
     * @param childState 子任务结束状态（COMPLETED/FAILED/CANCELLED）
     */
    public void completeChild(@NotNull State childState) {
        int completed = completedCount.incrementAndGet();
        switch (childState) {
            case COMPLETED -> successCount.incrementAndGet();
            case FAILED -> failedCount.incrementAndGet();
            case CANCELLED -> cancelledCount.incrementAndGet();
            default -> {}
        }
        if (completed >= taskCount) {
            determineAndSetFinalState();
        }
    }

    /**
     * 兼容旧 API：标记一个子任务完成，假定为 success。
     *
     * @deprecated 使用 {@link #completeChild(State)} 传入真实状态
     */
    @Deprecated
    public void completeChild() {
        completeChild(State.COMPLETED);
    }

    /**
     * 标记整个任务完成（所有子任务都成功）。
     */
    public void complete() {
        completedCount.set(taskCount);
        // 若 counter 还没全部计入，把剩余都视为 success
        int successNow = successCount.get();
        int alreadyCounted = successNow + failedCount.get() + cancelledCount.get();
        if (alreadyCounted < taskCount) {
            successCount.set(taskCount - failedCount.get() - cancelledCount.get());
        }
        determineAndSetFinalState();
    }

    /**
     * 标记任务异常终止。
     *
     * @param throwable 失败原因
     */
    public void completeExceptionally(@NotNull Throwable throwable) {
        failureCause.compareAndSet(null, throwable);
        failedCount.incrementAndGet();
        // 强制推进 counter 到 taskCount，触发最终态判定
        int prev = completedCount.getAndSet(taskCount);
        // 把未计的"剩余"算入 failed（保持总和正确）
        int alreadyCounted = successCount.get() + failedCount.get() + cancelledCount.get();
        if (alreadyCounted < taskCount) {
            failedCount.addAndGet(taskCount - alreadyCounted);
        }
        determineAndSetFinalState();
    }

    /**
     * RISK-04 修复：根据子任务 finalState 分布判定父 handle 最终态。
     *
     * <p>只允许一次有效 CAS（PENDING → 终态）。</p>
     */
    private void determineAndSetFinalState() {
        State current = state.get();
        if (current != State.PENDING) {
            return;
        }
        int success = successCount.get();
        int failed = failedCount.get();
        int cancelled = cancelledCount.get();
        State finalState;
        if (failed > 0) {
            // 至少一个失败 → FAILED（PARTIAL_FAILURE 也归入此态）
            finalState = State.FAILED;
        } else if (cancelled == taskCount) {
            // 全部取消 → CANCELLED
            finalState = State.CANCELLED;
        } else if (cancelled > 0) {
            // 部分取消 + 其余成功 → 当作 CANCELLED（无法恢复部分结果）
            finalState = State.CANCELLED;
        } else {
            // 全部成功
            finalState = State.COMPLETED;
        }
        doComplete(finalState);
    }

    /**
     * RISK-04 修复：获取 success/failed/cancelled 计数（供 batch 诊断）。
     */
    public int successChildCount() { return successCount.get(); }
    public int failedChildCount() { return failedCount.get(); }
    public int cancelledChildCount() { return cancelledCount.get(); }

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
