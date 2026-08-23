package fun.bm.mili.lmili.thread.scheduler.execute;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务调度门闩 —— 防止同一任务被重复入队或并发执行 (C-03/C-21)。
 *
 * <h3>状态转换</h3>
 * <pre>
 * IDLE ──▶ QUEUED ──▶ RUNNING ──▶ IDLE (或 CANCELLED)
 *                    ──▶ CANCELLED
 * QUEUED ──▶ CANCELLED
 * </pre>
 *
 * <ul>
 *   <li><b>IDLE</b>：任务未在队列中，可以安全入队</li>
 *   <li><b>QUEUED</b>：任务已在某 worker 队列中等待执行</li>
 *   <li><b>RUNNING</b>：任务正在执行中</li>
 *   <li><b>CANCELLED</b>：任务已取消</li>
 * </ul>
 *
 * <p>关键不变量：一个任务同一时刻最多只有一个 QUEUED 或 RUNNING 状态，
 * 防止重复入队导致的多次执行。
 */
public final class TaskScheduleState {

    /**
     * 任务调度阶段。
     */
    public enum Phase {
        /** 空闲：任务未在队列中 */
        IDLE,
        /** 已入队：任务在 worker 队列中等待 */
        QUEUED,
        /** 运行中：任务正在执行 */
        RUNNING,
        /** 已取消 */
        CANCELLED
    }

    private static final Phase PHASE_IDLE = Phase.IDLE;
    private static final Phase PHASE_QUEUED = Phase.QUEUED;
    private static final Phase PHASE_RUNNING = Phase.RUNNING;
    private static final Phase PHASE_CANCELLED = Phase.CANCELLED;

    private final AtomicReference<Phase> phase;

    /**
     * 创建初始为 IDLE 状态的调度门闩。
     */
    public TaskScheduleState() {
        this.phase = new AtomicReference<>(Phase.IDLE);
    }

    /**
     * 获取当前阶段。
     */
    public Phase get() {
        return phase.get();
    }

    /**
     * 尝试将任务从 IDLE 标记为 QUEUED（准备入队）。
     *
     * <p>仅允许 IDLE → QUEUED 转换。
     *
     * @return true 如果成功（调用者应继续入队）；false 如果已经在 QUEUED/RUNNING/CANCELLED
     */
    public boolean tryMarkQueued() {
        return phase.compareAndSet(PHASE_IDLE, PHASE_QUEUED);
    }

    /**
     * 尝试将任务从 QUEUED 标记为 RUNNING（开始执行）。
     *
     * <p>仅允许 QUEUED → RUNNING 转换。
     *
     * @return true 如果成功；false 如果状态不是 QUEUED（可能已被取消或不在队列中）
     */
    public boolean tryMarkRunning() {
        return phase.compareAndSet(PHASE_QUEUED, PHASE_RUNNING);
    }

    /**
     * 标记任务执行完成，回到 IDLE 状态。
     *
     * <p>允许 RUNNING → IDLE 转换。幂等：如果已经不是 RUNNING 则无效。
     *
     * @return true 如果成功从 RUNNING 转换到 IDLE
     */
    public boolean tryMarkIdle() {
        return phase.compareAndSet(PHASE_RUNNING, PHASE_IDLE);
    }

    /**
     * 尝试取消任务。
     *
     * <p>可以从 IDLE 或 QUEUED 转换到 CANCELLED。
     * 如果任务正在 RUNNING，则不能取消（需要等待执行完成）。
     *
     * @return true 如果成功取消；false 如果正在 RUNNING 或已经 CANCELLED
     */
    public boolean tryCancel() {
        while (true) {
            Phase current = phase.get();
            if (current == PHASE_RUNNING || current == PHASE_CANCELLED) {
                return false;
            }
            if (phase.compareAndSet(current, PHASE_CANCELLED)) {
                return true;
            }
        }
    }

    /**
     * 强制取消（从任意状态转为 CANCELLED）。
     *
     * <p><b>注意</b>：仅在 shutdown 等场景使用。
     */
    public void forceCancel() {
        phase.set(PHASE_CANCELLED);
    }

    /**
     * 把任务从 QUEUED 状态还原为 IDLE。
     *
     * <p>用于：worker 拒收任务后，需要让 caller 能重新调度同一任务（例如
     * Ownership verification 失败时 token 被取消但 task 需要被重新派发）。
     *
     * <p>只在 QUEUED 状态下生效。RUNNING/CANCELLED/IDLE 状态下调用返回 false，
     * 避免与正在执行的 task 冲突。</p>
     *
     * @return true 如果成功 QUEUED→IDLE；false 如果状态不是 QUEUED
     */
    public boolean resetQueuedToIdle() {
        return phase.compareAndSet(PHASE_QUEUED, PHASE_IDLE);
    }

    /**
     * 检查任务是否可以入队（处于 IDLE 状态）。
     */
    public boolean isIdle() {
        return phase.get() == PHASE_IDLE;
    }

    /**
     * 检查任务是否正在执行。
     */
    public boolean isRunning() {
        return phase.get() == PHASE_RUNNING;
    }

    /**
     * 检查任务是否已取消。
     */
    public boolean isCancelled() {
        return phase.get() == PHASE_CANCELLED;
    }

    @Override
    public String toString() {
        return "TaskScheduleState{" + phase.get() + "}";
    }
}
