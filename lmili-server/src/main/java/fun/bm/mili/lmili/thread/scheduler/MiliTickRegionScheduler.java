package fun.bm.mili.lmili.thread.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import fun.bm.mili.lmili.thread.scheduler.api.TaskHandle;
import fun.bm.mili.lmili.thread.scheduler.execute.TaskScheduleState;
import io.papermc.paper.threadedregions.*;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mili Tick Region Scheduler —— Folia API 适配器（R2-07 双 Worker runtime 合并 + 单 runtime 修复）。
 *
 * <p><b>R2-07 修复</b>：此类现在是纯 Folia API 适配器，不再拥有独立的 Worker 线程池。
 * 所有 tick 执行委派给共享 {@link MiliScheduler} 的 Worker Pool
 * （{@link MiliTickThread} 实例作为 carrier 线程）。</p>
 *
 * <p><b>单 runtime 修复</b>：scheduler 不再私有 —— 通过 {@link MiliSchedulerHolder} 取得
 * 全 Mili 唯一的 MiliScheduler 实例。这意味着 Folia 区域 tick、公共 API、
 * region 内并行任务、DAG 系统全部共享同一套 Worker 池和 WorkStealing 调度。</p>
 *
 * <h3>Tick 调度流程</h3>
 * <pre>
 * scheduleRegion(handle)          ← Folia API 入口
 *     │
 *     ▼
 * TickTask wrapper (R2-08 dedup)  ← TaskScheduleState IDLE→QUEUED CAS
 *     │
 *     ▼
 * MiliScheduler.submit(RegionTask)
 *     │
 *     ▼
 * WorkStealingCoordinator → SchedulerWorker (MiliTickThread)
 *     │
 *     ▼
 * handle.runTick() → tickRegion() → MinecraftServer.tickServer()
 *     │
 *     ▼
 * scheduleDelayed(nextTick) → 重新进入队列（避免忙等待）
 * </pre>
 */
public final class MiliTickRegionScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long TIME_BETWEEN_TICKS_MS = 1000L / 20; // 50ms
    private static final long GLOBAL_TICK_REGION_ID = -1L;

    public static final int TICK_RATE = 20;
    public static long TIME_BETWEEN_TICKS = 1_000_000_000L / TICK_RATE; // ns

    // Mili start - AdaptiveRuntime §5.3 (D-14)：TPS 治理目标（只读字段，禁止写 TIME_BETWEEN_TICKS）。
    // TickController.applyTpsTarget() 只写本字段；本类的 tick 循环内部自行安全读取（volatile）。
    // 默认 20.0 → 1000/20 = 50ms，与 TIME_BETWEEN_TICKS_MS 完全一致（未治理时零行为变化）。
    public static volatile double tpsTarget = 20.0;
    // Mili end

    /**
     * 预算闸门（D-10 / §5.3）：TickTask 包装层在 scheduler.submit(...) 前调用
     * {@link #tryAcquire(long, int)}；null = 闸门关闭（默认 off，零行为变化）。
     *
     * <p>返回的 {@link AutoCloseable} 是预算租约（{@code BudgetLease}），任务执行完必须 close()。
     * {@code tickTaskTypeOrdinal} 为 {@code TickTaskType} 的 ordinal —— 本类不 import 运行期
     * 类型，由装配方（RuntimeBootstrap/MiliRuntime）做 ordinal → 类型映射。
     */
    @FunctionalInterface
    public interface RegionTickBudgetGate {
        /**
         * 提交前预算申请（非阻塞；预算不足返回 null，调用方必须退避/aging，§6.3 R1）。
         *
         * @return 预算租约（任务执行后 close），或 null（预算不足）
         */
        AutoCloseable tryAcquire(long regionId, int tickTaskTypeOrdinal);
    }

    private static volatile RegionTickBudgetGate BUDGET_GATE;

    /** 装配预算闸门（仅 MiliRuntime/RuntimeBootstrap 装配期调用；null = 关闭闸门） */
    public static void setBudgetGate(@org.jetbrains.annotations.Nullable RegionTickBudgetGate gate) {
        BUDGET_GATE = gate;
    }

    /** 当前预算闸门（null = 关闭） */
    @org.jetbrains.annotations.Nullable
    public static RegionTickBudgetGate budgetGate() {
        return BUDGET_GATE;
    }

    // ---- 预算不足退避（非阻塞；5ms → 50ms，§5.3）----
    private static final long BUDGET_RETRY_INITIAL_MS = 5L;
    private static final long BUDGET_RETRY_MAX_MS = 50L;
    /** 连续预算被拒 3 次 → aging 放行（防饥饿，§6.3 R1） */
    private static final int BUDGET_AGING_THRESHOLD = 3;

    // LMili watchdog - 使用 LMili 实现的 watchdog
    public static final io.papermc.paper.threadedregions.LMiliWatchdogThread WATCHDOG_THREAD = new io.papermc.paper.threadedregions.LMiliWatchdogThread();
    static {
        WATCHDOG_THREAD.start();
    }

    // ---- 统一 MiliScheduler runtime（修复双 runtime 并存）----
    //
    // 此 scheduler 现在通过 {@link MiliSchedulerHolder} 持有，整个 Mili（区域 tick +
    // 公共 API + DAG 系统）共享同一个 MiliScheduler 实例。
    private final MiliScheduler scheduler;
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /**
     * 获取共享的 MiliScheduler（Folia 区域 tick 入口）。
     *
     * <p>仅在 {@link #MiliTickRegionScheduler(int)} 构造后可用；之前返回 null。</p>
     */
    public static MiliScheduler getSharedScheduler() {
        return MiliSchedulerHolder.get();
    }

    // ---- R4-修复: regionId → TickTask ----
    // 使用 ConcurrentHashMap 以 regionId 为键，替代之前的 IdentityHashMap + synchronizedMap 方案。
    // regionId 是 region 的稳定标识符（TickRegionData.ID_GENERATOR 生成，全局唯一），
    // 比 handle identity 更适合做键：线程安全（无需外部同步）、避免 identity 泄漏导致的
    // 无限增长问题。
    private static final ConcurrentHashMap<Long, TickTask> REGISTRY = new ConcurrentHashMap<>();

    /**
     * P1-1 / C4：region 销毁时清理静态 REGISTRY entry。
     * 防止静态 map 在长时间运行后无限累积（每个被销毁的 region 都曾留下一个 TickTask）。
     */
    public static void onRegionDestroyed(long regionId) {
        REGISTRY.remove(regionId);
    }

    // ---- R3-FIX: region 暂时不可获取时的重试退避（避免触发服务器关闭） ----
    /** 重试退避的初始延迟（ms） */
    private static final long RETRY_BACKOFF_INITIAL_MS = 5L;
    /** 重试退避的最大延迟（ms） */
    private static final long RETRY_BACKOFF_MAX_MS = 50L;
    /** 单次 tick 任务的最大连续重试次数（超过后放弃本次 tick） */
    private static final int MAX_CONSECUTIVE_RETRIES = 20;

    /**
     * 创建 Mili Tick Region Scheduler。
     *
     * <p>R2-07: 使用 tickThreads=true 创建 MiliTickThread worker（统壹 Worker Pool）。</p>
     *
     * <p><b>修复双 runtime 并存</b>：scheduler 不再私有构造，而是通过
     * {@link MiliSchedulerHolder#getOrCreate(int, boolean, String)} 取得共享单例。
     * 这确保 Folia 区域 tick、公共 API、DAG 系统全部走同一个 MiliScheduler runtime。</p>
     *
     * @param threadCount worker 线程数
     */
    public MiliTickRegionScheduler(final int threadCount) {
        // 至少使用2个worker线程，确保global tick不会被region tick阻塞
        final int workerCount = Math.max(2, threadCount);

        // 通过 Holder 获取/创建共享 MiliScheduler（worker 是 MiliTickThread）
        this.scheduler = MiliSchedulerHolder.getOrCreate(workerCount, true, "MiliTickRegionScheduler");

        LOGGER.info("[MiliTickRegionScheduler] Started with shared MiliScheduler runtime ({} workers)",
                workerCount);
    }

    /**
     * 调度一个 region 进行 tick。
     *
     * <p>Folia API 入口 —— 将 handle 包装为 RegionTask 并提交到统壹调度器。</p>
     *
     * @param handle region 的调度句柄
     */
    public void scheduleRegion(final TickRegionScheduler.RegionScheduleHandle handle) {
        if (halted.get()) return;
        if (handle.isMarkedAsNonSchedulable()) return; // 已取消的 handle 不应再被调度

        final TickTask task = computeTask(handle);
        // R2-08: CAS IDLE→QUEUED 防止重复入队
        if (!task.state.tryMarkQueued()) {
            return; // 已在 QUEUED/RUNNING 状态
        }

        // Mili start - AdaptiveRuntime §5.3 (D-10)：预算闸门（提交前检查；默认 off）。
        // 预算不足 → 非阻塞退避（5ms→50ms）重试；连续被拒 3 次 aging 放行（防饥饿）。
        final RegionTickBudgetGate gate = BUDGET_GATE;
        AutoCloseable lease = null;
        if (gate != null) {
            try {
                lease = gate.tryAcquire(task.regionId(), 0); // ordinal 0 = ENTITY（region tick 视为 entity 面）
            } catch (Throwable t) {
                // 闸门异常 fail-open（§6.3 R1：闸门误拒导致 region 饥饿 → 放行）
                LOGGER.warn("[MiliTickRegionScheduler] budget gate failed (fail-open)", t);
                lease = null;
            }
            if (lease == null) {
                if (task.budgetRejected()) {
                    // aging：连续被拒 3 次 → 放行（优先级上调一档的等价实现：本 tick 不再拦）
                    task.resetBudgetRejections();
                } else {
                    task.state.forceCancel(); // 归还 IDLE，供延迟重试重新 CAS
                    task.resubmitBudgetRetry();
                    return;
                }
            }
        }
        // Mili end

        try {
            // R4-修复: getAndSet 原子交换 —— 旧 handle（若有）不会被新 handle 覆盖丢失
            task.taskHandle.set(scheduler.submit(task.toRegionTask(lease)));
            // Note: this is a system-level tick task — it does NOT go through
            // PluginSchedulerBridge because it is a region-tick (Folia-level)
            // task, not a plugin task. The owner is implicitly lmili.system.
            // Per V2 §18, system tasks are exempt from per-plugin lifecycle
            // checks and tracking.
        } catch (Exception e) {
            // 提交失败，状态仍是 QUEUED（不是 RUNNING），tryMarkIdle() 无法工作，使用 forceCancel() 清理
            task.state.forceCancel();
            closeQuietly(lease);
            LOGGER.warn("[MiliTickRegionScheduler] Failed to submit tick task for region #{}",
                    handle.region != null ? handle.region.id : -1, e);
        }
    }

    /** 预算租约归还（幂等；失败静默） */
    private static void closeQuietly(final AutoCloseable lease) {
        if (lease != null) {
            try {
                lease.close();
            } catch (Throwable ignored) {
                // 归还失败仅记录（预算泄漏由 BudgetLease 幂等 close 兜底）
            }
        }
    }

    /**
     * 取消 region 的调度。
     *
     * <p>注意：此处只清理 handle → TickTask 映射与 delayed future。
     * region 在 merge 时会短暂进入 inactive，但其任务队列会被转移到目标 region，
     * 因此<b>不能</b>在此注销共享调度器中的 region 槽位 —— 该清理
     * 只在 {@link io.papermc.paper.threadedregions.TickRegions#onRegionDestroy}（region 真正销毁）时进行。</p>
     */
    public void descheduleRegion(final TickRegionScheduler.RegionScheduleHandle handle) {
        handle.markNonSchedulable();
        final long rid = handle.region != null ? handle.region.id : GLOBAL_TICK_REGION_ID;
        final TickTask task = REGISTRY.remove(rid);
        if (task != null) {
            task.state.forceCancel();
            final TaskHandle th = task.taskHandle.getAndSet(null);
            if (th != null) {
                th.cancel();
            }
        }
    }

    /**
     * 通知调度器 region 有中间任务需要执行。
     */
    public void setHasTasks(final TickRegionScheduler.RegionScheduleHandle handle) {
        // 统壹模式下，只需要重新提交（dedup 机制防止重复）
        scheduleRegion(handle);
    }

    /**
     * 停止调度器。
     *
     * <p>R2-07: 委派给共享 MiliScheduler 统一关闭（不再需要独立的 worker 停止逻辑）。</p>
     *
     * <p><b>修复双 runtime 并存</b>：halt 时由本类（唯一所有者）调用
     * {@link MiliSchedulerHolder#shutdown(String, long)}，避免与其它子系统重复关闭同一 scheduler。
     * 调用方应保证：halt() 调用前所有其它子系统（公共 API/DAG）已停止提交任务。</p>
     */
    public void halt() {
        if (!halted.compareAndSet(false, true)) return;
        LOGGER.info("[MiliTickRegionScheduler] Halting...");

        // 标记所有已知 handle 为不可调度
        int cancelledCount = 0;
        for (final TickTask task : REGISTRY.values()) {
            task.handle.markNonSchedulable();
            task.state.forceCancel();
            final TaskHandle th = task.taskHandle.getAndSet(null);
            if (th != null) {
                th.cancel();
            }
            cancelledCount++;
        }
        if (cancelledCount > 0) {
            LOGGER.info("[MiliTickRegionScheduler] Cancelled {} pending tick handles", cancelledCount);
        }

        // 关闭共享 MiliScheduler（owner=本类 —— 唯一所有者）
        MiliSchedulerHolder.shutdown("MiliTickRegionScheduler", 5_000L);

        shutdownLatch.countDown();
        LOGGER.info("[MiliTickRegionScheduler] Halted");
    }

    /**
     * 等待所有线程结束。
     */
    public boolean join(final long maxWaitMillis) {
        try {
            return shutdownLatch.await(maxWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 打印所有存活线程的堆栈。
     */
    public void dumpAliveThreadTraces(final String reason) {
        final Thread[] threads = getAliveThreads();
        for (final Thread thread : threads) {
            io.papermc.paper.util.TraceUtil.dumpTraceForThread(thread, reason);
        }
    }

    /**
     * 获取 MiliScheduler 实例。
     */
    public MiliScheduler getMiliScheduler() {
        return scheduler;
    }

    /**
     * 检查是否已停止。
     */
    public boolean isHalted() {
        return halted.get();
    }

    /**
     * 设置线程数（当前实现不支持动态调整，仅记录日志）。
     */
    public void setThreads(final int threads) {
        LOGGER.info("[MiliTickRegionScheduler] Thread count change requested to {} (not supported)",
                threads);
    }

    /**
     * 获取当前活跃的线程数（通过 MiliScheduler 的线程池）。
     */
    public int getTotalThreadCount() {
        return getAliveThreads().length;
    }

    /**
     * 获取所有存活的线程。
     */
    public Thread[] getAliveThreads() {
        // 通过 MiliScheduler 的线程池获取（MiliTickThread 实例）
        // 由于 MiliScheduler 不直接暴露线程列表，我们通过线程组扫描
        final ThreadGroup root = Thread.currentThread().getThreadGroup();
        Thread[] threads = new Thread[root.activeCount() + 10];
        int count = root.enumerate(threads);
        java.util.List<Thread> alive = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (threads[i] != null && threads[i].isAlive() && threads[i] instanceof MiliTickThread) {
                alive.add(threads[i]);
            }
        }
        return alive.toArray(new Thread[0]);
    }

    // =========================================================================
    // TickTask —— 带 TaskScheduleState 的 handle 包装 (R2-08/R2-13)
    // =========================================================================

    /**
     * Handle 包装器，附带 TaskScheduleState 门闩。
     *
     * <p>R2-08: 通过 {@link TaskScheduleState#tryMarkQueued()} 防止重复入队，
     * 通过状态机保证同一 handle 同一时刻只有一个 tick 任务在系统中。</p>
     */
    final class TickTask {
        final TickRegionScheduler.RegionScheduleHandle handle;
        final TaskScheduleState state = new TaskScheduleState();
        // R4-修复: 使用 AtomicReference 实现 getAndSet，避免 read-then-write 竞争
        final AtomicReference<TaskHandle> taskHandle = new AtomicReference<>(null);
        // R3-FIX: 当前退避延迟（连续 region-not-acquirable 时递增）
        private volatile long currentBackoffMs = RETRY_BACKOFF_INITIAL_MS;
        // R3-FIX: 连续 region-not-acquirable 重试计数（成功 tick 后重置）
        private volatile int consecutiveRetries = 0;
        // Mili start - AdaptiveRuntime §5.3：预算被拒计数（连续 3 次 → aging 放行）+ 预算退避
        private volatile int consecutiveBudgetRejections = 0;
        private volatile long currentBudgetBackoffMs = BUDGET_RETRY_INITIAL_MS;
        // Mili end

        TickTask(final TickRegionScheduler.RegionScheduleHandle handle) {
            this.handle = handle;
        }

        long regionId() {
            return handle.region != null ? handle.region.id : GLOBAL_TICK_REGION_ID;
        }

        // Mili start - AdaptiveRuntime §5.3：预算被拒计数与退避

        /** 预算被拒：返回 true 表示已达 aging 阈值（本提交放行） */
        boolean budgetRejected() {
            return ++consecutiveBudgetRejections >= BUDGET_AGING_THRESHOLD;
        }

        void resetBudgetRejections() {
            consecutiveBudgetRejections = 0;
        }

        /**
         * 预算不足的非阻塞重试（5ms → 50ms 退避；§5.3 / §6.3 R1）。
         *
         * <p>延迟后<strong>重新走 {@link #scheduleRegion(handle)}</strong>（而非直接
         * executeTask）：这样每次重试都重新过预算闸门，连续被拒计数才能累积到
         * {@link #BUDGET_AGING_THRESHOLD} 触发 aging 放行 —— 若直接执行 tick，
         * aging 计数永远停留在 1，防饥饿语义失效。
         */
        void resubmitBudgetRetry() {
            final long backoff = currentBudgetBackoffMs;
            currentBudgetBackoffMs = Math.min(currentBudgetBackoffMs * 2, BUDGET_RETRY_MAX_MS);
            if (halted.get() || handle.isMarkedAsNonSchedulable()) {
                return;
            }
            final long rid = regionId();
            final RegionTask reentry = new RegionTask() {
                @Override
                public void execute() {
                    if (!halted.get() && !handle.isMarkedAsNonSchedulable()) {
                        MiliTickRegionScheduler.this.scheduleRegion(handle);
                    }
                }

                @Override
                public long regionId() {
                    return rid;
                }

                @Override
                public @org.jetbrains.annotations.NotNull String name() {
                    return "BudgetRetry#" + rid;
                }

                @Override
                public void onCancel() {
                    // 预算重试被取消：不归还任何状态（scheduleRegion 自身 CAS 幂等）
                }
            };
            try {
                final TaskHandle newHandle = scheduler.scheduleDelayed(reentry,
                        Math.max(1, backoff), TimeUnit.MILLISECONDS);
                final TaskHandle previous = taskHandle.getAndSet(newHandle);
                if (previous != null) {
                    previous.cancel();
                }
            } catch (Exception e) {
                // 调度失败：预算重试不可能无限堆积 —— 下一轮 scheduleRegion 自然会再走闸门
                LOGGER.warn("[MiliTickRegionScheduler] budget retry scheduling failed for region #{}", rid, e);
            }
        }
        // Mili end

        RegionTask toRegionTask() {
            return toRegionTask(null);
        }

        RegionTask toRegionTask(final AutoCloseable lease) {
            final long rid = regionId();
            final Runnable r = this::executeTask;
            // RISK-FOLLOWUP：onCancel 把 task.state 从 QUEUED 还原为 IDLE，
            // 这样如果 Ownership verification 失败 cancel task，调度器后续
            // scheduleRegion(handle) 能重新 CAS tryMarkQueued() 成功。
            final fun.bm.mili.lmili.thread.scheduler.execute.TaskScheduleState localState = this.state;
            final TickRegionScheduler.RegionScheduleHandle localHandle = this.handle;
            return new RegionTask() {
                @Override
                public void execute() throws Exception {
                    try {
                        r.run();
                    } finally {
                        // Mili start - AdaptiveRuntime §5.3：预算租约 RAII 归还（幂等）
                        if (lease != null) {
                            try {
                                lease.close();
                            } catch (Throwable ignored) {
                                // BudgetLease.close 幂等；异常不应影响 tick 完成路径
                            }
                        }
                        // Mili end
                    }
                }

                @Override
                public long regionId() {
                    return rid;
                }

                @Override
                public @org.jetbrains.annotations.NotNull String name() {
                    return localHandle.region != null
                            ? "TickRegion#" + localHandle.region.id
                            : "TickGlobal";
                }

                @Override
                public void onCancel() {
                    // 把 task.state 从 QUEUED 还原为 IDLE，
                    // 允许 MiliTickRegionScheduler.scheduleRegion() 重新 CAS 成功。
                    // 不影响正常路径（state 是 RUNNING 时 tryMarkIdle 已经处理过）。
                    localState.resetQueuedToIdle();
                    // Mili start - AdaptiveRuntime §5.3：取消时归还预算租约
                    if (lease != null) {
                        try {
                            lease.close();
                        } catch (Throwable ignored) {
                            // 幂等 close；忽略
                        }
                    }
                    // Mili end
                }
            };
        }

        /**
         * 核心 tick 执行逻辑。
         *
         * <p>由 SchedulerWorker (MiliTickThread) 调用。
         * handle.runTick() 内部通过 setTickingRegion() 设置线程上下文。
         */
        void executeTask() {
            // 状态机：QUEUED → RUNNING，确保后续 tryMarkIdle() 能正确归还到 IDLE
            state.tryMarkRunning();
            try {
                // 性能优化：单次获取时间戳，避免多次 System.currentTimeMillis() 调用
                final long nowMillis = System.currentTimeMillis();

                // C-06 修复：next-tick gate —— 检查是否到了该 region 的下次允许 tick 时间
                if (handle.region != null) {
                    if (nowMillis < handle.nextAllowedTickTimeMillis) {
                        // 还没到时间，归还 RUNNING→IDLE 并延迟提交
                        state.tryMarkIdle();
                        resubmitDelayed(handle.nextAllowedTickTimeMillis - nowMillis);
                        return;
                    }
                }

                // R3-FIX: 执行 tick 前再次检查 handle 是否已被取消
                // 避免在取消后仍尝试 tick 并触发 region-not-acquirable 异常
                if (handle.isMarkedAsNonSchedulable()) {
                    state.tryMarkIdle();
                    return;
                }

                // 执行 tick — runTick() 内部会调用 setTickingRegion() 设置上下文
                // TPS 修复：记录 tick 开始时间，用于计算自适应延迟
                final boolean reschedule;
                final long tickElapsedMillis;
                try {
                    reschedule = handle.runTick();
                    // 性能优化：使用 nowMillis 计算 elapsed，避免第二次 currentTimeMillis 调用
                    tickElapsedMillis = System.currentTimeMillis() - nowMillis;
                } catch (IllegalStateException ise) {
                    // R3-FIX: 优雅处理 region-not-acquirable 异常
                    //
                    // runTick() 会在 region.state != STATE_READY 时抛出
                    // "Scheduled region should be acquirable"。这不是真正的失败，
                    // 只是说明 region 暂时被另一个执行者持有（Folia 原生调度器、
                    // 或 Mili 的另一条执行路径短暂持有）。
                    //
                    // 原代码通过 handleRegionFailure() → stopServer() 处理此异常，
                    // 会触发不必要的服务器关闭。修复方案：归还状态 + 短暂退避后重试。
                    state.tryMarkIdle();

                    if (!"Scheduled region should be acquirable".equals(ise.getMessage())) {
                        // 其他 IllegalStateException 仍按原逻辑处理
                        handleRegionFailure(ise);
                        return;
                    }

                    consecutiveRetries++;
                    if (consecutiveRetries > MAX_CONSECUTIVE_RETRIES) {
                        // R3-FIX: 连续重试次数超限 —— 放弃本次 tick 但不关闭服务器。
                        // Mili (噪音治理): 该路径在生产中典型场景是 region 被 Folia 原生调度器或
                        // Mili 另一执行路径短暂持有 —— 不是真正的失败，按 debug 级别记录；
                        // 每 tick 一条 warn 在持续争抢时会刷屏。调试时通过提升 logger 级别可见。
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("[MiliTickRegionScheduler] Region #{} not acquirable after {} retries, "
                                            + "skipping this tick (server will not shut down)",
                                    regionId(), consecutiveRetries);
                        }
                        consecutiveRetries = 0;
                        currentBackoffMs = RETRY_BACKOFF_INITIAL_MS;
                        // 仍然重新调度下一次 tick（region 释放后即可恢复）
                        if (!handle.isMarkedAsNonSchedulable() && !halted.get()) {
                            resubmitDelayed(Math.max(1L, (long) (1000.0 / tpsTarget)));
                        }
                        return;
                    }

                    // 指数退避（最多 RETRY_BACKOFF_MAX_MS）后重新提交
                    final long backoff = currentBackoffMs;
                    currentBackoffMs = Math.min(currentBackoffMs * 2, RETRY_BACKOFF_MAX_MS);
                    LOGGER.debug("[MiliTickRegionScheduler] Region #{} not acquirable, retrying in {} ms (attempt {})",
                            regionId(), backoff, consecutiveRetries);
                    if (!handle.isMarkedAsNonSchedulable() && !halted.get()) {
                        resubmitDelayed(backoff);
                    }
                    return;
                }
                // 成功执行了一次 tick —— 重置重试计数与退避延迟
                consecutiveRetries = 0;
                currentBackoffMs = RETRY_BACKOFF_INITIAL_MS;
                // Mili start - AdaptiveRuntime §5.3：成功 tick 后重置预算被拒计数与退避（aging 窗口结束）
                resetBudgetRejections();
                currentBudgetBackoffMs = BUDGET_RETRY_INITIAL_MS;
                // Mili end
                state.tryMarkIdle();

                // 如果需要继续调度，延迟提交到统壹队列
                if (reschedule && !handle.isMarkedAsNonSchedulable() && !halted.get()) {
                    // 性能优化：单次计算 tick 间隔（避免重复除法）
                    final long tickIntervalMs = Math.max(1L, (long) (1000.0 / tpsTarget));
                    if (handle.region != null) {
                        // TPS 修复：基于实际 tick 计算执行时间自适应延迟。
                        // 性能优化：使用 nowMillis 计算下次 tick 时间，避免额外 currentTimeMillis 调用
                        long delay = tickIntervalMs - tickElapsedMillis;
                        if (delay < 0) delay = 0;
                        handle.nextAllowedTickTimeMillis = nowMillis + tickElapsedMillis + delay;
                        resubmitDelayed(delay);
                    } else {
                        // global region 仍使用固定延迟（tpsTarget 一致口径）
                        resubmitDelayed(tickIntervalMs);
                    }
                }
            } catch (Throwable thr) {
                state.tryMarkIdle();
                handleRegionFailure(thr);
            }
        }

        /**
         * 延迟重新提交 tick 任务（避免忙等待）。
         *
         * <p>TPS 修复（R4）：在创建新的 delayed future 之前，先取消旧的任务句柄。
         * 否则每次 resubmit 都会在单线程 delayed scheduler 中堆积一个
         * {@code ScheduledFuture}，且与当前 {@code taskHandle} 分离、不会被清理。</p>
         *
         * <p>这些“孤儿”future 之后仍会触发：回调里调用 {@code submitRegionTask}，
         * 使同一个 region 产生重复的 tick 任务。重复任务竞速执行时，
         * {@code "Scheduled region should be acquirable"} 异常被抛出并计数
         * {@code consecutiveRetries} —— 当重试计数超过
         * {@link #MAX_CONSECUTIVE_RETRIES} 后，合法的 region tick 会被跳过，
         * 直接表现为长时间挂机后 TPS 从 20 下跌到 8 附近。</p>
         *
         * <p>取消旧句柄保证同一 region 同一时刻只有一个 delayed future 存活；
         * 配合 {@link TaskScheduleState} 的 dedup，彻底消除重复 tick 的源头。</p>
         */
        void resubmitDelayed(final long delayMs) {
            if (halted.get() || handle.isMarkedAsNonSchedulable()) return;
            // R2-08: CAS IDLE→QUEUED，防止重复提交
            if (!state.tryMarkQueued()) return;
            try {
                // R4-TPS 修复: 先创建新 delayed future，再用 getAndSet 原子交换。
                // 返回的旧 handle 若仍存活则取消，避免孤儿回调堆积在单线程
                // delayed scheduler 中（旧 handle 不会被新 handle 覆盖丢失）。
                final TaskHandle newHandle = scheduler.scheduleDelayed(toRegionTask(),
                        Math.max(1, delayMs), TimeUnit.MILLISECONDS);
                final TaskHandle previous = taskHandle.getAndSet(newHandle);
                if (previous != null) {
                    previous.cancel();
                }
                // System-level tick task — not a plugin task, no bridge needed.
            } catch (Exception e) {
                // scheduleDelayed 失败，状态仍是 QUEUED（不是 RUNNING），使用 forceCancel() 清理
                state.forceCancel();
            }
        }
    }

    /**
     * 获取或创建 regionId 对应的 TickTask。
     *
     * <p>R4-修复: 使用 ConcurrentHashMap.compute 以 regionId 为键。
     * regionId 是 region 的稳定标识符，比 handle identity 更适合做键：
     * 线程安全、不会因 handle 复制而泄漏旧条目。</p>
     *
     * <p>如果已有 TickTask 且其 handle 仍未标记为不可调度，则复用；
     * 否则创建新的 TickTask（关联新的 handle）。</p>
     */
    private TickTask computeTask(final TickRegionScheduler.RegionScheduleHandle handle) {
        final long rid = handle.region != null ? handle.region.id : GLOBAL_TICK_REGION_ID;
        return REGISTRY.compute(rid, (id, existing) -> {
            if (existing != null && !existing.handle.isMarkedAsNonSchedulable()) {
                return existing; // 复用
            }
            return new TickTask(handle);
        });
    }

    /**
     * 处理 region tick 失败。
     */
    private static void handleRegionFailure(final Throwable thr) {
        LOGGER.error("[MiliTickRegionScheduler] Exception during tick", thr);
        // 触发服务器关闭（Folia 标准行为）
        try {
            MinecraftServer.getServer().stopServer();
        } catch (Exception e) {
            LOGGER.error("[MiliTickRegionScheduler] Failed to stop server", e);
        }
    }

    /**
     * 启动调度器（MiliScheduler 在构造器中已启动）。
     */
    public void start() {
        // MiliScheduler 已在构造器中启动 worker
    }
}
