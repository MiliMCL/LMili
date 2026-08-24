package fun.bm.mili.lmili.runtime;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.runtime.control.ChunkController;
import fun.bm.mili.lmili.runtime.control.EntityController;
import fun.bm.mili.lmili.runtime.control.IOController;
import fun.bm.mili.lmili.runtime.control.MetricsController;
import fun.bm.mili.lmili.runtime.control.SchedulerController;
import fun.bm.mili.lmili.runtime.control.TickController;
import fun.bm.mili.lmili.runtime.exec.FanoutController;
import fun.bm.mili.lmili.runtime.io.OLinearFlusherBridge;
import fun.bm.mili.lmili.runtime.policy.PolicyCommand;
import fun.bm.mili.lmili.runtime.policy.PolicyController;
import fun.bm.mili.lmili.runtime.policy.PressureStateMachine;
import fun.bm.mili.lmili.runtime.policy.RuntimePolicySnapshot;
import fun.bm.mili.lmili.runtime.task.TickTaskType;
import fun.bm.mili.lmili.thread.runtime.metrics.RuntimeMetrics;
import fun.bm.mili.lmili.thread.scheduler.MiliTickRegionScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.MiliSchedulerHolder;
import fun.bm.mili.utils.performance.AdaptiveTPSManager;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自适应运行时装配中枢（ARCHITECTURE_AdaptiveRuntime.md §3.13）。
 *
 * <p>职责：装配全部控制器（§5.2 接线表）、生命周期编排（start/shutdown v2 顺序）、
 * 广播 onPolicyChange（§5.3 订阅模型）、fail-safe 观察（§6.2）。
 *
 * <p>关闭顺序（§6.1 规则 3 / §3.13 SHUTDOWN）：
 * {@code SHUTDOWN_ENTER → stopControlCycle → onRuntimeShutdown 广播 → policy.freeze()
 * → io.raiseAllFlushToCritical() → io.drainForShutdown(timeout) → scheduler.shutdown(timeout)
 * → TERMINATED}。
 */
public final class MiliRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 生命周期状态（原子可见） */
    public enum LifecycleState { NEW, STARTED, SHUTDOWN, TERMINATED }

    // ---- 控制器（组装期实例化；final 保证引用安全）----
    private final SchedulerController scheduler = new SchedulerController();
    private final TickController tick = new TickController();
    private final EntityController entity = new EntityController();
    private final ChunkController chunk = new ChunkController();
    private final OLinearFlusherBridge bridge = new OLinearFlusherBridge(null);
    private final IOController io = new IOController(bridge);
    private final MetricsController metrics = new MetricsController();
    private final PressureStateMachine stateMachine = new PressureStateMachine();
    private final PolicyController policy = new PolicyController(this, stateMachine);
    private final MiliGlobalController global = new MiliGlobalController(policy, stateMachine);
    private final FanoutController fanout = new FanoutController();

    // ---- 模块（可插拔子系统）----
    private final ConcurrentHashMap<String, RuntimeModule> modules = new ConcurrentHashMap<>();

    private final AtomicReference<LifecycleState> lifecycle = new AtomicReference<>(LifecycleState.NEW);

    public MiliRuntime() {
        // §5.2 装配：控制器接线（全部在构造器内完成，发布即安全）
        policy.wireControllers(scheduler, tick, entity, chunk, io, fanout);
        policy.attachControlPlane(global, metrics);
        global.wireMetrics(metrics);
        global.wireIo(io);
        io.wirePolicy(policy);
        chunk.wirePolicyChannel((command, tag) -> policy.submitCommandAsync(command));
        tick.wirePressureProvider(() -> policy.snapshot().pressure());
        scheduler.setBudgetForwarder(tick::applyBudgetPolicy);

        // 内置 MetricSource（面板/压力判定）
        metrics.registerSource(MetricsController.tpsSource());
        metrics.registerSource(MetricsController.schedulerSource(scheduler));
        metrics.registerSource(MetricsController.activeRegionsSource(scheduler));
        metrics.registerSource(MetricsController.regionLoadSource());
        metrics.registerSource(MetricsController.ioStateSource(io));
        metrics.registerSource(MetricsController.entityTickSource(entity));

        // §5.3 (D-10)：预算闸门装配到 MiliTickRegionScheduler（默认 off 直到本 runtime 创建）。
        // ordinal → TickTaskType 映射；TickController.tryAcquire 非阻塞（CAS），闸门失败由
        // 调度器侧 fail-open（§6.3 R1）。
        MiliTickRegionScheduler.setBudgetGate((regionId, ordinal) -> {
            final TickTaskType type = ordinal >= 0 && ordinal < TickTaskType.values().length
                    ? TickTaskType.values()[ordinal] : TickTaskType.ENTITY;
            return tick.tryAcquire(regionId, type);
        });

        // 调度器晚绑定（RuntimeBootstrap 在 TickRegionScheduler 就绪后 bind；幂等）
        scheduler.bind(MiliSchedulerHolder.get());
    }

    // ================= 生命周期 =================

    /**
     * 启动（幂等）：停用旧 AdaptiveTPSManager（D-14；disable() 不改其逻辑）→
     * 初始快照发布 → 模块 onRuntimeStart → 控制周期。
     */
    public void start() {
        if (!lifecycle.compareAndSet(LifecycleState.NEW, LifecycleState.STARTED)) {
            return;
        }
        try {
            AdaptiveTPSManager.disable();
        } catch (Throwable t) {
            LOGGER.warn("[MiliRuntime] AdaptiveTPSManager.disable failed (ignored)", t);
        }
        try {
            policy.initialize();
            for (RuntimeModule module : modules.values()) {
                try {
                    module.onRuntimeStart(this);
                } catch (Throwable t) {
                    LOGGER.error("[MiliRuntime] module {} onRuntimeStart failed", module.name(), t);
                }
            }
            policy.startControlCycle(global, metrics);
            LOGGER.info("[MiliRuntime] started (control cycle 10Hz, modules={})", modules.size());
        } catch (Throwable t) {
            LOGGER.error("[MiliRuntime] start failed", t);
        }
    }

    /**
     * 关闭（v2 顺序，§6.1 规则 3）。幂等：重复调用直接返回。
     *
     * @param ioDrainTimeout IO 排水有界等待（超时告警，不静默）
     */
    public void shutdown(Duration ioDrainTimeout) {
        final LifecycleState cur = lifecycle.get();
        if (cur == LifecycleState.SHUTDOWN || cur == LifecycleState.TERMINATED) {
            return;
        }
        lifecycle.set(LifecycleState.SHUTDOWN);
        final Duration drainTimeout = ioDrainTimeout != null ? ioDrainTimeout : Duration.ofSeconds(10);
        try {
            // 1. SHUTDOWN_ENTER（全局终态标记；经受控通道记录审计）
            global.markTerminated();
            policy.submitCommandAsync(PolicyCommand.fromStateMachine(
                    fun.bm.mili.lmili.runtime.policy.CommandAction.APPLY_STATE,
                    Map.of("state", "SHUTDOWN")));
            // 2. 停止控制周期
            policy.stopControlCycle();
            // 3. 模块关闭广播（含 flusher 相关模块）
            for (RuntimeModule module : modules.values()) {
                try {
                    module.onRuntimeShutdown(this);
                } catch (Throwable t) {
                    LOGGER.error("[MiliRuntime] module {} onRuntimeShutdown failed", module.name(), t);
                }
            }
            // 4. 冻结策略（拒绝任何新命令）
            policy.freeze();
            // 5. IO 排水（先提升全部 CRITICAL，再等待，再放行 flusher.shutdown）
            io.raiseAllFlushToCritical();
            io.drainForShutdown(drainTimeout);
            // 6. 调度器关闭（现有顺序不变）
            try {
                scheduler.shutdown(drainTimeout);
            } catch (Throwable t) {
                LOGGER.error("[MiliRuntime] scheduler shutdown failed", t);
            }
        } finally {
            lifecycle.set(LifecycleState.TERMINATED);
            LOGGER.info("[MiliRuntime] terminated");
        }
    }

    // ================= 订阅广播（§5.3） =================

    /** 策略快照发布（PolicyController 回调；模块只读订阅） */
    public void publishPolicy(RuntimePolicySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        for (RuntimeModule module : modules.values()) {
            try {
                module.onPolicyChange(snapshot);
            } catch (Throwable t) {
                LOGGER.warn("[MiliRuntime] module {} onPolicyChange failed", module.name(), t);
            }
        }
    }

    // ================= 模块注册 =================

    /**
     * 注册可插拔模块（重复注册抛 {@link RuntimeLifecycleException}；TERMINATED 后拒绝）。
     */
    public void registerModule(String name, RuntimeModule module) {
        if (module == null) {
            throw new RuntimeLifecycleException("module must not be null");
        }
        if (lifecycle.get() == LifecycleState.TERMINATED) {
            throw new RuntimeLifecycleException("runtime terminated: cannot register module " + name);
        }
        if (modules.putIfAbsent(name, module) != null) {
            throw new RuntimeLifecycleException("module already registered: " + name);
        }
        LOGGER.info("[MiliRuntime] module registered: {}", name);
    }

    public void unregisterModule(String name) {
        modules.remove(name);
    }

    public Set<String> moduleNames() {
        return Collections.unmodifiableSet(modules.keySet());
    }

    // ================= 装配/接线入口（RuntimeBootstrap 使用） =================

    public void bindScheduler(fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler ms) {
        scheduler.bind(ms);
    }

    /** 接线现有 RuntimeMetrics（可选；面板维度） */
    public void wireRuntimeMetrics(RuntimeMetrics m) {
        if (m != null) {
            metrics.wireRuntimeMetrics(m);
            metrics.registerSource(metrics.runtimeMetricsSource());
        }
    }

    /** 权限检查器（命令路径；null = fail-closed） */
    public void setPermissionChecker(java.util.function.BiPredicate<String, String> checker) {
        policy.setPermissionChecker(checker);
    }

    // ================= 只读访问器 =================

    public SchedulerController scheduler() { return scheduler; }

    public TickController tick() { return tick; }

    public EntityController entity() { return entity; }

    public ChunkController chunk() { return chunk; }

    public IOController io() { return io; }

    public MetricsController metrics() { return metrics; }

    public PolicyController policy() { return policy; }

    public MiliGlobalController global() { return global; }

    public FanoutController fanout() { return fanout; }

    public OLinearFlusherBridge bridge() { return bridge; }

    public PressureStateMachine stateMachine() { return stateMachine; }

    public LifecycleState lifecycleState() {
        return lifecycle.get();
    }

    public boolean isRunning() {
        return lifecycle.get() == LifecycleState.STARTED;
    }

    /** 观测接口：fail-safe 全源失败（面板告警） */
    public boolean allMetricsFailed() {
        return metrics.allSourcesFailed();
    }
}
