package fun.bm.mili.lmili.thread.scheduler.execute;

import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 区域任务队列 —— 每个 region 的专属任务队列。
 *
 * <h3>设计目标</h3>
 * <ul>
 *   <li><b>无锁入队</b>：使用 ConcurrentLinkedDeque 实现无锁任务提交</li>
 *   <li><b>顺序保证</b>：同一 region 的任务按提交顺序执行（FIFO）</li>
 *   <li><b>Work-Stealing 友好</b>：支持从队列头部窃取任务（供 WorkStealingCoordinator 使用）</li>
 *   <li><b>轻量级</b>：最小化内存开销，无额外线程</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <pre>
 * RegionQueue (per region)
 *     ├── push(task) —— 本地 worker 调用（尾部入队）
 *     ├── pop()      —— 本地 worker 调用（尾部出队，LIFO for cache locality）
 *     └── steal()    —— 其他 worker 调用（头部出队，FIFO for fairness）
 * </pre>
 *
 * <p>本地 worker 从尾部弹出（LIFO），利用 CPU 缓存局部性。
 * 窃取者从头部窃取（FIFO），保证最早提交的任务优先被处理。
 *
 * <h3>使用场景</h3>
 * <pre>{@code
 * RegionQueue queue = new RegionQueue(regionId);
 *
 * // 提交任务
 * queue.push(RegionTask.builder(regionId)
 *     .task(() -> tickChunk(chunk))
 *     .build());
 *
 * // 本地消费
 * RegionTask task;
 * while ((task = queue.pop()) != null) {
 *     task.execute();
 * }
 * }</pre>
 */
public final class RegionQueue {

    /**
     * 关联的 region ID。
     */
    private final long regionId;

    /**
     * 底层双端队列 —— 无锁并发。
     *
     * <p>本地 worker 操作 tail（push/pop），窃取者操作 head（steal）。
     * 这种 "生产者-消费者" 分离减少了缓存行竞争。
     */
    private final ConcurrentLinkedDeque<RegionTask> deque;

    /**
     * 队列状态标志。
     */
    private final AtomicBoolean active;

    /**
     * 入队任务计数。
     */
    private final LongAdder pushCount;

    /**
     * 出队任务计数（本地消费）。
     */
    private final LongAdder popCount;

    /**
     * 被窃取任务计数。
     */
    private final LongAdder stealCount;

    /**
     * 队列中当前任务数的近似值。
     *
     * <p>使用 AtomicInteger 而非 deque.size()（O(n)），
     * 提供 O(1) 的近似计数。
     */
    private final AtomicInteger approximateSize;

    /**
     * 创建区域任务队列。
     *
     * @param regionId 关联的 region ID
     */
    public RegionQueue(final long regionId) {
        this.regionId = regionId;
        this.deque = new ConcurrentLinkedDeque<>();
        this.active = new AtomicBoolean(true);
        this.pushCount = new LongAdder();
        this.popCount = new LongAdder();
        this.stealCount = new LongAdder();
        this.approximateSize = new AtomicInteger(0);
    }

    // ---- 任务操作 ----

    /**
     * 向队列尾部添加任务。
     *
     * <p>此方法由本地 worker 调用，将新任务追加到队列尾部。
     * 时间复杂度 O(1)，无锁。
     *
     * @param task 要添加的任务
     * @throws IllegalStateException 如果队列已停用
     */
    public void push(@NotNull RegionTask task) {
        if (!active.get()) {
            throw new IllegalStateException(
                    "RegionQueue #" + regionId + " is deactivated");
        }

        deque.addLast(task);
        pushCount.increment();
        approximateSize.incrementAndGet();
    }

    /**
     * 从队列尾部弹出任务（本地消费）。
     *
     * <p>使用 LIFO 顺序以利用 CPU 缓存局部性。
     * 时间复杂度 O(1)，无锁。
     *
     * @return 队列尾部的任务，如果队列为空则返回 null
     */
    @Nullable
    public RegionTask pop() {
        RegionTask task = deque.pollLast();
        if (task != null) {
            popCount.increment();
            approximateSize.decrementAndGet();
        }
        return task;
    }

    /**
     * 从队列头部窃取任务（供 work-stealing 使用）。
     *
     * <p>窃取者使用 FIFO 顺序，保证最早提交的任务优先被处理。
     * 时间复杂度 O(1)，无锁。
     *
     * @return 队列头部的任务，如果队列为空则返回 null
     */
    @Nullable
    public RegionTask steal() {
        RegionTask task = deque.pollFirst();
        if (task != null) {
            stealCount.increment();
            approximateSize.decrementAndGet();
        }
        return task;
    }

    /**
     * 查看队列尾部任务（不弹出）。
     *
     * @return 队列尾部的任务，如果队列为空则返回 null
     */
    @Nullable
    public RegionTask peek() {
        return deque.peekLast();
    }

    // ---- 状态查询 ----

    /**
     * 获取关联的 region ID。
     */
    public long regionId() {
        return regionId;
    }

    /**
     * 检查队列是否为空。
     *
     * <p>此检查是近似值 —— 可能在检查后立即有任务入队。
     */
    public boolean isEmpty() {
        return deque.isEmpty();
    }

    /**
     * 获取队列中任务数的近似值。
     *
     * <p>此值是近似的，不保证精确。用于负载均衡决策。
     */
    public int approximateSize() {
        return Math.max(0, approximateSize.get());
    }

    /**
     * 检查队列是否处于活跃状态。
     */
    public boolean isActive() {
        return active.get();
    }

    /**
     * 获取入队任务总数。
     */
    public long totalPushed() {
        return pushCount.sum();
    }

    /**
     * 获取本地消费任务总数。
     */
    public long totalPopped() {
        return popCount.sum();
    }

    /**
     * 获取被窃取任务总数。
     */
    public long totalStolen() {
        return stealCount.sum();
    }

    // ---- 生命周期 ----

    /**
     * 停用队列。
     *
     * <p>停用后不再接受新任务，但已提交的任务仍可消费。
     * 通常在 region 卸载时调用。
     */
    public void deactivate() {
        active.set(false);
    }

    /**
     * 清空队列并返回所有未执行的任务。
     *
     * <p>通常在 region 卸载或调度器关闭时调用。
     *
     * @return 未执行的任务列表
     */
    @NotNull
    public java.util.List<RegionTask> drain() {
        java.util.List<RegionTask> remaining = new java.util.ArrayList<>();
        RegionTask task;
        while ((task = deque.poll()) != null) {
            remaining.add(task);
            task.onCancel();
        }
        approximateSize.set(0);
        return remaining;
    }

    @Override
    public String toString() {
        return "RegionQueue#" + regionId +
                "{size=" + approximateSize() +
                ", pushed=" + totalPushed() +
                ", popped=" + totalPopped() +
                ", stolen=" + totalStolen() +
                ", active=" + active.get() + "}";
    }
}
