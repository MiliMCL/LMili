package fun.bm.mili.lmili.thread.scheduler.execute;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 调度器生命周期状态机 —— 统一管理调度器的启动、优雅关闭和强制关闭。
 *
 * <h3>状态转换</h3>
 * <pre>
 * RUNNING ──shutdown()──▶ QUIESCING ──(all workers stopped)──▶ CLOSED
 *    │                        │
 *    └──halt()────────────────┴──▶ CLOSED (强制)
 * </pre>
 *
 * <ul>
 *   <li><b>RUNNING</b>：正常运行，接受新任务</li>
 *   <li><b>QUIESCING</b>：优雅关闭中，不再接受新任务，等待已有任务完成</li>
 *   <li><b>CLOSED</b>：已关闭，不再接受任何操作</li>
 * </ul>
 *
 * <h3>修复的并发问题</h3>
 * <ul>
 *   <li><b>C-11</b>：shutdown 缺乏统一 QUIESCING 状态机 —— 提供 RUNNING → QUIESCING → CLOSED 的
 *       明确转换，阻止新任务在 QUIESCING/CLOSED 状态下被提交</li>
 *   <li><b>C-19</b>：shutdown 与延迟调度器竞争 —— 在 QUIESCING 阶段安全地停止延迟调度器，
 *       防止已取消的任务仍被调度唤醒</li>
 * </ul>
 *
 * <p>所有状态转换通过 CAS 保证原子性。一旦进入 CLOSED 状态，不可逆转。
 */
public final class SchedulerLifecycle {

    /**
     * 调度器生命周期阶段。
     */
    public enum Phase {
        /** 正常运行 */
        RUNNING,
        /** 优雅关闭中，等待任务完成 */
        QUIESCING,
        /** 已关闭 */
        CLOSED
    }

    private final AtomicReference<Phase> phase;

    /**
     * 创建处于 RUNNING 状态的 SchedulerLifecycle。
     */
    public SchedulerLifecycle() {
        this.phase = new AtomicReference<>(Phase.RUNNING);
    }

    /**
     * 获取当前阶段。
     */
    public Phase get() {
        return phase.get();
    }

    /**
     * 是否处于 RUNNING 状态。
     */
    public boolean isRunning() {
        return phase.get() == Phase.RUNNING;
    }

    /**
     * 是否已关闭（QUIESCING 或 CLOSED）。
     */
    public boolean isShuttingDown() {
        Phase current = phase.get();
        return current == Phase.QUIESCING || current == Phase.CLOSED;
    }

    /**
     * 是否已完全关闭。
     */
    public boolean isClosed() {
        return phase.get() == Phase.CLOSED;
    }

    /**
     * 尝试从 RUNNING 进入 QUIESCING（优雅关闭）。
     *
     * <p>这是幂等的：如果已经在 QUIESCING，返回 true。
     *
     * @return true 如果成功进入 QUIESCING（或已在 QUIESCING）；false 如果已经 CLOSED
     */
    public boolean beginShutdown() {
        while (true) {
            Phase current = phase.get();
            if (current == Phase.CLOSED) {
                return false; // 已经强制关闭了
            }
            if (current == Phase.QUIESCING) {
                return true; // 幂等
            }
            if (phase.compareAndSet(Phase.RUNNING, Phase.QUIESCING)) {
                return true;
            }
        }
    }

    /**
     * 确认关闭完成（QUIESCING → CLOSED）。
     *
     * <p>应在所有 worker 线程停止后调用。
     *
     * @return true 如果成功从 QUIESCING 进入 CLOSED；false 如果已经在 CLOSED
     */
    public boolean completeShutdown() {
        return phase.compareAndSet(Phase.QUIESCING, Phase.CLOSED);
    }

    /**
     * 强制关闭（RUNNING/QUIESCING → CLOSED）。
     *
     * <p>用于 halt() 等场景，不再等待任务完成。
     *
     * @return true 如果成功进入 CLOSED
     */
    public boolean halt() {
        Phase current;
        while (true) {
            current = phase.get();
            if (current == Phase.CLOSED) {
                return true; // 幂等
            }
            if (phase.compareAndSet(current, Phase.CLOSED)) {
                return true;
            }
        }
    }

    @Override
    public String toString() {
        return "SchedulerLifecycle{" + phase.get() + "}";
    }
}
