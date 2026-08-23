package fun.bm.mili.lmili.thread.scheduler.execute;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

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
 *   <li><b>C-11 / RISK-02</b>：shutdown 缺乏统一 QUIESCING 状态机 —— 提供 RUNNING → QUIESCING → CLOSED 的
 *       明确转换，阻止新任务在 QUIESCING/CLOSED 状态下被提交</li>
 *   <li><b>C-19 / RISK-05</b>：shutdown 与延迟调度器竞争 —— 在 QUIESCING 阶段安全地停止延迟调度器，
 *       防止已取消的任务仍被调度唤醒</li>
 *   <li><b>RISK-02</b>：submit 与 shutdown 的 TOCTOU 竞态 —— 通过
 *       {@link #submitGate} 保证"检查状态 + 实际提交"原子完成。submit 必须先 acquire
 *       submitGate（exclusive 模式下仅 RUNNING 可通过），release 后才能进入实际调度路径；
 *       shutdown 调用 {@link #beginShutdown()} 时也会 acquire 同一把锁，从而阻止
 *       任何 submit 在 beginShutdown 与 worker stop 之间进入。</li>
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
     * RISK-02 修复：submit gate —— 让"submit"与"shutdown 状态转换"串行化。
     *
     * <p>设计：
     * <ul>
     *   <li>submit 路径在 {@link #acquireSubmitSlot()} 中 acquire；通过状态检查后
     *       持有 gate；只有调用 {@link #releaseSubmitSlot()} 后才让其他 submit
     *       或 shutdown 进入。</li>
     *   <li>{@link #beginShutdown()} acquire gate 后立即进入 QUIESCING 状态，
     *       此后任何 acquireSubmitSlot 都会失败（因 phase != RUNNING）。</li>
     *   <li>{@link #halt()} acquire gate 后直接 CLOSED，阻止任何 submit。</li>
     * </ul>
     *
     * <p>使用公平锁避免 submit 线程饥饿。</p>
     */
    private final ReentrantLock submitGate = new ReentrantLock(/* fair= */ true);

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
     * RISK-02 修复：原子 admission —— 在 submit 路径入口调用。
     *
     * <p>acquire 提交门锁后立即检查 phase 是否仍为 RUNNING：
     * <ul>
     *   <li>是 RUNNING：返回 true，调用者进入实际调度路径
     *       （之后必须调用 {@link #releaseSubmitSlot()} 释放门锁）</li>
     *   <li>非 RUNNING：返回 false，调用者必须取消任务，不能继续提交</li>
     * </ul>
     *
     * <p>这把锁同时被 {@link #beginShutdown()} 与 {@link #halt()} 持有 —— 因此
     * shutdown 永远不会与 submit 并发穿过 RUNNING 状态。</p>
     */
    public boolean acquireSubmitSlot() {
        submitGate.lock();
        try {
            return phase.get() == Phase.RUNNING;
        } catch (Throwable t) {
            submitGate.unlock();
            throw t;
        }
    }

    /**
     * RISK-02 修复：释放 submit 门锁。必须与 {@link #acquireSubmitSlot()} 配对调用。
     */
    public void releaseSubmitSlot() {
        submitGate.unlock();
    }

    /**
     * 尝试从 RUNNING 进入 QUIESCING（优雅关闭）。
     *
     * <p>RISK-02 修复：调用前 acquire submit gate —— 任何正在 submit 的线程在
     * 释放 gate 前，都能看到 phase 仍是 RUNNING；shutdown 转换发生在所有
     * 正在 submit 的线程释放 gate 之后，因此不会发生"已 acquire token 但
     * scheduler 已停止"的孤儿任务。</p>
     *
     * <p>这是幂等的：如果已经在 QUIESCING，返回 true。
     *
     * @return true 如果成功进入 QUIESCING（或已在 QUIESCING）；false 如果已经 CLOSED
     */
    public boolean beginShutdown() {
        submitGate.lock();
        try {
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
        } finally {
            submitGate.unlock();
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
     * <p>RISK-02 修复：acquire submit gate 后强制 CAS 到 CLOSED，
     * 阻止任何 submit 在 acquire gate 后看到 RUNNING 状态。
     *
     * @return true 如果成功进入 CLOSED
     */
    public boolean halt() {
        submitGate.lock();
        try {
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
        } finally {
            submitGate.unlock();
        }
    }

    /**
     * 诊断用：submit gate 是否被持有。
     */
    public boolean isSubmitGateHeld() {
        return submitGate.isLocked();
    }

    @Override
    public String toString() {
        return "SchedulerLifecycle{" + phase.get() + ", submitGateHeld=" + submitGate.isLocked() + "}";
    }
}
