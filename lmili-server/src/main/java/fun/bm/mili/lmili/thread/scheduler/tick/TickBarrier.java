package fun.bm.mili.lmili.thread.scheduler.tick;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tick 屏障 —— 非阻塞式 tick 完成追踪。
 *
 * <p>核心设计原则：Region Tick 线程永不等待子任务。
 * 使用 {@link CompletableFuture} 替代 {@link java.util.concurrent.CountDownLatch}，
 * 通过回调驱动完成语义。
 *
 * <h3>完成语义</h3>
 * <pre>
 * sealed == true AND pending == 0 → Tick 完成
 * </pre>
 *
 * <p>不能只判断 {@code pending == 0}，因为任务可能还没有全部注册。
 * 正确流程：
 * <ol>
 *   <li>注册所有 region（{@code register()}）</li>
 *   <li>密封屏障（{@code seal()}）</li>
 *   <li>等待 {@code pending == 0}</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * <p>本类是线程安全的。多个线程可以同时调用 {@link #register()}、{@link #complete()}、
 * {@link #seal()}。
 */
public final class TickBarrier {

    private final AtomicInteger pending = new AtomicInteger(0);

    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private final AtomicBoolean sealed = new AtomicBoolean(false);

    /**
     * 注册一个待完成的任务。
     *
     * <p>必须在 {@link #seal()} 之前调用。
     *
     * @throws IllegalStateException 如果 barrier 已经密封
     */
    public void register() {
        if (sealed.get()) {
            throw new IllegalStateException("Tick already sealed");
        }
        pending.incrementAndGet();
    }

    /**
     * 标记一个任务完成。
     *
     * <p>当所有已注册任务都完成且 barrier 已密封时，completion 完成。
     */
    public void complete() {
        int remaining = pending.decrementAndGet();
        if (remaining == 0 && sealed.get()) {
            completion.complete(null);
        }
    }

    /**
     * 密封屏障 —— 表示不再有新任务注册。
     *
     * <p>如果调用时所有任务已完成，则立即完成 completion。
     */
    public void seal() {
        sealed.set(true);
        if (pending.get() == 0) {
            completion.complete(null);
        }
    }

    /**
     * 标记 tick 失败。
     *
     * @param error 失败原因
     */
    public void fail(Throwable error) {
        completion.completeExceptionally(error);
    }

    /**
     * 获取当前待完成的任务数。
     */
    public int pendingCount() {
        return pending.get();
    }

    /**
     * 检查 barrier 是否已密封。
     */
    public boolean isSealed() {
        return sealed.get();
    }

    /**
     * 检查 tick 是否已完成。
     */
    public boolean isComplete() {
        return completion.isDone();
    }

    /**
     * 获取完成阶段 —— 非阻塞观察 tick 完成状态。
     *
     * @return 完成阶段，在 tick 完成时完成
     */
    public CompletionStage<Void> completion() {
        return completion;
    }
}
