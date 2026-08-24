package fun.bm.mili.lmili.runtime.policy;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.runtime.DegradeReason;
import fun.bm.mili.lmili.runtime.MiliGlobalController;
import fun.bm.mili.lmili.runtime.MiliRuntime;
import fun.bm.mili.lmili.runtime.control.ChunkController;
import fun.bm.mili.lmili.runtime.control.ControlPanelSnapshot;
import fun.bm.mili.lmili.runtime.control.EntityController;
import fun.bm.mili.lmili.runtime.control.IOController;
import fun.bm.mili.lmili.runtime.control.MetricsController;
import fun.bm.mili.lmili.runtime.control.SchedulerController;
import fun.bm.mili.lmili.runtime.control.TickController;
import fun.bm.mili.lmili.runtime.control.TickStatsSnapshot;
import fun.bm.mili.lmili.runtime.exec.FanoutController;
import fun.bm.mili.lmili.runtime.io.FlushPriority;
import fun.bm.mili.lmili.runtime.io.GlobalFlushPolicy;
import fun.bm.mili.lmili.runtime.io.PersistencePriority;
import fun.bm.mili.lmili.runtime.io.PluginHint;
import fun.bm.mili.lmili.runtime.io.PluginHintResult;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

/**
 * 策略控制器 —— 运行期策略变更的<strong>唯一受控通道</strong>（ARCHITECTURE_AdaptiveRuntime.md §3.9 / D-03 / §6.1）。
 *
 * <p>流程：语法校验 → 权限校验（仅命令路径）→ 值域校验（不信任命令层钳制，§6.1 规则 2）
 * → copy-on-write 派生 → CAS 生效 → 广播 onPolicyChange → 审计。
 * 状态机自动迁移与内部模块同样走本通道（§6.1 规则 6），但不占命令限频额度。
 *
 * <p>并发模型（§6.5）：单线程 policyExecutor（"Mili-Policy"）串行化全部写入；
 * 快照 AtomicReference CAS 发布；禁自旋锁、禁跨 Controller synchronized 组合。
 *
 * <p>熔断（§6.2）：控制器动作连续失败 ≥3 次 → Open 熔断 5 分钟（默认），
 * 期间跳过该动作并使用最后已知良好快照；到期进入 Half-Open 试运行。
 */
public final class PolicyController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int COMMAND_RATE_LIMIT_PER_MINUTE = 10;   // 写命令 10 次/分钟/actor
    private static final int PLUGIN_HINT_RATE_LIMIT_PER_MINUTE = 60; // 插件 hint 60 次/分钟/plugin
    private static final long SUBMIT_TIMEOUT_MS = 5_000;

    private final MiliRuntime runtime;
    private final PressureStateMachine stateMachine;
    private final PolicyAuditLog auditLog = new PolicyAuditLog();

    private final AtomicReference<RuntimePolicySnapshot> current =
            new AtomicReference<>(RuntimePolicySnapshot.initial());
    private final AtomicReference<RuntimePolicySnapshot> previous = new AtomicReference<>(null);
    private final AtomicBoolean frozen = new AtomicBoolean(false);

    private final ExecutorService policyExecutor;
    private final ScheduledExecutorService controlScheduler;
    private final AtomicBoolean controlRunning = new AtomicBoolean(false);

    // ---- 子控制器（wireControllers 装配期注入）----
    private volatile SchedulerController scheduler;
    private volatile TickController tick;
    private volatile EntityController entity;
    private volatile ChunkController chunk;
    private volatile IOController io;
    private volatile FanoutController fanout;
    private volatile MiliGlobalController global;
    private volatile MetricsController metrics;

    // ---- 权限检查（命令路径；null = 拒绝（fail-closed））----
    private volatile BiPredicate<String, String> permissionChecker = null;

    // ---- 限频（actor → 窗口）----
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> hintRateWindows = new ConcurrentHashMap<>();

    // ---- 熔断（action → circuit）----
    private final ConcurrentHashMap<String, ActionCircuit> circuits = new ConcurrentHashMap<>();
    private volatile long circuitOpenDurationNanos = 300_000_000_000L; // 5min

    public PolicyController(MiliRuntime runtime, PressureStateMachine stateMachine) {
        this.runtime = runtime;
        this.stateMachine = stateMachine;
        this.policyExecutor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "Mili-Policy");
            t.setDaemon(true);
            return t;
        });
        this.controlScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "Mili-Control");
            t.setDaemon(true);
            return t;
        });
    }

    // ================= 唯一受控通道 =================

    /**
     * 提交策略命令（同步返回；内部经单线程 policyExecutor 串行化）。
     */
    public CommandResult submitCommand(PolicyCommand command) {
        if (command == null) {
            return CommandResult.rejected("NULL_COMMAND");
        }
        try {
            return policyExecutor.submit(() -> applyInternal(command)).get(SUBMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            audit(command, "TIMEOUT", -1, -1, -1);
            return CommandResult.rejected("POLICY_EXECUTOR_TIMEOUT");
        } catch (java.util.concurrent.ExecutionException ee) {
            audit(command, "EXECUTOR_FAILURE", -1, -1, -1);
            LOGGER.error("[PolicyController] submitCommand execution failure", ee.getCause());
            return CommandResult.rejected("POLICY_EXECUTOR_FAILURE");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return CommandResult.rejected("INTERRUPTED");
        }
    }

    /**
     * 非阻塞提交（控制周期/状态机使用；返回 future 供测试 join）。
     */
    public CompletableFuture<CommandResult> submitCommandAsync(PolicyCommand command) {
        if (command == null) {
            return CompletableFuture.completedFuture(CommandResult.rejected("NULL_COMMAND"));
        }
        return CompletableFuture.supplyAsync(() -> applyInternal(command), policyExecutor);
    }

    // ================= apply 核心（policyExecutor 单线程内执行） =================

    private CommandResult applyInternal(PolicyCommand cmd) {
        final CommandSource source = cmd.source();
        final String actor = cmd.actor() == null ? "UNKNOWN" : cmd.actor();

        if (frozen.get()) {
            audit(cmd, "REJECTED:FROZEN", -1, -1, -1);
            return CommandResult.rejected("SHUTDOWN: policy frozen");
        }

        // 1. 语法校验（参数完整性/可解析性；先于权限，§6.1 规则 2）
        final String syntaxError = validateSyntax(cmd);
        if (syntaxError != null) {
            audit(cmd, "REJECTED:SYNTAX:" + syntaxError, -1, -1, -1);
            return CommandResult.rejected("INVALID_PARAMETERS: " + syntaxError);
        }

        // 2. 权限校验（仅命令路径；STATE_MACHINE/INTERNAL_MODULE 受信）
        if (source == CommandSource.PLAYER || source == CommandSource.CONSOLE || source == CommandSource.RCON) {
            final String perm = requiredPermission(cmd.action());
            if (perm != null && !hasPermission(actor, perm)) {
                audit(cmd, "REJECTED:PERMISSION", -1, -1, -1);
                return CommandResult.rejected("PERMISSION_DENIED: " + perm);
            }
            if (cmd.action() == CommandAction.DEGRADE || cmd.action() == CommandAction.RESTORE
                    || cmd.action() == CommandAction.ROLLBACK) {
                if (!hasPermission(actor, "minecraft.command.op")) {
                    audit(cmd, "REJECTED:OP_REQUIRED", -1, -1, -1);
                    return CommandResult.rejected("PERMISSION_DENIED: minecraft.command.op required");
                }
            }
            // 3. 写命令限频（10 次/分钟/actor）
            if (!rateLimit(rateWindows, actor, COMMAND_RATE_LIMIT_PER_MINUTE)) {
                audit(cmd, "REJECTED:RATE_LIMITED", -1, -1, -1);
                return CommandResult.rejected("RATE_LIMITED (max " + COMMAND_RATE_LIMIT_PER_MINUTE + " writes/min)");
            }
        }

        // 4. 值域校验 + 翻译执行
        return applyAction(cmd, current.get(), actor);
    }

    private CommandResult applyAction(PolicyCommand cmd, RuntimePolicySnapshot cur, String actor) {
        // copy-on-write：基于当前快照派生 Builder（不可变 → 新快照，§6.4）
        final RuntimePolicySnapshot.Builder builder = new RuntimePolicySnapshot.Builder(cur);
        try {
            switch (cmd.action()) {
                case SET_SCHEDULER_MODE -> {
                    final int n = parseRangeInt(cmd, "schedulerWorkers", 1, 64);
                    scheduler.setWorkers(n);
                    builder.schedulerWorkers(n);
                }
                case SET_IO_WORKERS -> {
                    final int n = parseRangeInt(cmd, "ioWorkers", 1, 24);
                    withCircuit("setWorkerCount", () -> io.setWorkerCount(n));
                    builder.ioWorkers(n);
                }
                case SET_PARALLEL -> {
                    final boolean on = parseBool(cmd, "enabled");
                    global.setParallelTickEnabled(on);
                    tick.setParallelTickEnabled(on);
                    builder.parallelTickEnabled(on);
                }
                case SET_THROTTLE -> {
                    final boolean on = parseBool(cmd, "enabled");
                    global.setEntityThrottleEnabled(on);
                    entity.setThrottleEnabled(on);
                    builder.entityThrottleEnabled(on);
                }
                case SET_CPU_BUDGET -> {
                    final int pct = parseRangeInt(cmd, "cpuBudgetPct", 10, 100);
                    global.applyCpuBudgetPctInternal(pct);
                    builder.cpuBudgetPct(pct);
                }
                case SET_IO_BUDGET -> {
                    final int pct = parseRangeInt(cmd, "ioBudgetPct", 10, 100);
                    global.applyIoBudgetPctInternal(pct);
                    builder.ioBudgetPct(pct);
                }
                case SET_FANOUT -> {
                    final int n = parseRangeInt(cmd, "fanOut", 1, 64);
                    fanout.setMaxFanOut(n);
                    builder.maxFanOut(n);
                }
                case SET_TPS_TARGET -> {
                    final double t = parseRangeDouble(cmd, "tpsTarget", 10.0, 20.0);
                    tick.applyTpsTarget(t);
                }
                case SET_FLUSH_PRIORITY -> {
                    final long rid = parseLong(cmd, "regionId");
                    final FlushPriority p = parseFlushPriority(cmd, "priority");
                    withCircuit("setFlushPriority", () -> io.setFlushPriority(rid, p));
                    builder.flushPriority(rid, p);
                }
                case DEGRADE -> {
                    final DegradeReason reason = parseDegradeReason(cmd);
                    global.enterDegraded(reason);
                    builder.pressure(PressureState.DEGRADED);
                }
                case RESTORE -> {
                    if (cur.pressure() != PressureState.DEGRADED) {
                        audit(cmd, "REJECTED:NOT_IN_DEGRADED", -1, cur.version(), -1);
                        return CommandResult.rejected("NOT_IN_DEGRADED");
                    }
                    global.restoreNormal();
                    stateMachine.forceState(PressureState.NORMAL);
                    builder.pressure(PressureState.NORMAL);
                }
                case ROLLBACK -> {
                    return rollbackInternal(cmd, cur, actor);
                }
                case APPLY_STATE -> {
                    final PressureState target = parseState(cmd, "state");
                    final PressureState prev = cur.pressure();
                    if (target == prev) {
                        return CommandResult.rejected("NO_STATE_CHANGE");
                    }
                    if (target == PressureState.SHUTDOWN) {
                        return CommandResult.rejected("SHUTDOWN_RESERVED");
                    }
                    final DegradeReason reason = parseDegradeReason(cmd);
                    if (target == PressureState.DEGRADED && reason != DegradeReason.NONE) {
                        global.setDegradeReasonInternal(reason);
                    }
                    runStateActions(prev, target);
                    stateMachine.forceState(target);
                    global.setPressureStateInternal(target);
                    builder.pressure(target);
                }
                default -> {
                    audit(cmd, "REJECTED:UNKNOWN_ACTION", -1, -1, -1);
                    return CommandResult.rejected("UNKNOWN_ACTION");
                }
            }
        } catch (RejectedParamException rpe) {
            audit(cmd, "REJECTED:VALUE_RANGE:" + rpe.getMessage(), -1, cur.version(), -1);
            return CommandResult.rejected("OUT_OF_RANGE: " + rpe.getMessage());
        }

        builder.appliedBy(actor);
        builder.appliedAtNanos(System.nanoTime());
        return publish(builder.build(cur.version() + 1), cmd, cur);
    }

    /** CAS 发布 + 广播 + 审计（policyExecutor 单线程内；CAS 恒成功） */
    private CommandResult publish(RuntimePolicySnapshot next, PolicyCommand cmd, RuntimePolicySnapshot before) {
        if (!current.compareAndSet(before, next)) {
            audit(cmd, "CAS_FAILURE", -1, before.version(), -1);
            return CommandResult.rejected("CAS_FAILURE");
        }
        previous.set(before);
        runtime.publishPolicy(next);
        audit(cmd, "OK", next.version(), before.version(), next.version());
        return CommandResult.accepted("applied v" + next.version(), next);
    }

    /** 回滚（限 1 级；版本单调递增保证审计对账，§6.1 规则 4） */
    private CommandResult rollbackInternal(PolicyCommand cmd, RuntimePolicySnapshot cur, String actor) {
        final RuntimePolicySnapshot prev = previous.get();
        if (prev == null) {
            audit(cmd, "REJECTED:NO_PREVIOUS_SNAPSHOT", -1, cur.version(), -1);
            return CommandResult.rejected("NO_PREVIOUS_SNAPSHOT (only 1 level of rollback)");
        }
        final long nextVersion = cur.version() + 1; // 版本不回退
        final RuntimePolicySnapshot.Builder b = new RuntimePolicySnapshot.Builder(prev);
        b.appliedBy("ROLLBACK:" + actor);
        b.appliedAtNanos(System.nanoTime());
        final RuntimePolicySnapshot rolled = b.build(nextVersion);
        previous.set(null); // 限 1 级
        if (!current.compareAndSet(cur, rolled)) {
            return CommandResult.rejected("CAS_FAILURE");
        }
        runtime.publishPolicy(rolled);
        audit(cmd, "ROLLED_BACK", nextVersion, cur.version(), nextVersion);
        LOGGER.info("[PolicyController] Rolled back v{} → v{} by {}", cur.version(), nextVersion, actor);
        return CommandResult.rolledBack("rolled back v" + cur.version() + " → v" + nextVersion, rolled, prev);
    }

    /** 状态动作执行（APPLY_STATE 翻译；§4.2 状态动作汇总表） */
    private void runStateActions(PressureState prev, PressureState target) {
        final StateAction actions = buildStateActions();
        if (prev == PressureState.NORMAL && target != PressureState.NORMAL) {
            actions.onExitNormal(target);
        }
        if (target == PressureState.NORMAL) {
            actions.onEnterNormal();
        }
        if (target == PressureState.CPU_PRESSURE) {
            actions.onEnterCpuPressure();
        }
        if (prev == PressureState.CPU_PRESSURE) {
            actions.onExitCpuPressure();
        }
        if (target == PressureState.IO_PRESSURE) {
            actions.onEnterIoPressure();
        }
        if (prev == PressureState.IO_PRESSURE) {
            actions.onExitIoPressure();
        }
    }

    /** 状态动作实现（委托子控制器受控方法；全部在 policyExecutor 内执行） */
    private StateAction buildStateActions() {
        return new StateAction() {
            @Override
            public void onEnterNormal() {
                entity.setLowPriorityFactor(1.0);
                tick.setBackgroundTaskFactor(1.0);
                fanout.setPressureLimited(false);
                tick.setReserveClosed(false);
                io.setGlobalFlushPolicy(GlobalFlushPolicy.DEFAULTS);
                io.setIoPressureMode(false);
                chunk.resumeLowPrioritySaves();
                global.setBackpressureInternal(false);
            }

            @Override
            public void onExitNormal(PressureState next) {
                LOGGER.info("[PolicyController] State {} → {}", PressureState.NORMAL, next);
            }

            @Override
            public void onEnterCpuPressure() {
                // §4.2：低优先级实体（远离玩家/非战斗）预算 ×0.5；background ×0.5；fan-out 减半；reserve 关闭
                entity.setLowPriorityFactor(0.5);
                tick.setBackgroundTaskFactor(0.5);
                fanout.setPressureLimited(true);
                tick.setReserveClosed(true);
                global.setBackpressureInternal(true);
            }

            @Override
            public void onExitCpuPressure() {
                entity.setLowPriorityFactor(1.0);
                tick.setBackgroundTaskFactor(1.0);
                fanout.setPressureLimited(false);
                tick.setReserveClosed(false);
                global.setBackpressureInternal(false);
            }

            @Override
            public void onEnterIoPressure() {
                // §4.2：① 全局 flush 策略 LOW→DEFERRED、NORMAL→LOW；② 低优先级 chunk save 延后；③ 关键 flush 提高（合成公式已含脏度加权）
                io.setIoPressureMode(true);
                io.setGlobalFlushPolicy(GlobalFlushPolicy.IO_PRESSURE);
                chunk.pauseLowPrioritySaves();
                global.setBackpressureInternal(true);
            }

            @Override
            public void onExitIoPressure() {
                io.setIoPressureMode(false);
                io.setGlobalFlushPolicy(GlobalFlushPolicy.DEFAULTS);
                chunk.resumeLowPrioritySaves();
                global.setBackpressureInternal(false);
            }
        };
    }

    // ================= 插件 hint 裁决（D-22：插件只是申请，裁决权在本控制器） =================

    /**
     * 插件 flush 优先级申请裁决（IOController 委托；异步，policyExecutor 内执行）。
     * 权限 lmili.io.hint → 配额 60/min/plugin → 压力态降级（IO_PRESSURE 下 HIGH→NORMAL）。
     */
    public CompletableFuture<PluginHintResult> adjudicatePluginHint(
            long regionId, FlushPriority requested, String pluginId, String reason) {
        final String plugin = pluginId == null ? "unknown" : pluginId;
        return CompletableFuture.supplyAsync(() -> {
            final PluginHint hint = PersistencePriority.pluginRequest(plugin, regionId, requested, reason);
            final Map<String, String> params = Map.of(
                    "pluginId", plugin,
                    "regionId", String.valueOf(regionId),
                    "requested", String.valueOf(requested),
                    "reason", reason == null ? "" : reason);
            if (frozen.get()) {
                auditLog.appendHint(CommandSource.INTERNAL_MODULE, plugin, params, "REJECTED:FROZEN");
                return PluginHintResult.rejected(hint, "SHUTDOWN: policy frozen");
            }
            if (!hasPermission(plugin, "lmili.io.hint")) {
                auditLog.appendHint(CommandSource.INTERNAL_MODULE, plugin, params, "REJECTED:PERMISSION");
                return PluginHintResult.rejected(hint, "PERMISSION_DENIED: lmili.io.hint");
            }
            if (!rateLimit(hintRateWindows, plugin, PLUGIN_HINT_RATE_LIMIT_PER_MINUTE)) {
                auditLog.appendHint(CommandSource.INTERNAL_MODULE, plugin, params, "REJECTED:QUOTA");
                return PluginHintResult.rejected(hint, "QUOTA_EXCEEDED (max " + PLUGIN_HINT_RATE_LIMIT_PER_MINUTE + "/min)");
            }
            final PressureState st = current.get().pressure();
            if (st == PressureState.IO_PRESSURE && requested == FlushPriority.HIGH) {
                // 压力态降级（§4.4）：HIGH → NORMAL
                withCircuit("setFlushPriority", () -> io.setFlushPriority(regionId, FlushPriority.NORMAL));
                auditLog.appendHint(CommandSource.INTERNAL_MODULE, plugin, params, "DOWNGRADED:IO_PRESSURE");
                return PluginHintResult.downgraded(hint, FlushPriority.NORMAL, "IO_PRESSURE: HIGH downgraded to NORMAL");
            }
            if (st == PressureState.DEGRADED || st == PressureState.SHUTDOWN) {
                auditLog.appendHint(CommandSource.INTERNAL_MODULE, plugin, params, "REJECTED:" + st);
                return PluginHintResult.rejected(hint, st + ": hints disabled");
            }
            withCircuit("setFlushPriority", () -> io.setFlushPriority(regionId, requested));
            auditLog.appendHint(CommandSource.INTERNAL_MODULE, plugin, params, "GRANTED");
            return PluginHintResult.granted(hint, requested);
        }, policyExecutor);
    }

    // ================= 熔断（§6.2） =================

    private void withCircuit(String action, Runnable runnable) {
        final ActionCircuit circuit = circuits.computeIfAbsent(action, k -> new ActionCircuit());
        final long now = System.nanoTime();
        if (circuit.isOpen(now)) {
            LOGGER.warn("[PolicyController] circuit OPEN for {} (action skipped, last-known-good retained)", action);
            return;
        }
        try {
            runnable.run();
            circuit.recordSuccess();
        } catch (Throwable t) {
            if (circuit.recordFailure(now, circuitOpenDurationNanos)) {
                LOGGER.error("[PolicyController] circuit OPEN for {} after consecutive failures ({}ms cooldown)",
                        action, circuitOpenDurationNanos / 1_000_000, t);
            } else {
                LOGGER.warn("[PolicyController] action {} failed (consecutive={})", action, circuit.consecutiveFailures(), t);
            }
        }
    }

    /** 熔断状态（面板 v2 可视化） */
    public String circuitBreakerStatus() {
        if (circuits.isEmpty()) {
            return "ALL_CLOSED";
        }
        final StringBuilder sb = new StringBuilder();
        circuits.forEach((action, c) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(action).append('=').append(c.status());
        });
        return sb.toString();
    }

    /** 熔断时长覆盖（测试/调优；默认 5 分钟） */
    public void setCircuitOpenDurationNanos(long nanos) {
        if (nanos > 0) {
            this.circuitOpenDurationNanos = nanos;
        }
    }

    public long circuitOpenDurationNanos() {
        return circuitOpenDurationNanos;
    }

    // ================= 只读查询 =================

    public RuntimePolicySnapshot snapshot() {
        return current.get();
    }

    public RuntimePolicySnapshot previousSnapshot() {
        return previous.get();
    }

    public List<PolicyAuditEntry> auditEntries(int limit) {
        return auditLog.entries(limit);
    }

    public int auditCount() {
        return auditLog.size();
    }

    public boolean isFrozen() {
        return frozen.get();
    }

    public PressureStateMachine stateMachine() {
        return stateMachine;
    }

    /** 控制面板快照（StatusSubcommand 只读数据；命令层零 Controller 引用） */    public ControlPanelSnapshot panelSnapshot() {
        final MetricsController.PanelMetrics m = metrics != null ? metrics.panelMetrics() : null;
        final TickStatsSnapshot tickStats = tick != null ? tick.snapshot() : null;
        final RuntimePolicySnapshot snap = current.get();
        return new ControlPanelSnapshot(
                System.nanoTime(),
                global != null ? global.pressureState() : PressureState.NORMAL,
                m != null ? m.tps() : 0,
                m != null ? m.cpuLoad() : 0,
                m != null ? m.schedulerLoadPct() : 0,
                m != null ? m.ioQueueDepth() : 0,
                m != null ? m.ioP99Nanos() : 0,
                m != null ? m.totalRegions() : 0,
                m != null ? m.activeRegions() : 0,
                m != null ? m.workers() : 0,
                snap.ioWorkers(),
                snap.parallelTickEnabled(),
                snap.oLinearEnabled(),
                snap.entityThrottleEnabled(),
                snap.backpressureActive(),
                global != null ? global.degradeReason() : DegradeReason.NONE,
                snap.version(),
                circuitBreakerStatus(),
                global != null && global.isDivergenceDetected(),
                stateMachine.isLocked(),
                frozen.get(),
                auditLog.size(),
                tickStats != null ? tickStats.budgetRejections() : 0,
                tickStats != null ? tickStats.agingPromotions() : 0
        );
    }

    // ================= 冻结 / 控制周期 =================

    /**
     * 冻结（v2 关闭顺序 step 3）：快照锁定为当前值，拒绝新命令、拒绝内部模块策略变更、拒绝状态机迁移。
     * 冻结后常规受控通道完全关闭。
     */
    public void freeze() {
        if (frozen.compareAndSet(false, true)) {
            stateMachine.freeze();
            stopControlCycle();
            auditLog.append(CommandSource.STATE_MACHINE, "SYSTEM", null, Collections.emptyMap(),
                    "FROZEN", current.get().version(), -1, -1);
            LOGGER.info("[PolicyController] Policy frozen (v{})", current.get().version());
        }
    }

    /** 启动控制周期（10Hz；MiliRuntime.start 调用；PolicyController 为唯一驱动方） */
    public void startControlCycle(MiliGlobalController global, MetricsController metrics) {
        if (!controlRunning.compareAndSet(false, true)) {
            return;
        }
        this.global = global;
        this.metrics = metrics;
        controlScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (frozen.get()) {
                    return;
                }
                global.onControlCycle(metrics.collect());
            } catch (Throwable t) {
                LOGGER.error("[PolicyController] control cycle error", t);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        LOGGER.info("[PolicyController] Control cycle started (10Hz)");
    }

    public void stopControlCycle() {
        if (controlRunning.compareAndSet(true, false)) {
            controlScheduler.shutdownNow();
        }
    }

    public boolean isControlCycleRunning() {
        return controlRunning.get();
    }

    // ================= 装配（仅 MiliRuntime 调用） =================

    public void wireControllers(SchedulerController scheduler, TickController tick, EntityController entity,
                                ChunkController chunk, IOController io, FanoutController fanout) {
        this.scheduler = scheduler;
        this.tick = tick;
        this.entity = entity;
        this.chunk = chunk;
        this.io = io;
        this.fanout = fanout;
    }

    public void attachControlPlane(MiliGlobalController global, MetricsController metrics) {
        this.global = global;
        this.metrics = metrics;
    }

    /** 权限检查器（命令路径；MiliRuntime/RuntimeBootstrap 装配；null = fail-closed） */
    public void setPermissionChecker(BiPredicate<String, String> checker) {
        this.permissionChecker = checker;
    }

    /** 装配期初始化：发布初始快照（版本 1）并广播（MiliRuntime.start 调用） */
    public void initialize() {
        final RuntimePolicySnapshot init = current.get();
        if (init.version() == 0) {
            // derive(newVersion, mutate) 返回已带 newVersion 的 RuntimePolicySnapshot（不是 Builder，无须 .build）
            final RuntimePolicySnapshot v1 = init.derive(1L, x -> x);
            current.set(v1);
            runtime.publishPolicy(v1);
            auditLog.append(CommandSource.STATE_MACHINE, "SYSTEM", null, Collections.emptyMap(),
                    "INIT", 1L, 0L, 1L);
        }
    }

    /** 测试用：清空限频窗口（自检隔离） */
    public void resetRateLimitsForTest() {
        rateWindows.clear();
        hintRateWindows.clear();
    }

    // ================= 内部工具 =================

    private boolean hasPermission(String actor, String permission) {
        final BiPredicate<String, String> checker = permissionChecker;
        if (checker == null) {
            return false; // fail-closed
        }
        try {
            return checker.test(actor, permission);
        } catch (Throwable t) {
            LOGGER.warn("[PolicyController] permission check failed for {} ({})", actor, permission, t);
            return false;
        }
    }

    private static String requiredPermission(CommandAction action) {
        return switch (action) {
            case SET_SCHEDULER_MODE, SET_IO_WORKERS, SET_PARALLEL, SET_THROTTLE,
                 SET_CPU_BUDGET, SET_IO_BUDGET, SET_FANOUT, SET_TPS_TARGET,
                 SET_FLUSH_PRIORITY, DEGRADE, RESTORE, ROLLBACK -> "lmili.control.write";
            case APPLY_STATE -> null; // 仅 STATE_MACHINE 来源（受信）
        };
    }

    private static boolean rateLimit(ConcurrentHashMap<String, RateWindow> windows, String key, int limit) {
        return windows.computeIfAbsent(key, k -> new RateWindow()).tryAcquire(limit, 60_000L);
    }

    private void audit(PolicyCommand cmd, String result, long version, long before, long after) {
        auditLog.append(cmd.source(), cmd.actor() == null ? "UNKNOWN" : cmd.actor(), cmd.action(), cmd.params(),
                result, version, before, after);
    }

    // ---- 参数解析（服务端侧校验：语法错误/越界一律拒绝，不信任命令层钳制，§6.1 规则 2）----

    private String validateSyntax(PolicyCommand cmd) {
        final Map<String, String> params = cmd.params();
        return switch (cmd.action()) {
            case SET_SCHEDULER_MODE -> params.containsKey("schedulerWorkers") ? null : "missing schedulerWorkers";
            case SET_IO_WORKERS -> params.containsKey("ioWorkers") ? null : "missing ioWorkers";
            case SET_PARALLEL, SET_THROTTLE -> params.containsKey("enabled") ? null : "missing enabled";
            case SET_CPU_BUDGET -> params.containsKey("cpuBudgetPct") ? null : "missing cpuBudgetPct";
            case SET_IO_BUDGET -> params.containsKey("ioBudgetPct") ? null : "missing ioBudgetPct";
            case SET_FANOUT -> params.containsKey("fanOut") ? null : "missing fanOut";
            case SET_TPS_TARGET -> params.containsKey("tpsTarget") ? null : "missing tpsTarget";
            case SET_FLUSH_PRIORITY -> (params.containsKey("regionId") && params.containsKey("priority"))
                    ? null : "missing regionId/priority";
            case DEGRADE, RESTORE, ROLLBACK, APPLY_STATE -> null;
            default -> null;
        };
    }

    private static int parseRangeInt(PolicyCommand cmd, String key, int min, int max) throws RejectedParamException {
        final String raw = cmd.params().get(key);
        if (raw == null) {
            throw new RejectedParamException("missing " + key);
        }
        try {
            final int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                throw new RejectedParamException(key + "=" + v + " out of range [" + min + "," + max + "]");
            }
            return v;
        } catch (NumberFormatException nfe) {
            throw new RejectedParamException(key + "='" + raw + "' not an integer");
        }
    }

    private static double parseRangeDouble(PolicyCommand cmd, String key, double min, double max) throws RejectedParamException {
        final String raw = cmd.params().get(key);
        if (raw == null) {
            throw new RejectedParamException("missing " + key);
        }
        try {
            final double v = Double.parseDouble(raw.trim());
            if (v < min || v > max) {
                throw new RejectedParamException(key + "=" + v + " out of range [" + min + "," + max + "]");
            }
            return v;
        } catch (NumberFormatException nfe) {
            throw new RejectedParamException(key + "='" + raw + "' not a number");
        }
    }

    private static long parseLong(PolicyCommand cmd, String key) throws RejectedParamException {
        final String raw = cmd.params().get(key);
        if (raw == null) {
            throw new RejectedParamException("missing " + key);
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException nfe) {
            throw new RejectedParamException(key + "='" + raw + "' not a long");
        }
    }

    private static boolean parseBool(PolicyCommand cmd, String key) throws RejectedParamException {
        final String raw = cmd.params().get(key);
        if (raw == null) {
            throw new RejectedParamException("missing " + key);
        }
        return switch (raw.trim().toLowerCase()) {
            case "on", "true", "1" -> true;
            case "off", "false", "0" -> false;
            default -> throw new RejectedParamException(key + "='" + raw + "' not on/off");
        };
    }

    private static FlushPriority parseFlushPriority(PolicyCommand cmd, String key) throws RejectedParamException {
        final String raw = cmd.params().get(key);
        if (raw == null) {
            throw new RejectedParamException("missing " + key);
        }
        try {
            return FlushPriority.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException iae) {
            throw new RejectedParamException(key + "='" + raw + "' not a FlushPriority");
        }
    }

    private static PressureState parseState(PolicyCommand cmd, String key) throws RejectedParamException {
        final String raw = cmd.params().get(key);
        if (raw == null) {
            throw new RejectedParamException("missing " + key);
        }
        try {
            return PressureState.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException iae) {
            throw new RejectedParamException(key + "='" + raw + "' not a PressureState");
        }
    }

    private static DegradeReason parseDegradeReason(PolicyCommand cmd) {
        final String raw = cmd.params().get("reason");
        if (raw == null) {
            return DegradeReason.MANUAL;
        }
        try {
            return DegradeReason.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException iae) {
            return DegradeReason.MANUAL;
        }
    }

    private static final class RejectedParamException extends Exception {
        RejectedParamException(String message) {
            super(message);
        }
    }

    // ---- 限频窗口（并发安全，简单时间窗）----
    static final class RateWindow {
        private final ArrayDeque<Long> stamps = new ArrayDeque<>();

        synchronized boolean tryAcquire(int limit, long windowMillis) {
            final long now = System.currentTimeMillis();
            while (!stamps.isEmpty() && stamps.peekFirst() < now - windowMillis) {
                stamps.pollFirst();
            }
            if (stamps.size() >= limit) {
                return false;
            }
            stamps.addLast(now);
            return true;
        }
    }

    // ---- 熔断电路（Open → Half-Open → Closed）----
    static final class ActionCircuit {
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong openedUntilNanos = new AtomicLong(0);
        private final AtomicBoolean halfOpen = new AtomicBoolean(false);

        boolean isOpen(long now) {
            final long until = openedUntilNanos.get();
            if (until == 0) {
                return false;
            }
            if (now >= until) {
                // 到期 → Half-Open：允许单次试运行
                if (halfOpen.compareAndSet(false, true)) {
                    openedUntilNanos.set(0);
                    return false;
                }
                return true; // 已有试运行在途
            }
            return true;
        }

        /** @return true = 本次失败触发 Open */
        boolean recordFailure(long now, long openDurationNanos) {
            if (halfOpen.getAndSet(false)) {
                failures.set(3);
                openedUntilNanos.set(now + openDurationNanos);
                return true;
            }
            final int f = failures.incrementAndGet();
            if (f >= 3) {
                openedUntilNanos.set(now + openDurationNanos);
                return true;
            }
            return false;
        }

        void recordSuccess() {
            if (halfOpen.getAndSet(false)) {
                openedUntilNanos.set(0);
            }
            failures.set(0);
        }

        int consecutiveFailures() {
            return failures.get();
        }

        String status() {
            final long until = openedUntilNanos.get();
            if (until > System.nanoTime()) {
                return "OPEN";
            }
            if (halfOpen.get()) {
                return "HALF_OPEN";
            }
            return "CLOSED";
        }
    }
}
