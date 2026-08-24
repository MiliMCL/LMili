package fun.bm.mili.lmili.runtime.policy;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 压力状态机 —— 滞回防抖 + 迁移限速（ARCHITECTURE_AdaptiveRuntime.md §3.10 / §4.3 / D-05）。
 *
 * <p>滞回（D-05）：进入需连续满足 2 个控制周期（enterCycles）、退出需连续满足 3 个（exitCycles）；
 * 最小驻留 5s（minDwellMillis）；每分钟迁移上限 6 次（maxTransitionsPerMinute），
 * 超限锁定 30s（lockedUntilMillis）。
 *
 * <p>transition 内部 try/catch：连续 5 次失败 → 强制 DEGRADED（§6.2 状态机失败出口）。
 * 仅 GlobalController.onControlCycle 调用（控制线程单写者；锁为兜底，非自旋）。
 */
public final class PressureStateMachine {

    private final AtomicReference<PressureState> state = new AtomicReference<>(PressureState.NORMAL);
    private final AtomicBoolean frozen = new AtomicBoolean(false);
    private volatile PressureThresholds thresholds = PressureThresholds.DEFAULTS;

    // ---- 控制线程单写者状态（transition 串行调用）----
    private final double[] emaSignals = new double[4]; // tpsEma, cpuEma, ioQueueEma, ioP99Ema
    private int enterCpuCycles;
    private int enterIoCycles;
    private int exitCpuCycles;
    private int exitIoCycles;
    private int failureCount;
    private final ArrayDeque<Long> transitionTimestamps = new ArrayDeque<>();

    // ---- 跨线程只读/volatile ----
    private volatile long enteredAtMillis;
    private volatile long lastTransitionAtMillis;
    private volatile long lockedUntilMillis;
    private volatile String lastReason = "INIT";
    private volatile PressureState lastExit = PressureState.NORMAL;

    private final Object lock = new Object();

    /**
     * 推进状态机（每控制周期调用一次）。
     *
     * @return true 表示发生了迁移（GlobalController 据此提交 APPLY_STATE 受控命令）
     */
    public boolean transition(PressureSignals signals) {
        synchronized (lock) {
            try {
                return doTransition(signals);
            } catch (Throwable t) {
                failureCount++;
                lastReason = "EXCEPTION:" + t.getClass().getSimpleName();
                if (failureCount >= 5) {
                    // §6.2：连续 5 次失败 → DEGRADED 观察态（保持当前预算，禁止激进扩缩）
                    state.set(PressureState.DEGRADED);
                    lastReason = "STATE_MACHINE_FAILURE";
                }
                return false;
            }
        }
    }

    private boolean doTransition(PressureSignals s) {
        if (s == null || s.shuttingDown()) {
            return false;
        }
        if (frozen.get()) {
            return false;
        }
        final PressureThresholds th = thresholds;
        final long now = System.currentTimeMillis();
        if (lockedUntilMillis > now) {
            lastReason = "LOCKED:" + (lockedUntilMillis - now) + "ms";
            return false;
        }

        // EMA 输入平滑（α=0.3，§4.2；metrics 侧已平滑一次，此处双保险）
        emaSignals[0] = 0.3 * s.tps() + 0.7 * emaSignals[0];
        emaSignals[1] = 0.3 * s.cpuLoad() + 0.7 * emaSignals[1];
        emaSignals[2] = 0.3 * s.ioQueueDepth() + 0.7 * emaSignals[2];
        emaSignals[3] = 0.3 * s.ioP99Nanos() + 0.7 * emaSignals[3];
        final double tps = emaSignals[0];
        final double cpu = emaSignals[1];
        final double queue = emaSignals[2];
        final double p99 = emaSignals[3];

        final PressureState cur = state.get();
        if (cur == PressureState.SHUTDOWN) {
            return false;
        }

        final boolean enteringCpu = tps < th.enterCpuTps() && cpu > th.enterCpuLoad();
        final boolean exitingCpu = tps > th.exitCpuTps() || cpu < th.exitCpuLoad();
        final boolean enteringIo = queue >= th.enterIoQueue() || p99 >= th.enterIoP99Nanos();
        final boolean exitingIo = queue < th.exitIoQueue() && p99 < th.exitIoP99Nanos();

        if (cur == PressureState.NORMAL) {
            if (enteringCpu) {
                if (++enterCpuCycles >= th.enterCycles()) {
                    enterCpuCycles = 0;
                    return migrateTo(PressureState.CPU_PRESSURE,
                            String.format("tps=%.1f<%.1f && cpu=%.0f%%>%.0f%%", tps, th.enterCpuTps(), cpu * 100, th.enterCpuLoad() * 100));
                }
            } else {
                enterCpuCycles = 0;
            }
            if (enteringIo) {
                if (++enterIoCycles >= th.enterCycles()) {
                    enterIoCycles = 0;
                    return migrateTo(PressureState.IO_PRESSURE,
                            String.format("queue=%.0f>=%d || p99=%.0fms>=%dms", queue, th.enterIoQueue(), p99 / 1_000_000, th.enterIoP99Nanos() / 1_000_000));
                }
            } else {
                enterIoCycles = 0;
            }
            return false;
        }
        if (cur == PressureState.CPU_PRESSURE) {
            if (exitingCpu) {
                if (++exitCpuCycles >= th.exitCycles()) {
                    exitCpuCycles = 0;
                    return migrateTo(PressureState.NORMAL,
                            String.format("tps=%.1f>%.1f || cpu=%.0f%%<%.0f%%", tps, th.exitCpuTps(), cpu * 100, th.exitCpuLoad() * 100));
                }
            } else {
                exitCpuCycles = 0;
            }
            return false;
        }
        if (cur == PressureState.IO_PRESSURE) {
            if (exitingIo) {
                if (++exitIoCycles >= th.exitCycles()) {
                    exitIoCycles = 0;
                    return migrateTo(PressureState.NORMAL,
                            String.format("queue=%.0f<%d && p99=%.0fms<%dms", queue, th.exitIoQueue(), p99 / 1_000_000, th.exitIoP99Nanos() / 1_000_000));
                }
            } else {
                exitIoCycles = 0;
            }
            return false;
        }
        // DEGRADED：不自动迁移（仅手动 restore / 编排）
        return false;
    }

    private boolean migrateTo(PressureState next, String reason) {
        final long now = System.currentTimeMillis();
        // 最小驻留（D-05：5s）
        if (lastTransitionAtMillis > 0 && now - lastTransitionAtMillis < thresholds.minDwellMillis()) {
            lastReason = "MIN_DWELL";
            return false;
        }
        // 60s 窗口内迁移次数上限（6 次）→ 锁定 30s
        final long windowStart = now - 60_000;
        while (!transitionTimestamps.isEmpty() && transitionTimestamps.peekFirst() < windowStart) {
            transitionTimestamps.pollFirst();
        }
        if (transitionTimestamps.size() >= thresholds.maxTransitionsPerMinute()) {
            lockedUntilMillis = now + 30_000;
            transitionTimestamps.clear();
            lastReason = "RATE_LIMIT_LOCK_30S";
            return false;
        }

        lastExit = state.getAndSet(next);
        enteredAtMillis = now;
        lastTransitionAtMillis = now;
        transitionTimestamps.addLast(now);
        lastReason = reason;
        failureCount = 0;
        return true;
    }

    // ================= 只读查询（面板/测试） =================

    public PressureState current() {
        return state.get();
    }

    public PressureState lastExit() {
        return lastExit;
    }

    public long enteredAtMillis() {
        return enteredAtMillis;
    }

    public long lastTransitionAtMillis() {
        return lastTransitionAtMillis;
    }

    public String lastReason() {
        return lastReason;
    }

    public boolean isLocked() {
        return lockedUntilMillis > System.currentTimeMillis();
    }

    public long lockedUntilMillis() {
        return lockedUntilMillis;
    }

    public int failureCount() {
        return failureCount;
    }

    /** 60s 窗口内已迁移次数（诊断） */
    public int transitionsInWindow() {
        synchronized (lock) {
            final long windowStart = System.currentTimeMillis() - 60_000;
            while (!transitionTimestamps.isEmpty() && transitionTimestamps.peekFirst() < windowStart) {
                transitionTimestamps.pollFirst();
            }
            return transitionTimestamps.size();
        }
    }

    // ================= 编排接口（仅 PolicyController / MiliRuntime 调用） =================

    /** 阈值集替换（volatile 原子发布；测试/调优） */
    public void updateThresholds(PressureThresholds thresholds) {
        if (thresholds != null) {
            this.thresholds = thresholds;
        }
    }

    public PressureThresholds thresholds() {
        return thresholds;
    }

    /** 冻结（PolicyController.freeze() 调用；冻结后不再迁移） */
    public void freeze() {
        frozen.set(true);
    }

    public void unfreeze() {
        frozen.set(false);
    }

    public boolean isFrozen() {
        return frozen.get();
    }

    /**
     * 直接置位（仅 PolicyController 编排：manual degrade/restore 与关闭编排）。
     * 状态机的"裁决权"在 PolicyController；此处只是镜像。
     */
    public void forceState(PressureState s) {
        if (s == null) {
            return;
        }
        synchronized (lock) {
            lastExit = state.getAndSet(s);
            enteredAtMillis = System.currentTimeMillis();
            lastTransitionAtMillis = System.currentTimeMillis();
            transitionTimestamps.addLast(lastTransitionAtMillis);
            lastReason = "FORCED:" + s;
            failureCount = 0;
        }
    }
}
