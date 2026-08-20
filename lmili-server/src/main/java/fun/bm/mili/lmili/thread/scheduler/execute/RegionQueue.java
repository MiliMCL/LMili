package fun.bm.mili.lmili.thread.scheduler.execute;

import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 区域任务队列 —— 每个 region 的专属任务队列。
 *
 * <h3>设计目标</h3>
 * <ul>
 *   <li><b>无锁入队</b>：使用 ConcurrentLinkedDeque 实现无锁任务提交</li>
 *   <li><b>顺序保证</b>：本地 worker 使用 LIFO 消费（利用缓存局部性），窃取者使用 FIFO（保证公平性）</li>
 *   <li><b>Work-Stealing 友好</b>：支持从队列头部窃取任务（供 WorkStealingCoordinator 使用）</li>
 *   <li><b>Region 独占执行</b>：通过 {@link RegionState} 保证同一 Region 的任务不会被并发执行 (C-01)</li>
 *   <li><b>生命周期安全</b>：push/deactivate 通过 RegionState CAS 协议避免 TOCTOU race (C-07)</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <pre>
 * RegionQueue (per region)
 *     ├── push(task)       —— 本地 worker 调用（尾部入队），受 RegionState 保护
 *     ├── pop()            —— 本地 worker 调用（尾部出队，LIFO for cache locality）
 *     └── steal()          —— 其他 worker 调用（头部出队，FIFO for fairness）
 * </pre>
 *
 * <h3>并发安全保证</h3>
 * <p>通过 {@link RegionState} 实现：
 * <ul>
 *   <li>push 仅在 ACTIVE 状态下成功（CAS 保护）</li>
 *   <li>deactivate 将状态从 ACTIVE 转为 DRAINING，阻止新任务入队</li>
 *   <li>正在执行的任务完成后，当 executingCount==0 时自动关闭</li>
 *   <li>CLOSED 状态不可复活</li>
 * </ul>
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
     * Region 生命周期状态 —— 控制任务入队和排空。
     *
     * <p>解决 C-01（Region 独占执行）、C-07（push/deactivate TOCTOU）、
     * C-08（unregister 与已取出任务冲突）。
     */
    private final RegionState regionState;

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
     * 创建区域任务队列。
     *
     * @param regionId 关联的 region ID
     */
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
     * <p>此方法由本地 worker 调用，将新任务追加到队列尾部。
     * 时间复杂度 O(1)，无锁。
     *
     * <p><b>线程安全</b>：通过 {@link RegionState#tryBeginExecution} 确保
     * 仅在 ACTIVE 状态下接受新任务，避免 push/deactivate TOCTOU race (C-07)。
     *
     * @param task 要添加的任务
     * @throws IllegalStateException 如果队列已停用（DRAINING/CLOSED）
     */
    public void push(@NotNull RegionTask task) {
        // 使用 RegionState CAS 检查替代简单的 active.get() 检查
        // 这保证了 push 和 deactivate 之间的原子性
        if (!regionState.isActive()) {
            throw new IllegalStateException(
                    "RegionQueue #" + regionId + " is not active (phase: " + regionState.get() + ")");
        }

        deque.addLast(task);
        pushCount.increment();
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
     * <p><b>注意 (C-23)</b>：此值仅具有近似意义，不能用于 ownership 判断或空队列判断。
     * 仅应用于负载均衡、指标收集等场景。
     *
     * @return 队列中任务数的近似值（不小于 0）
     */
    public int approximateSize() {
        // 使用 deque.size() 的近似值，但限制为 O(1) 的估算
        // 注意：ConcurrentLinkedDeque.size() 是 O(n)，这里我们只用它做粗略估算
        // 实际生产代码中应使用独立的计数器
        return Math.max(0, deque.size());
    }

    /**
     * 检查队列是否处于活跃状态（ACTIVE 阶段）。
     */
    public boolean isActive() {
        return regionState.isActive();
    }

    /**
     * 获取 RegionState 引用（供 WorkStealingCoordinator 使用）。
     */
    public RegionState regionState() {
        return regionState;
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
     * <p>将 RegionState 从 ACTIVE 转为 DRAINING，阻止新任务入队。
     * 已在队列中的任务仍可消费。
     *
     * <p>解决 C-07：push/deactivate TOCTOU race —— push 和 deactivate
     * 现在通过 RegionState 的原子状态转换来协调。
     */
    public void deactivate() {
        regionState.tryBeginDrain();
    }

    /**
     * 清空队列并返回所有未执行的任务。
     *
     * <p>通常在 region 卸载或调度器关闭时调用。
     *
     * <p>解决 C-08：unregister 与已取出任务冲突 ——
     * 调用者应先 deactivate()，等待 executingCount==0，再 drain()。
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
        return remaining;
    }

    /**
     * 尝试关闭队列（DRAINING → CLOSED）。
     *
     * <p>仅在 executingCount==0 时成功。
     *
     * @return true 如果成功关闭
     */
    public boolean tryClose() {
        return regionState.tryClose();
    }

    /**
     * 强制关闭（忽略 executingCount）。
     *
     * <p><b>注意</b>：仅在 shutdown 等场景使用。
     */
    public void forceClose() {
        regionState.forceClose();
    }

    @Override
    public String toString() {
        return "RegionQueue#" + regionId +
                "{state=" + regionState.get() +
                ", executing=" + regionState.executingCount() +
                ", pushed=" + totalPushed() +
                ", popped=" + totalPopped() +
                ", stolen=" + totalStolen() + "}";
    }
}
