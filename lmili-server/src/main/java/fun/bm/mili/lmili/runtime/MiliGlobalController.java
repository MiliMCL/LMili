package fun.bm.mili.lmili.runtime;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.runtime.control.ControlPanelSnapshot;
import fun.bm.mili.lmili.runtime.control.IOController;
import fun.bm.mili.lmili.runtime.control.MetricsController;
import fun.bm.mili.lmili.runtime.io.IoSaturationLevel;
import fun.bm.mili.lmili.runtime.policy.CommandAction;
import fun.bm.mili.lmili.runtime.policy.PolicyCommand;
import fun.bm.mili.lmili.runtime.policy.PolicyController;
import fun.bm.mili.lmili.runtime.policy.PressureSignals;
import fun.bm.mili.lmili.runtime.policy.PressureState;
import fun.bm.mili.lmili.runtime.policy.PressureStateMachine;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全局控制中枢（Control Plane）—— 全局开关、预算百分比、手动降级/恢复、健康快照、控制周期
 * （ARCHITECTURE_AdaptiveRuntime.md §3.2）。
 *
 * <p>本类<strong>不直接 tick、不提交任务、不写文件</strong>；
 * 所有运行期变更（含状态迁移）经 {@link PolicyController#submitCommandAsync} 受控通道
 * （§6.1 规则 6：STATE_MACHINE/INTERNAL_MODULE 同样走通道）。setter 钳制 + 审计（审计在 PolicyController 层）。
 */
public final class MiliGlobalController {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 全局开关/参数（defaults）----
    private final AtomicBoolean runtimeEnabled = new AtomicBoolean(true);
    private final AtomicInteger cpuBudgetPct = new AtomicInteger(75);
    private final AtomicInteger ioBudgetPct = new AtomicInteger(60);
    private final AtomicBoolean parallelTickEnabled = new AtomicBoolean(true);
    private final AtomicBoolean oLinearEnabled = new AtomicBoolean(true);
    private final AtomicBoolean entityThrottleEnabled = new AtomicBoolean(false);
    private final AtomicBoolean backpressureActive = new AtomicBoolean(false);
    private final AtomicReference<PressureState> pressureState = new AtomicReference<>(PressureState.NORMAL);
    private final AtomicReference<DegradeReason> degradeReason = new AtomicReference<>(DegradeReason.NONE);
    private final AtomicLong lastControlCycleNanos = new AtomicLong();

    // ---- 子引用（装配期注入）----
    private final PolicyController policy;
    private final PressureStateMachine stateMachine;
    private volatile MetricsController metrics;
    private volatile IOController io;

    // ---- 发散检测（§6.2：连续 10 周期恶化 → DEGRADED + 告警）----
    private int divergenceCycles;
    private final AtomicBoolean divergenceDetected = new AtomicBoolean(false);
    private double prevCycleTps = 20.0;
    private long prevCycleQueue = 0;

    // ---- §4.6 闭环（IO worker AIAD；默认关闭）----
    private int ioAdjustCooldownCycles = 0;
    private int ioDecreaseCycles = 0;

    public MiliGlobalController(PolicyController policy, PressureStateMachine stateMachine) {
        this.policy = policy;
        this.stateMachine = stateMachine;
    }

    // ================= 控制周期（唯一调用方：PolicyController 的 10Hz 调度） =================

    /**
     * 每控制周期回调：采集信号 → fail-safe 判定 → 状态机迁移（经受控通道）→ §4.6 闭环 → 发散检测。
     * 本方法不阻塞、不写文件、不直接 tick。
     */
    public void onControlCycle(PressureSignals signals) {
        if (signals == null) {
            return;
        }
        if (!runtimeEnabled.get()) {
            return;
        }
        if (policy.isFrozen()) {
            return;
        }
        if (signals.shuttingDown()) {
            return;
        }
        lastControlCycleNanos.set(System.nanoTime());

        // §6.2 fail-safe：全部源失败 → DEGRADED 观察态（保持当前预算、禁止激进扩缩）
        if (metrics != null && metrics.allSourcesFailed()) {
            if (pressureState.get() != PressureState.DEGRADED) {
                LOGGER.error("[MiliGlobalController] All metric sources failed — entering DEGRADED (fail-safe, §6.2)");
                policy.submitCommandAsync(PolicyCommand.fromStateMachine(CommandAction.APPLY_STATE,
                        Map.of("state", "DEGRADED", "reason", "METRICS_FAILURE")));
            }
            return; // DEGRADED 观察态：不继续迁移
        }

        // 状态机迁移（滞回；经受控通道应用，§6.1 规则 6）
        final boolean changed = stateMachine.transition(signals);
        if (changed) {
            final PressureState next = stateMachine.current();
            policy.submitCommandAsync(PolicyCommand.fromStateMachine(CommandAction.APPLY_STATE,
                    Map.of("state", next.name())));
        }

        // §4.6 CPU↔Scheduler↔IO 闭环（AIAD 步进；观察 5 周期；默认关闭）
        if (io != null && io.isAutoScalingEnabled()) {
            adjustIoWorkers(signals);
        }

        // §6.2 发散检测（连续 10 周期恶化 → DEGRADED + 告警）
        detectDivergence(signals);
    }

    /** §4.6：AIAD（Additive Increase / Additive Decrease）IO worker 调整 */
    private void adjustIoWorkers(PressureSignals s) {
        if (ioAdjustCooldownCycles > 0) {
            ioAdjustCooldownCycles--; // 观察 5 周期
            return;
        }
        final IoSaturationLevel level = fun.bm.mili.lmili.runtime.io.OLinearFlusherBridge.computeLevel(s.ioQueueDepth(), s.ioP99Nanos());
        final int currentWorkers = policy.snapshot().ioWorkers();
        if (s.cpuLoad() > 0.75 && level == IoSaturationLevel.NORMAL) {
            // 增加：min(w+1, maxIoWorkers)
            final int target = Math.min(currentWorkers + 1, io.maxIoWorkers());
            if (target != currentWorkers) {
                policy.submitCommandAsync(PolicyCommand.fromModule("GlobalController-AIAD",
                        CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", String.valueOf(target))));
                ioAdjustCooldownCycles = 5;
            }
        } else if (s.cpuLoad() < 0.5 && s.ioQueueDepth() < 200) {
            if (++ioDecreaseCycles >= 10) {
                final int target = Math.max(1, currentWorkers - 1);
                if (target != currentWorkers) {
                    policy.submitCommandAsync(PolicyCommand.fromModule("GlobalController-AIAD",
                            CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", String.valueOf(target))));
                }
                ioDecreaseCycles = 0;
                ioAdjustCooldownCycles = 5;
            }
        } else {
            ioDecreaseCycles = 0;
        }
    }

    /** §6.2：发散检测 —— 压力态下连续 10 周期恶化 → DEGRADED + 告警 */
    private void detectDivergence(PressureSignals s) {
        final PressureState st = pressureState.get();
        if (st == PressureState.CPU_PRESSURE) {
            if (s.tps() < prevCycleTps - 0.01) {
                if (++divergenceCycles >= 10 && !divergenceDetected.getAndSet(true)) {
                    LOGGER.error("[MiliGlobalController] DIVERGENCE: TPS worsening for 10 cycles in CPU_PRESSURE — entering DEGRADED");
                    policy.submitCommandAsync(PolicyCommand.fromStateMachine(CommandAction.DEGRADE,
                            Map.of("reason", "DIVERGENCE")));
                }
            } else {
                divergenceCycles = 0;
            }
        } else if (st == PressureState.IO_PRESSURE) {
            if (s.ioQueueDepth() > prevCycleQueue) {
                if (++divergenceCycles >= 10 && !divergenceDetected.getAndSet(true)) {
                    LOGGER.error("[MiliGlobalController] DIVERGENCE: IO queue worsening for 10 cycles in IO_PRESSURE — entering DEGRADED");
                    policy.submitCommandAsync(PolicyCommand.fromStateMachine(CommandAction.DEGRADE,
                            Map.of("reason", "DIVERGENCE")));
                }
            } else {
                divergenceCycles = 0;
            }
        } else {
            divergenceCycles = 0;
            if (st == PressureState.NORMAL) {
                divergenceDetected.set(false);
            }
        }
        prevCycleTps = s.tps();
        prevCycleQueue = s.ioQueueDepth();
    }

    // ================= 健康快照 / 只读 =================

    public GlobalHealthSnapshot healthSnapshot() {
        final MetricsController.PanelMetrics m = metrics != null ? metrics.panelMetrics() : null;
        return new GlobalHealthSnapshot(
                pressureState.get(),
                m != null ? m.tps() : 0,
                m != null ? m.cpuLoad() : 0,
                m != null ? m.schedulerLoadPct() : 0,
                m != null ? m.ioQueueDepth() : 0,
                m != null ? m.ioP99Nanos() : 0,
                m != null ? m.totalRegions() : 0,
                m != null ? m.activeRegions() : 0,
                m != null ? m.workers() : 0,
                parallelTickEnabled.get(),
                oLinearEnabled.get(),
                entityThrottleEnabled.get(),
                backpressureActive.get(),
                degradeReason.get(),
                System.nanoTime()
        );
    }

    public PressureState pressureState() {
        return pressureState.get();
    }

    public DegradeReason degradeReason() {
        return degradeReason.get();
    }

    public boolean isRuntimeEnabled() {
        return runtimeEnabled.get();
    }

    public int cpuBudgetPct() {
        return cpuBudgetPct.get();
    }

    public int ioBudgetPct() {
        return ioBudgetPct.get();
    }

    public boolean isBackpressureActive() {
        return backpressureActive.get();
    }

    public boolean isDivergenceDetected() {
        return divergenceDetected.get();
    }

    public long lastControlCycleNanos() {
        return lastControlCycleNanos.get();
    }

    // ================= 手动降级 / 恢复（经受控通道；PolicyController 在命令翻译后调用内部方法） =================

    /** 进入手动降级（/lmili control degrade；记录审计——审计在 PolicyController 层） */
    public void enterDegraded(DegradeReason reason) {
        setDegradeReasonInternal(reason);
        setPressureStateInternal(PressureState.DEGRADED);
        LOGGER.warn("[MiliGlobalController] Entered DEGRADED ({})", reason);
    }

    public void restoreNormal() {
        setDegradeReasonInternal(DegradeReason.NONE);
        setPressureStateInternal(PressureState.NORMAL);
        divergenceDetected.set(false);
        divergenceCycles = 0;
        LOGGER.info("[MiliGlobalController] Restored NORMAL");
    }

    /** 仅 PolicyController 编排调用 */
    public void setPressureStateInternal(PressureState s) {
        if (s != null) {
            pressureState.set(s);
        }
    }

    /** 仅 PolicyController 编排调用 */
    public void setDegradeReasonInternal(DegradeReason reason) {
        if (reason != null) {
            degradeReason.set(reason);
        }
    }

    /** 仅 PolicyController 编排调用（状态动作） */
    public void setBackpressureInternal(boolean on) {
        backpressureActive.set(on);
    }

    /** 仅 PolicyController 编排调用（SET_PARALLEL 翻译） */
    public void setParallelTickEnabled(boolean on) {
        parallelTickEnabled.set(on);
    }

    /** 仅 PolicyController 编排调用（SET_THROTTLE 翻译） */
    public void setEntityThrottleEnabled(boolean on) {
        entityThrottleEnabled.set(on);
    }

    /** 仅 PolicyController 编排调用（SET_CPU_BUDGET 翻译；钳制 [10,100]） */
    public void applyCpuBudgetPctInternal(int pct) {
        cpuBudgetPct.set(Math.max(10, Math.min(100, pct)));
    }

    /** 仅 PolicyController 编排调用（SET_IO_BUDGET 翻译；钳制 [10,100]） */
    public void applyIoBudgetPctInternal(int pct) {
        ioBudgetPct.set(Math.max(10, Math.min(100, pct)));
    }

    // ================= 公共 setter（钳制 + 经受控通道；命令层不应直接调用） =================

    /** 设置全局 CPU 预算百分比（钳制 [10,100]；经受控通道生效） */
    public void setCpuBudgetPct(int pct) {
        policy.submitCommandAsync(PolicyCommand.fromModule("GlobalController",
                CommandAction.SET_CPU_BUDGET, Map.of("cpuBudgetPct", String.valueOf(Math.max(10, Math.min(100, pct))))));
    }

    /** 设置全局 IO 预算百分比（钳制 [10,100]；经受控通道生效） */
    public void setIoBudgetPct(int pct) {
        policy.submitCommandAsync(PolicyCommand.fromModule("GlobalController",
                CommandAction.SET_IO_BUDGET, Map.of("ioBudgetPct", String.valueOf(Math.max(10, Math.min(100, pct))))));
    }

    /** 设置调度器 worker 数（§8.3 限制 1：动态 worker 与 work-stealing 交互未压测 → 默认 no-op） */
    public void setWorkers(int n) {
        policy.submitCommandAsync(PolicyCommand.fromModule("GlobalController",
                CommandAction.SET_SCHEDULER_MODE,
                Map.of("schedulerWorkers", String.valueOf(Math.max(1, Math.min(64, n))))));
    }

    /** 设置 IO worker 数（钳制 [1, 24]；经受控通道生效） */
    public void setIoWorkers(int n) {
        policy.submitCommandAsync(PolicyCommand.fromModule("GlobalController",
                CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", String.valueOf(Math.max(1, Math.min(24, n))))));
    }

    /** 手动降级（程序化入口；经受控通道生效） */
    public void requestDegrade(DegradeReason reason) {
        policy.submitCommandAsync(PolicyCommand.fromModule("GlobalController",
                CommandAction.DEGRADE, Map.of("reason", String.valueOf(reason))));
    }

    /** 手动恢复（程序化入口；经受控通道生效） */
    public void requestRestore() {
        policy.submitCommandAsync(PolicyCommand.fromModule("GlobalController",
                CommandAction.RESTORE, Map.of()));
    }

    // ================= 关闭编排（仅 MiliRuntime 调用） =================

    /** 关闭编排终态：压力 → SHUTDOWN，reason → SHUTDOWN（幂等） */
    public void markTerminated() {
        pressureState.set(PressureState.SHUTDOWN);
        degradeReason.set(DegradeReason.SHUTDOWN);
        backpressureActive.set(false);
        LOGGER.info("[MiliGlobalController] Marked terminated");
    }

    // ================= 装配（仅 MiliRuntime 调用） =================

    public void wireMetrics(MetricsController metrics) {
        this.metrics = metrics;
    }

    public void wireIo(IOController io) {
        this.io = io;
    }

    public void setRuntimeEnabled(boolean on) {
        runtimeEnabled.set(on);
    }

    /** 面板快照（委托 PolicyController 组装；命令层只读） */
    public ControlPanelSnapshot panelSnapshot() {
        return policy.panelSnapshot();
    }
}
