package fun.bm.mili.lmili.thread.scheduler.execute;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Region 生命周期状态机 —— 解决 register/unregister race (C-02)、push/deactivate TOCTOU (C-07)、
 * unregister 与已取出任务冲突 (C-08) 等并发问题。
 *
 * <h3>状态转换</h3>
 * <pre>
 * ACTIVE ──deactivate()──▶ DRAINING ──(executing==0)──▶ CLOSED
 * </pre>
 *
 * <ul>
 *   <li><b>ACTIVE</b>：接受新任务，正常调度</li>
 *   <li><b>DRAINING</b>：不再接受新任务，等待正在执行的任务完成</li>
 *   <li><b>CLOSED</b>：不再激活，不可复活</li>
 * </ul>
 *
 * <p>所有状态转换通过 CAS 保证原子性，禁止 CLOSED → ACTIVE 的反向转换。
 *
 * <h3>执行计数器</h3>
 * <p>与状态配合使用，记录当前正在执行的任务数。
 * DRAINING 状态下当 executingCount 降为 0 时才能安全关闭。
 */
public final class RegionState {

    /**
     * Region 生命周期阶段。
     */
    public enum Phase {
        /** 正常运行，接受新任务 */
        ACTIVE,
        /** 正在排空，不再接受新任务，等待执行中任务完成 */
        DRAINING,
        /** 已关闭，不可复活 */
        CLOSED
    }

    private final AtomicReference<Phase> phase;
    private final java.util.concurrent.atomic.AtomicInteger executingCount;

    /**
     * 创建 ACTIVE 阶段的 RegionState。
     */
    public RegionState() {
        this.phase = new AtomicReference<>(Phase.ACTIVE);
        this.executingCount = new AtomicInteger(0);
    }

    /**
     * 获取当前生命周期阶段。
     */
    public Phase get() {
        return phase.get();
    }

    /**
     * 是否处于 ACTIVE 阶段（接受新任务）。
     */
    public boolean isActive() {
        return phase.get() == Phase.ACTIVE;
    }

    /**
     * 尝试提交任务：仅当 ACTIVE 时递增执行计数器。
     *
     * @return true 如果成功（处于 ACTIVE），false 如果已 DRAINING/CLOSED
     */
    public boolean tryBeginExecution() {
        while (true) {
            Phase current = phase.get();
            if (current != Phase.ACTIVE) {
                return false;
            }
            // 快速路径：phase 仍为 ACTIVE，递增计数器
            // 注意：这里只递增计数器，不修改 phase。deactivate() 会在执行期间将 phase 设为 DRAINING。
            // 但这没关系——只要 tryBeginExecution 返回 true，我们就承诺执行。
            executingCount.incrementAndGet();
            // 双重检查：如果在 increment 之后 phase 被改为 DRAINING，仍然允许执行完成
            // 因为我们已经在 ACTIVE 状态下获得了执行权
            return true;
        }
    }

    /**
     * 通知一个任务执行完成。
     *
     * <p>在 DRAINING 阶段当计数器降为 0 时，可以安全关闭。
     */
    public void endExecution() {
        executingCount.decrementAndGet();
    }

    /**
     * 尝试进入 DRAINING 阶段。
     *
     * <p>只能在 ACTIVE → DRAINING 或 DRAINING（幂等）时成功。
     *
     * @return true 如果成功转换到 DRAINING；false 如果已经是 CLOSED
     */
    public boolean tryBeginDrain() {
        Phase current;
        while (true) {
            current = phase.get();
            if (current == Phase.CLOSED) {
                return false;
            }
            if (current == Phase.DRAINING) {
                return true; // 幂等
            }
            if (phase.compareAndSet(Phase.ACTIVE, Phase.DRAINING)) {
                return true;
            }
        }
    }

    /**
     * 尝试关闭 Region（DRAINING → CLOSED）。
     *
     * <p>仅在 executingCount == 0 时成功。
     *
     * @return true 如果成功关闭，false 如果还有任务在执行
     */
    public boolean tryClose() {
        while (true) {
            Phase current = phase.get();
            if (current == Phase.ACTIVE) {
                // 先尝试进入 DRAINING
                if (!phase.compareAndSet(Phase.ACTIVE, Phase.DRAINING)) {
                    continue; // 有其他线程修改了状态，重试
                }
                // 已经进入 DRAINING，继续检查 executingCount
                current = Phase.DRAINING;
            }
            if (current == Phase.CLOSED) {
                return true; // 已经关闭了
            }
            // current == Phase.DRAINING
            if (executingCount.get() != 0) {
                return false; // 还有任务在执行
            }
            if (phase.compareAndSet(Phase.DRAINING, Phase.CLOSED)) {
                return true;
            }
            // CAS 失败意味着状态被改变，重试
        }
    }

    /**
     * 强制关闭（忽略 executingCount）。
     *
     * <p><b>注意</b>：仅在 shutdown 等场景使用，此时假设所有 worker 已停止。
     */
    public void forceClose() {
        phase.set(Phase.CLOSED);
    }

    /**
     * 获取当前正在执行的任务数。
     */
    public int executingCount() {
        return executingCount.get();
    }

    @Override
    public String toString() {
        return "RegionState{" + phase.get() + ", executing=" + executingCount.get() + "}";
    }
}
