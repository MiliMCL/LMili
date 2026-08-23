package fun.bm.mili.lmili.thread.scheduler.internal;

import fun.bm.mili.lmili.thread.scheduler.api.TaskHandle;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link fun.bm.mili.lmili.thread.scheduler.api.BatchHandle} 的默认实现。
 *
 * <p>追踪一批相关任务的完成状态，支持：
 * <ul>
 *   <li>查询单个子任务状态</li>
 *   <li>等待部分任务完成</li>
 *   <li>获取失败任务列表</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有方法都是线程安全的。子任务可以在任意线程上完成。
 */
public final class DefaultBatchHandle implements fun.bm.mili.lmili.thread.scheduler.api.BatchHandle {

    /** 所有子任务句柄 */
    private final List<TaskHandle> children;

    /** 父级句柄 —— 追踪整体完成状态 */
    private final DefaultTaskHandle parentHandle;

    /** 子任务数量 */
    private final int batchSize;

    /** 成功完成的任务数 */
    private final AtomicInteger successCount;

    /**
     * 创建批量任务句柄。
     *
     * <p>RISK-04 修复：每个 child 完成时把 child.state 传给 parent。
     * 父 handle 的最终态由子句 finalState 分布决定（COMPLETED/FAILED/CANCELLED）。</p>
     *
     * @param children 子任务句柄列表
     */
    public DefaultBatchHandle(@NotNull List<TaskHandle> children) {
        this.children = Collections.unmodifiableList(new ArrayList<>(children));
        this.batchSize = children.size();
        this.parentHandle = new DefaultTaskHandle(batchSize);
        this.successCount = new AtomicInteger(0);

        // 为每个子任务注册完成回调
        for (int i = 0; i < batchSize; i++) {
            TaskHandle child = children.get(i);
            child.onComplete(handle -> {
                // RISK-04：把 child.state 传给 parent，由 parent 决定最终态
                TaskHandle.State st = handle.state();
                if (st == TaskHandle.State.COMPLETED) {
                    this.successCount.incrementAndGet();
                }
                parentHandle.completeChild(st);
            });
        }
    }

    // ---- BatchHandle 接口实现 ----

    @Override
    public int batchSize() {
        return batchSize;
    }

    @Override
    @NotNull
    public TaskHandle getChild(int index) {
        if (index < 0 || index >= batchSize) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + batchSize);
        }
        return children.get(index);
    }

    @Override
    @NotNull
    public List<TaskHandle> children() {
        return children;
    }

    @Override
    @NotNull
    public List<TaskHandle> failedChildren() {
        List<TaskHandle> failed = new ArrayList<>();
        for (TaskHandle child : children) {
            if (child.state() == TaskHandle.State.FAILED) {
                failed.add(child);
            }
        }
        return failed;
    }

    @Override
    public boolean awaitUntil(int count, long timeout, @NotNull TimeUnit unit) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (successCount.get() < count) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Only " + successCount.get() + " of " + count +
                        " tasks completed within timeout");
            }
            // 短暂等待（避免忙等）
            Thread.sleep(Math.min(remaining / 1_000_000L, 10));
        }
        return true;
    }

    @Override
    public int successCount() {
        return successCount.get();
    }

    // ---- TaskHandle 接口委托 ----

    @Override
    @NotNull
    public TaskHandle.State state() {
        return parentHandle.state();
    }

    @Override
    public void onComplete(@NotNull java.util.function.Consumer<TaskHandle> callback) {
        parentHandle.onComplete(callback);
    }

    @Override
    public boolean await(long timeout, @NotNull TimeUnit unit) throws TimeoutException, InterruptedException {
        return parentHandle.await(timeout, unit);
    }

    @Override
    public boolean await() throws InterruptedException {
        return parentHandle.await();
    }

    @Override
    public Throwable failureCause() {
        return parentHandle.failureCause();
    }

    @Override
    public int taskCount() {
        return batchSize;
    }

    @Override
    public int completedCount() {
        return parentHandle.completedCount();
    }

    @Override
    public boolean cancel() {
        // 取消所有子任务，然后取消父 handle
        boolean allCancelled = parentHandle.cancel();
        for (TaskHandle child : children) {
            child.cancel();
        }
        return allCancelled;
    }

    @Override
    @NotNull
    public java.util.concurrent.CompletableFuture<Void> toCompletableFuture() {
        return parentHandle.toCompletableFuture();
    }
}
