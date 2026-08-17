package fun.bm.mili.lmili.thread.scheduler.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 可重用的执行上下文 —— 每个 tick 的执行环境，通过对象池复用。
 *
 * <p>核心设计：
 * <ul>
 *   <li><b>池化复用</b>：通过 ThreadLocal 池避免每 tick 创建新对象</li>
 *   <li><b>临时存储</b>：提供 scratchList 和 scratchMap 供中间计算使用</li>
 *   <li><b>生命周期明确</b>：acquire() 获取，release() 归还，防止泄漏</li>
 * </ul>
 *
 * <h3>使用模式</h3>
 * <pre>{@code
 * ExecutionContext ctx = ExecutionContext.acquire();
 * try {
 *     ctx.scratchList().add(someTask);
 *     ctx.scratchMap().put("key", value);
 *     // ... 使用上下文
 * } finally {
 *     ctx.release(); // 必须归还
 * }
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>每个 ExecutionContext 实例只被一个线程使用（通过 ThreadLocal 池分配），
 * 因此内部状态无需同步。禁止跨线程共享实例。
 */
public final class ExecutionContext {

    /**
     * 对象池 —— ThreadLocal 实现，每个线程独立池。
     */
    private static final ThreadLocal<ObjectPool<ExecutionContext>> POOL =
            ThreadLocal.withInitial(() -> new ObjectPool<>(
                    ExecutionContext::new,
                    ExecutionContext::reset,
                    8
            ));

    // ---- 可复用的临时存储 ----
    private final List<CompletableFuture<Void>> scratchFutureList;
    private final Map<String, Object> scratchMap;
    private final List<Runnable> scratchRunnableList;

    // ---- 上下文状态 ----
    private long regionId = -1;
    private long startNanos = 0;
    private long deadlineNanos = 0; // 0 = 无限制

    // ---- 引用追踪（用于 PhantomReference 泄漏检测）----
    private volatile boolean released = true; // 默认为 true，acquire 时设为 false

    private ExecutionContext() {
        this.scratchFutureList = new ArrayList<>(16);
        this.scratchMap = new HashMap<>(8);
        this.scratchRunnableList = new ArrayList<>(16);
    }

    /**
     * 从池中获取一个执行上下文。
     *
     * <p>如果池中有可复用对象，返回重置后的实例；否则创建新实例。
     * 此方法约 20-50ns（含可能的创建开销）。
     */
    @NotNull
    public static ExecutionContext acquire() {
        ExecutionContext ctx = POOL.get().acquire();
        ctx.released = false;
        ctx.startNanos = System.nanoTime();
        return ctx;
    }

    /**
     * 归还执行上下文到池中。
     *
     * <p>归还后不能再使用此实例。池中最多保留 8 个实例，
     * 多余的对象会被 GC 回收。
     */
    public void release() {
        if (released) return; // 防止重复归还
        released = true;
        POOL.get().release(this);
    }

    /**
     * 重置上下文状态（归还到池时调用）。
     */
    private void reset() {
        this.regionId = -1;
        this.startNanos = 0;
        this.deadlineNanos = 0;
        this.scratchFutureList.clear();
        this.scratchMap.clear();
        this.scratchRunnableList.clear();
    }

    // ---- 上下文配置 ----

    /**
     * 设置关联的 region ID。
     */
    @NotNull
    public ExecutionContext forRegion(final long regionId) {
        this.regionId = regionId;
        return this;
    }

    /**
     * 设置执行截止时间。
     */
    @NotNull
    public ExecutionContext withDeadline(final long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
        return this;
    }

    /**
     * 设置执行超时（相对于当前时间）。
     */
    @NotNull
    public ExecutionContext withTimeout(final long timeoutMillis) {
        this.deadlineNanos = timeoutMillis > 0
                ? this.startNanos + timeoutMillis * 1_000_000L
                : 0;
        return this;
    }

    // ---- 访问器 ----

    public long regionId() { return regionId; }
    public long startNanos() { return startNanos; }

    /**
     * 获取已用时间（纳秒）。
     */
    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    /**
     * 检查是否已超时。
     */
    public boolean isTimedOut() {
        return deadlineNanos > 0 && System.nanoTime() > deadlineNanos;
    }

    /**
     * 获取未来到截止时间的剩余毫秒数。
     */
    public long remainingMillis() {
        if (deadlineNanos <= 0) return Long.MAX_VALUE;
        return (deadlineNanos - System.nanoTime()) / 1_000_000L;
    }

    /**
     * 获取可复用的 Future 列表。
     *
     * <p>用于收集异步任务，之后可以统一等待完成。
     */
    @NotNull
    public List<CompletableFuture<Void>> scratchFutureList() {
        return scratchFutureList;
    }

    /**
     * 获取可复用的 Map。
     *
     * <p>用于在 tick 执行过程中存放临时数据。
     */
    @NotNull
    public Map<String, Object> scratchMap() {
        return scratchMap;
    }

    /**
     * 获取可复用的 Runnable 列表。
     *
     * <p>用于收集需要执行的逻辑，批量提交。
     */
    @NotNull
    public List<Runnable> scratchRunnableList() {
        return scratchRunnableList;
    }

    /**
     * 获取 TaskHandle 的临时存储位置。
     *
     * <p>用于 DAG 执行中追踪子任务。
     */
    public TaskHandleImpl acquireTaskHandle(int nodeCount) {
        return TaskHandleImpl.create(nodeCount);
    }

    /**
     * 检查是否已被释放。
     */
    public boolean isReleased() {
        return released;
    }

    /**
     * 简单的 TaskHandle 实现，用于内部追踪。
     */
    static final class TaskHandleImpl {
        private final CompletableFuture<Void> future;
        private final int totalTasks;
        private final java.util.concurrent.atomic.AtomicInteger completedTasks;
        private volatile boolean done;
        private volatile Throwable failureCause;

        static TaskHandleImpl create(int nodeCount) {
            return new TaskHandleImpl(nodeCount);
        }

        TaskHandleImpl(int totalTasks) {
            this.totalTasks = totalTasks;
            this.future = new CompletableFuture<>();
            this.completedTasks = new java.util.concurrent.atomic.AtomicInteger(0);
        }

        void signalCompletion() {
            if (completedTasks.incrementAndGet() >= totalTasks) {
                done = true;
                if (failureCause != null) {
                    future.completeExceptionally(failureCause);
                } else {
                    future.complete(null);
                }
            }
        }

        void signalFailure(Throwable cause) {
            failureCause = cause;
            if (completedTasks.incrementAndGet() >= totalTasks) {
                done = true;
                future.completeExceptionally(cause);
            }
        }

        CompletableFuture<Void> future() { return future; }
        boolean isDone() { return done; }
        int completedCount() { return completedTasks.get(); }
    }
}
