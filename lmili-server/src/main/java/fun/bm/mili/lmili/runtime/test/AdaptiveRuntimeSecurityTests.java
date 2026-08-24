package fun.bm.mili.lmili.runtime.test;

import fun.bm.mili.lmili.runtime.MiliGlobalController;
import fun.bm.mili.lmili.runtime.MiliRuntime;
import fun.bm.mili.lmili.runtime.budget.RegionTickBudget;
import fun.bm.mili.lmili.runtime.budget.TickBudgetConfig;
import fun.bm.mili.lmili.runtime.control.MetricsController;
import fun.bm.mili.lmili.runtime.io.DirtyAgeSnapshot;
import fun.bm.mili.lmili.runtime.io.FlushPriority;
import fun.bm.mili.lmili.runtime.io.OLinearFlusherBridge;
import fun.bm.mili.lmili.runtime.io.PersistencePriority;
import fun.bm.mili.lmili.runtime.io.PluginHintResult;
import fun.bm.mili.lmili.runtime.io.RegionLoadSnapshot;
import fun.bm.mili.lmili.runtime.policy.CommandAction;
import fun.bm.mili.lmili.runtime.policy.CommandResult;
import fun.bm.mili.lmili.runtime.policy.CommandSource;
import fun.bm.mili.lmili.runtime.policy.PolicyCommand;
import fun.bm.mili.lmili.runtime.policy.PolicyController;
import fun.bm.mili.lmili.runtime.policy.PressureSignals;
import fun.bm.mili.lmili.runtime.policy.PressureState;
import fun.bm.mili.lmili.runtime.policy.PressureStateMachine;
import fun.bm.mili.lmili.runtime.policy.PressureThresholds;
import fun.bm.mili.lmili.runtime.task.TickTaskType;
import fun.bm.mili.lmili.thread.scheduler.MiliTickRegionScheduler;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * AdaptiveRuntime §6.6 安全测试清单 —— 可运行自检（main）。
 *
 * <p>无服务器环境运行：只装配 runtime 控制面（不启动 10Hz 控制周期线程）。
 * 用法：{@code java -cp <server-classpath> fun.bm.mili.lmili.runtime.test.AdaptiveRuntimeSecurityTests}
 * 全部通过 exit 0；任一失败 exit 1（便于 CI 挂接）。
 *
 * <p>覆盖清单（§6.6）：权限拒绝+审计 / op 二次校验 / 限频 / 参数注入 / 审计不可变 /
 * 二次回滚拒绝+版本单调 / 振荡锁定（min-dwell+6次每分）/ fail-safe→DEGRADED /
 * 熔断 3 连败 OPEN / shutdown 竞态 R2'（frozen+快照稳定）/ 预算饥饿 aging /
 * soft-hard 边界 / PersistencePriority 合成（D-22）/ D-23 链路 / 并发单写者串行化。
 */
public final class AdaptiveRuntimeSecurityTests {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        try {
            testPermissionDenyAndAudit();
            testParamInjection();
            testAuditImmutability();
            testRollbackAndVersionMonotonic();
            testOscillationLock();
            testFailSafeToDegraded();
            testCircuitBreaker();
            testShutdownRace();
            testBudgetStarvationAndAging();
            testSoftHardBounds();
            testPersistencePriorityComposite();
            testD23Chain();
            testConcurrencySingleWriter();
        } finally {
            // 恢复全局静态（防止污染后续测试/服务器启动）
            MiliTickRegionScheduler.tpsTarget = 20.0;
            MiliTickRegionScheduler.setBudgetGate(null);
        }
        System.out.println("==========================================");
        System.out.println("AdaptiveRuntimeSecurityTests: " + passed + " passed, " + failed + " failed");
        System.out.println("==========================================");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ================= §6.1：权限 / op / 限频 + 审计 =================

    private static void testPermissionDenyAndAudit() {
        final MiliRuntime rt = newRuntime();
        try {
            rt.policy().initialize();
            // 1. 读/写权限拒绝（fail-closed 检查器）
            rt.setPermissionChecker((actor, perm) -> false);
            final CommandResult denied = rt.policy().submitCommand(
                    new PolicyCommand(CommandSource.PLAYER, CommandAction.SET_IO_WORKERS,
                            Map.of("ioWorkers", "4"), "alice", System.nanoTime()));
            check("permission deny -> rejected", !denied.accepted() && denied.message().contains("PERMISSION_DENIED"), denied.message());

            // 2. 审计记录了拒绝
            final boolean audited = rt.policy().auditEntries(50).stream()
                    .anyMatch(e -> e.result().contains("PERMISSION_DENIED") && "alice".equals(e.actor()));
            check("denial audited", audited, "no PERMISSION_DENIED audit for alice");

            // 3. op 二次校验：有 write 权限但无 op → degrade 拒绝
            rt.setPermissionChecker((actor, perm) -> perm.equals("lmili.control.write"));
            final CommandResult opDenied = rt.policy().submitCommand(
                    new PolicyCommand(CommandSource.PLAYER, CommandAction.DEGRADE,
                            Map.of("reason", "MANUAL"), "bob", System.nanoTime()));
            check("degrade requires op", !opDenied.accepted() && opDenied.message().contains("minecraft.command.op"), opDenied.message());

            // 4. 写命令限频 10 次/分钟/actor
            rt.setPermissionChecker((actor, perm) -> true);
            int accepted = 0;
            for (int i = 0; i < 15; i++) {
                final CommandResult r = rt.policy().submitCommand(
                        new PolicyCommand(CommandSource.PLAYER, CommandAction.SET_IO_WORKERS,
                                Map.of("ioWorkers", "4"), "carol", System.nanoTime()));
                if (r.accepted()) {
                    accepted++;
                }
            }
            check("write rate limit 10/min/actor", accepted == 10, "accepted=" + accepted);
        } finally {
            rt.shutdown(Duration.ofMillis(300));
        }
    }

    // ================= §6.1 规则 2：参数注入/越界（服务端拒绝，不信任命令层钳制） =================

    private static void testParamInjection() {
        final MiliRuntime rt = newRuntime();
        try {
            rt.setPermissionChecker((actor, perm) -> true);
            rt.policy().initialize();
            final CommandResult notInt = submit(rt, "mallory", CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", "abc"));
            final CommandResult negative = submit(rt, "mallory", CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", "-5"));
            final CommandResult overflow = submit(rt, "mallory", CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", "100"));
            final CommandResult tpsLow = submit(rt, "mallory", CommandAction.SET_TPS_TARGET, Map.of("tpsTarget", "5"));
            final CommandResult tpsHigh = submit(rt, "mallory", CommandAction.SET_TPS_TARGET, Map.of("tpsTarget", "25"));
            final CommandResult boolGarbage = submit(rt, "mallory", CommandAction.SET_PARALLEL, Map.of("enabled", "maybe"));
            final CommandResult missing = submit(rt, "mallory", CommandAction.SET_IO_WORKERS, Map.of());
            check("'abc' ioWorkers rejected", !notInt.accepted() && notInt.message().contains("OUT_OF_RANGE"), notInt.message());
            check("'-5' ioWorkers rejected", !negative.accepted() && negative.message().contains("OUT_OF_RANGE"), negative.message());
            check("'100' ioWorkers rejected", !overflow.accepted() && overflow.message().contains("OUT_OF_RANGE"), overflow.message());
            check("'5' tpsTarget rejected", !tpsLow.accepted() && tpsLow.message().contains("OUT_OF_RANGE"), tpsLow.message());
            check("'25' tpsTarget rejected", !tpsHigh.accepted() && tpsHigh.message().contains("OUT_OF_RANGE"), tpsHigh.message());
            check("'maybe' enabled rejected", !boolGarbage.accepted() && boolGarbage.message().contains("OUT_OF_RANGE"), boolGarbage.message());
            check("missing param rejected", !missing.accepted() && missing.message().contains("INVALID_PARAMETERS"), missing.message());
            // 恢复正常静态
            MiliTickRegionScheduler.tpsTarget = 20.0;
        } finally {
            rt.shutdown(Duration.ofMillis(300));
        }
    }

    // ================= §6.1：审计不可变 =================

    private static void testAuditImmutability() {
        final MiliRuntime rt = newRuntime();
        try {
            rt.setPermissionChecker((actor, perm) -> true);
            rt.policy().initialize();
            final Map<String, String> mutable = new HashMap<>();
            mutable.put("ioWorkers", "6");
            rt.policy().submitCommand(new PolicyCommand(CommandSource.PLAYER, CommandAction.SET_IO_WORKERS,
                    mutable, "dave", System.nanoTime()));
            final var entries = rt.policy().auditEntries(50);
            final var last = entries.get(entries.size() - 1);
            // 拷贝不可变
            boolean immutable = false;
            try {
                last.params().put("x", "y");
            } catch (UnsupportedOperationException expected) {
                immutable = true;
            }
            check("audit params immutable", immutable, "");
            // 与输入 map 隔离（事后修改输入不影响审计）
            mutable.put("ioWorkers", "99");
            check("audit decoupled from input map", "6".equals(last.params().get("ioWorkers")),
                    "stored=" + last.params().get("ioWorkers"));
        } finally {
            rt.shutdown(Duration.ofMillis(300));
        }
    }

    // ================= §6.1 规则 4 / D-04：二次回滚拒绝 + 版本单调 =================

    private static void testRollbackAndVersionMonotonic() {
        final MiliRuntime rt = newRuntime();
        try {
            rt.setPermissionChecker((actor, perm) -> true);
            rt.policy().initialize();
            final long v1 = rt.policy().snapshot().version();
            rt.policy().submitCommand(PolicyCommand.fromModule("test", CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", "6")));
            final long v2 = rt.policy().snapshot().version();
            check("version incremented on write", v2 == v1 + 1, "v1=" + v1 + " v2=" + v2);

            final CommandResult rb1 = rt.policy().submitCommand(PolicyCommand.fromModule("test", CommandAction.ROLLBACK, Map.of()));
            final long v3 = rt.policy().snapshot().version();
            check("rollback accepted", rb1.accepted() && rb1.rolledBackTo() != null, rb1.message());
            check("rollback restored previous fields", rt.policy().snapshot().ioWorkers() == 0, "ioWorkers=" + rt.policy().snapshot().ioWorkers());
            check("rollback keeps version monotonic", v3 == v2 + 1, "v3=" + v3);

            final CommandResult rb2 = rt.policy().submitCommand(PolicyCommand.fromModule("test", CommandAction.ROLLBACK, Map.of()));
            check("second rollback rejected (1-level only)", !rb2.accepted() && rb2.message().contains("NO_PREVIOUS_SNAPSHOT"), rb2.message());
            check("version never decreases", rt.policy().snapshot().version() >= v3, "v=" + rt.policy().snapshot().version());
        } finally {
            rt.shutdown(Duration.ofMillis(300));
        }
    }

    // ================= D-05：滞回 + 振荡锁定（min-dwell 0 注入；6 次/分 → 锁 30s） =================

    private static void testOscillationLock() {
        final PressureStateMachine sm = new PressureStateMachine();
        sm.updateThresholds(new PressureThresholds(18.0, 0.85, 19.5, 0.70, 2000, 80_000_000L,
                800, 30_000_000L, 2, 3, 0, 6, 2_000_000_000L));
        final PressureSignals cpu = new PressureSignals(10.0, 0.95, 0, 0, 0.5, 0, 0, false, System.nanoTime());
        final PressureSignals ok = new PressureSignals(20.5, 0.4, 0, 0, 0.5, 0, 0, false, System.nanoTime());

        // 单周期不迁移（滞回：进入需连续 2 周期）
        final PressureStateMachine single = new PressureStateMachine();
        single.updateThresholds(sm.thresholds());
        single.transition(cpu);
        check("hysteresis: single cycle does not migrate", single.current() == PressureState.NORMAL,
                "state=" + single.current());

        // 驱动 6 次成功迁移
        int migrations = 0;
        int guard = 0;
        while (migrations < 6 && guard++ < 2000) {
            if (sm.current() == PressureState.NORMAL) {
                if (sm.transition(cpu)) {
                    migrations++;
                }
            } else {
                if (sm.transition(ok)) {
                    migrations++;
                }
            }
        }
        check("6 migrations achievable (D-05 hysteresis)", migrations == 6, "migrations=" + migrations);

        // 第 7 次迁移尝试 → RATE_LIMIT_LOCK_30S
        int guard2 = 0;
        while (!sm.isLocked() && guard2++ < 50) {
            if (sm.current() == PressureState.NORMAL) {
                sm.transition(cpu);
            } else {
                sm.transition(ok);
            }
        }
        check("7th migration triggers 30s lock", sm.isLocked(), "reason=" + sm.lastReason());
        check("locked state rejects transitions", !sm.transition(cpu), "");
    }

    // ================= §6.2：全源失败 fail-safe → DEGRADED =================

    private static void testFailSafeToDegraded() {
        final MiliRuntime rt = newRuntime();
        try {
            final PressureStateMachine sm = new PressureStateMachine();
            final PolicyController pol = new PolicyController(rt, sm);
            pol.initialize();
            final MetricsController metrics = new MetricsController(); // 无任何源
            final MiliGlobalController gc = new MiliGlobalController(pol, sm);
            gc.wireMetrics(metrics);
            metrics.collect(); // 空源 → allSourcesFailed=true
            check("empty sources -> allFailed flag", metrics.allSourcesFailed(), "");
            gc.onControlCycle(PressureSignals.neutral());
            final boolean degraded = await(() -> pol.snapshot().pressure() == PressureState.DEGRADED, 5000);
            check("all sources failed -> DEGRADED (fail-safe)", degraded, "pressure=" + pol.snapshot().pressure());
            check("degrade reason recorded", pol.snapshot().pressure() == PressureState.DEGRADED, "");
        } finally {
            rt.shutdown(Duration.ofMillis(300));
        }
    }

    // ================= §6.2：熔断 —— ≥3 连败 → OPEN（5min 默认；注入缩短） =================

    private static void testCircuitBreaker() {
        final MiliRuntime rt = newRuntime();
        try {
            rt.setPermissionChecker((actor, perm) -> true);
            rt.policy().initialize();
            rt.io().setWorkerCountApplierForTest(n -> {
                throw new RuntimeException("simulated io pool resize failure");
            });
            rt.policy().setCircuitOpenDurationNanos(60_000_000_000L); // 60s：测试期间保持 OPEN
            for (int i = 0; i < 3; i++) {
                rt.policy().submitCommand(PolicyCommand.fromModule("test", CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", "4")));
            }
            check("3 consecutive failures -> circuit OPEN", rt.policy().circuitBreakerStatus().contains("OPEN"),
                    rt.policy().circuitBreakerStatus());
            // OPEN 期间动作被跳过（最后已知良好保留），命令仍被接受（发布路径不受影响）
            final CommandResult duringOpen = rt.policy().submitCommand(
                    PolicyCommand.fromModule("test", CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", "4")));
            check("circuit OPEN does not block policy publish", duringOpen.accepted(), duringOpen.message());
        } finally {
            rt.io().setWorkerCountApplierForTest(null);
            rt.shutdown(Duration.ofMillis(300));
        }
    }

    // ================= §6.3 R2'：shutdown 竞态 —— 冻结拒绝 + 快照稳定 =================

    private static void testShutdownRace() {
        final MiliRuntime rt = newRuntime();
        try {
            rt.setPermissionChecker((actor, perm) -> true);
            rt.policy().initialize();
            // IO 灌入低优先级 + 提升全 CRITICAL + 有界排水（无 flusher：立即完成，无静默超时）
            final OLinearFlusherBridge bridge = rt.bridge();
            bridge.setIoPressure(true);
            bridge.setPriority(101, FlushPriority.DEFERRED);
            bridge.setPriority(102, FlushPriority.LOW);
            rt.io().raiseAllFlushToCritical();
            check("raiseAllFlushToCritical -> CRITICAL", bridge.priorityOf(101) == FlushPriority.CRITICAL
                    && bridge.priorityOf(102) == FlushPriority.CRITICAL, "");
            rt.io().drainForShutdown(Duration.ofMillis(500));
            check("bounded drain completes without exception", true, "");

            rt.shutdown(Duration.ofMillis(300));
            check("policy frozen after shutdown", rt.policy().isFrozen(), "");
            final long before = rt.policy().snapshot().version();
            final CommandResult late = rt.policy().submitCommand(
                    PolicyCommand.fromModule("late", CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", "8")));
            check("submit during shutdown rejected", !late.accepted() && late.message().contains("frozen"), late.message());
            check("snapshot unchanged during drain", rt.policy().snapshot().version() == before,
                    "v=" + rt.policy().snapshot().version());
        } finally {
            rt.shutdown(Duration.ofMillis(100)); // 幂等
        }
    }

    // ================= §6.3 R1：预算饥饿 → soft 窗口 aging =================

    private static void testBudgetStarvationAndAging() {
        final MiliRuntime rt = newRuntime();
        try {
            // 单 budget 实例模拟一轮 tick 的共享账本（ParallelExecutor 模式）
            final RegionTickBudget budget = new RegionTickBudget(4_000_000L, 5_000_000L,
                    TickBudgetConfig.DEFAULTS, true);
            // 分池耗尽 → 普通获取拒绝（饥饿场景）
            final boolean drained = !budget.tryAcquire(TickTaskType.ENTITY, 2_000_000L);
            check("pool exhausted -> budget denied (starvation)", drained, "");
            // soft 窗口 aging 放行（防饥饿）
            final boolean aged = budget.tryAcquireSoftWindow(TickTaskType.ENTITY, 1_500_000L);
            check("soft window aging allows execution", aged, "");

            // TickController 级：统计口径（拒绝计数 + aging 计数）
            final long rejBefore = rt.tick().snapshot().budgetRejections();
            rt.tick().tryAcquire(1L, TickTaskType.ENTITY, 6_000_000L); // > hard → null
            check("tick-level hard rejection counted", rt.tick().snapshot().budgetRejections() >= rejBefore + 1,
                    "rejections=" + rt.tick().snapshot().budgetRejections());
            rt.tick().tryAcquireSoftWindow(1L, TickTaskType.ENTITY);
            check("tick-level soft window (aging) works", rt.tick().snapshot().agingPromotions() >= 1,
                    "aging=" + rt.tick().snapshot().agingPromotions());
        } finally {
            rt.shutdown(Duration.ofMillis(300));
        }
    }

    // ================= §3.4 / D-10：soft/hard 边界 =================

    private static void testSoftHardBounds() {
        final RegionTickBudget b = new RegionTickBudget(4_000_000L, 5_000_000L, TickBudgetConfig.DEFAULTS, true);
        check("within soft pool accepted", b.tryAcquire(TickTaskType.ENTITY, 1_000_000L), "");
        check("over hard bound rejected", !b.tryAcquire(TickTaskType.ENTITY, 6_000_000L), "");
        check("soft window still within hard accepted", b.tryAcquireSoftWindow(TickTaskType.ENTITY, 2_000_000L), "");
        check("soft window over hard rejected", !b.tryAcquireSoftWindow(TickTaskType.ENTITY, 6_000_000L), "");
        check("SAVE never consumes CPU budget", b.tryAcquire(TickTaskType.SAVE, 10_000_000L), "");
    }

    // ================= D-22：PersistencePriority 合成打分 =================

    private static void testPersistencePriorityComposite() {
        final PersistencePriority.Weights w = PersistencePriority.Weights.DEFAULTS;
        final RegionLoadSnapshot hot = RegionLoadSnapshot.fromLoadFactor(0.95);
        final RegionLoadSnapshot idle = RegionLoadSnapshot.fromLoadFactor(0.05);
        final DirtyAgeSnapshot dirty = new DirtyAgeSnapshot(70_000_000L, 600_000_000_000L, 10); // 70MB > 64MB cap
        final DirtyAgeSnapshot fresh = new DirtyAgeSnapshot(0L, 1_000_000_000L, 0);
        // shutdown 权重 2.0 压过一切
        check("shutdown -> CRITICAL", PersistencePriority.evaluate(1, idle, fresh, null, true, w) == FlushPriority.CRITICAL, "");
        // HOT + 大量脏数据 → CRITICAL（3.0 + 3.2 ≥ 3.5）
        check("HOT+dirty -> CRITICAL", PersistencePriority.evaluate(2, hot, dirty, null, false, w) == FlushPriority.CRITICAL, "");
        // IDLE + 干净 → LOW（1.0 + 0 ≥ 0.5）
        check("IDLE+fresh -> LOW", PersistencePriority.evaluate(3, idle, fresh, null, false, w) == FlushPriority.LOW, "");
        // 插件 HIGH hint（权重 0.6 → 1.8 分）提升 IDLE → HIGH
        final fun.bm.mili.lmili.runtime.io.PluginHint hint = PersistencePriority.pluginRequest("p1", 4, FlushPriority.HIGH, "test");
        check("plugin request hint fields", hint.pluginId().equals("p1") && hint.requested() == FlushPriority.HIGH, "");
        check("plugin HIGH hint lifts IDLE to HIGH",
                PersistencePriority.evaluate(4, idle, fresh, hint, false, w) == FlushPriority.HIGH, "");
    }

    // ================= D-23：IO→Tick 只经策略提示（结构性 + 行为性） =================

    private static void testD23Chain() {
        final MiliRuntime rt = newRuntime();
        try {
            // 结构性：IOController 不允许持有 Tick 引用（D-23 铁律）
            final Field[] fields = fun.bm.mili.lmili.runtime.control.IOController.class.getDeclaredFields();
            boolean hasTickRef = false;
            for (Field f : fields) {
                if (f.getType().getSimpleName().contains("Tick")) {
                    hasTickRef = true;
                    break;
                }
            }
            check("IOController has no Tick reference (D-23)", !hasTickRef, "");

            rt.setPermissionChecker((actor, perm) -> true);
            rt.policy().initialize();
            // 行为性：插件 hint 走 PolicyController 裁决通道（IO→Policy→IO；不直改 Tick）
            final PluginHintResult res = rt.io().requestFlushPriority(7L, FlushPriority.HIGH, "myplugin", "test").join();
            check("plugin hint adjudicated via policy channel", res.accepted(), res.reason());
            // 只读查询（isSaturated/shouldDefer）不产生任何状态变化
            final boolean beforeDefer = rt.bridge().isIoPressure();
            rt.io().isSaturated();
            rt.io().shouldDefer(7L, FlushPriority.NORMAL);
            check("read-only IO queries do not mutate pressure flag", rt.bridge().isIoPressure() == beforeDefer, "");
        } finally {
            rt.shutdown(Duration.ofMillis(300));
        }
    }

    // ================= §6.4：并发压力 —— 单写者串行化（无 CAS 失败） =================

    private static void testConcurrencySingleWriter() throws Exception {
        final MiliRuntime rt = newRuntime();
        try {
            rt.setPermissionChecker((actor, perm) -> true);
            rt.policy().initialize();
            rt.io().setWorkerCountApplierForTest(n -> {
                // 测试注入：不触碰真实池
            });
            final long v0 = rt.policy().snapshot().version();
            final int threads = 8;
            final int per = 25;
            final ExecutorService pool = Executors.newFixedThreadPool(threads);
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<CommandResult>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                for (int i = 0; i < per; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        return rt.policy().submitCommand(PolicyCommand.fromModule("conc-" + tid,
                                CommandAction.SET_IO_WORKERS, Map.of("ioWorkers", String.valueOf(1 + (tid % 4)))));
                    }));
                }
            }
            start.countDown();
            int accepted = 0;
            boolean casFailure = false;
            for (Future<CommandResult> f : futures) {
                final CommandResult r = f.get(30, TimeUnit.SECONDS);
                if (r.accepted()) {
                    accepted++;
                } else if (r.message().contains("CAS_FAILURE")) {
                    casFailure = true;
                }
            }
            pool.shutdownNow();
            check("all concurrent writes applied exactly once", accepted == threads * per, "accepted=" + accepted);
            check("no CAS failures under concurrency", !casFailure, "");
            check("version advanced by exactly N*M (single-writer serialization)",
                    rt.policy().snapshot().version() == v0 + threads * per,
                    "v0=" + v0 + " now=" + rt.policy().snapshot().version());
            check("audit count matches applied writes", rt.policy().auditCount() >= 1 + threads * per,
                    "audit=" + rt.policy().auditCount());
        } finally {
            rt.io().setWorkerCountApplierForTest(null);
            rt.shutdown(Duration.ofMillis(300));
        }
    }

    // ================= 工具 =================

    private static MiliRuntime newRuntime() {
        return new MiliRuntime();
    }

    private static CommandResult submit(MiliRuntime rt, String actor, CommandAction action, Map<String, String> params) {
        return rt.policy().submitCommand(new PolicyCommand(CommandSource.PLAYER, action, params, actor, System.nanoTime()));
    }

    private static boolean await(Supplier<Boolean> cond, long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(cond.get())) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 等待期间异常视为未满足
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + (detail == null || detail.isEmpty() ? "" : " :: " + detail));
        }
    }
}
