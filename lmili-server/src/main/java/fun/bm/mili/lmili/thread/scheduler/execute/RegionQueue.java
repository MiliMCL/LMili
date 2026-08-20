package fun.bm.mili.lmili.thread.scheduler.execute;

import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

/**
 * 区域任务队列 —— 每个 region 的专属任务队列。
 *
 * <h3>设计目标</h3>
 * <ul>
 *   <li><b>无锁入队</b>：使用 ConcurrentLinkedDeque 实现无锁任务提交</li>
 *   <li><b>顺序保证</b>：本地 worker 使用 LIFO 消费，窃取者使用 FIFO</li>
 *   <li><b>Region 独占执行</b>：通过 {@link RegionState} 保证同一 Region 不会被并发执行</li>
 * </ul>
 *
 * <h3>R2-01/R2-02 修复</h3>
 * <p>push 使用 {@link RegionState#tryAcceptTask()} 原子协议，
 * 消除 isActive 检查与 addLast 之间的 race。
 */
public final class RegionQueue {

    private final long regionId;
    private final ConcurrentLinkedDeque<RegionTask> deque;
    private final RegionState regionState;
    private final LongAdder pushCount;
    private final LongAdder popCount;
    private final LongAdder stealCount;

    public RegionQueue(final long regionId) {
        this.regionId = regionId;
        this.deque = new ConcurrentLinkedDeque<>();
        this.regionState = new RegionState();
        this.pushCount = new LongAdder();
        this.popCount = new LongAdder();
        this.stealCount = new LongAdder();
    }

    // ---- 任务操作 ----

    /**
     * 向队列尾部添加任务。
     *
     * <p>R2-02 修复：使用 RegionState.tryAcceptTask() 原子协议，
     * 消除 isActive 检查与 addLast 之间的 TOCTOU。
     *
     * @param task 要添加的任务
     * @throws IllegalStateException 如果队列已停用
     */
    public void push(@NotNull RegionTask task) {
        // 原子接受任务（递增 queuedCount）
        if (!regionState.tryAcceptTask()) {
            throw new IllegalStateException(
                    "RegionQueue #" + regionId + " is not active (phase: " + regionState.getPhase() + ")");
        }
        deque.addLast(task);
        pushCount.increment();
    }

    /**
     * 从队列尾部弹出任务（本地消费，LIFO）。
     */
    @Nullable
    public RegionTask pop() {
        RegionTask task = deque.pollLast();
        if (task != null) {
            popCount.increment();
            regionState.releaseTask(); // 递减 queuedCount
        }
        return task;
    }

    /**
     * 从队列头部窃取任务（FIFO）。
     */
    @Nullable
    public RegionTask steal() {
        RegionTask task = deque.pollFirst();
        if (task != null) {
            stealCount.increment();
            regionState.releaseTask(); // 递减 queuedCount
        }
        return task;
    }

    @Nullable
    public RegionTask peek() {
        return deque.peekLast();
    }

    // ---- 状态查询 ----

    public long regionId() { return regionId; }

    public boolean isEmpty() { return deque.isEmpty(); }

    /**
     * 获取队列中任务数的近似值（C-23：仅用于 metrics/负载均衡）。
     */
    public int approximateSize() {
        return Math.max(0, deque.size());
    }

    public boolean isActive() { return regionState.isActive(); }

    public RegionState regionState() { return regionState; }

    public long totalPushed() { return pushCount.sum(); }
    public long totalPopped() { return popCount.sum(); }
    public long totalStolen() { return stealCount.sum(); }

    // ---- 生命周期 ----

    /**
     * 停用队列（进入 DRAINING）。
     */
    public void deactivate() {
        regionState.tryBeginDrain();
    }

    /**
     * 清空队列并返回未执行的任务。
     */
    @NotNull
    public java.util.List<RegionTask> drain() {
        java.util.List<RegionTask> remaining = new java.util.ArrayList<>();
        RegionTask task;
        while ((task = deque.poll()) != null) {
            remaining.add(task);
            task.onCancel();
        }
        return remaining;
    }

    public boolean tryClose() {
        return regionState.tryClose();
    }

    public void forceClose() {
        regionState.forceClose();
    }

    @Override
    public String toString() {
        RegionState.Snapshot s = regionState.getSnapshot();
        return "RegionQueue#" + regionId + "{" + s.phase() + ", " + s.execState() +
                ", owner=" + s.owner() + ", queued=" + s.queued() + "}";
    }
}
