package fun.bm.mili.lmili.thread.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import fun.bm.mili.lmili.thread.scheduler.api.TaskHandle;
import fun.bm.mili.lmili.thread.scheduler.execute.TaskScheduleState;
import io.papermc.paper.threadedregions.*;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // Folia watchdog - 复用 Folia 的 watchdog
    public static final FoliaWatchdogThread WATCHDOG_THREAD = new FoliaWatchdogThread();
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

    // ---- R2-08/R2-13: handle → TickTask wrapper registry (identity-based) ----
    private static final Map<TickRegionScheduler.RegionScheduleHandle, TickTask> REGISTRY =
            Collections.synchronizedMap(new IdentityHashMap<>());

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

        final TickTask task = computeTask(handle);
        // R2-08: CAS IDLE→QUEUED 防止重复入队
        if (!task.state.tryMarkQueued()) {
            return; // 已在 QUEUED/RUNNING 状态
        }

        try {
            task.taskHandle = scheduler.submit(task.toRegionTask());
        } catch (Exception e) {
            // 提交失败，状态仍是 QUEUED（不是 RUNNING），tryMarkIdle() 无法工作，使用 forceCancel() 清理
            task.state.forceCancel();
            LOGGER.warn("[MiliTickRegionScheduler] Failed to submit tick task for region #{}",
                    handle.region != null ? handle.region.id : -1, e);
        }
    }

    /**
     * 取消 region 的调度。
     */
    public void descheduleRegion(final TickRegionScheduler.RegionScheduleHandle handle) {
        handle.markNonSchedulable();
        final TickTask task = REGISTRY.get(handle);
        if (task != null) {
            task.state.tryCancel();
            if (task.taskHandle != null) {
                task.taskHandle.cancel();
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
            if (task.taskHandle != null) {
                task.taskHandle.cancel();
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
        volatile TaskHandle taskHandle;
        // R3-FIX: 当前退避延迟（连续 region-not-acquirable 时递增）
        private volatile long currentBackoffMs = RETRY_BACKOFF_INITIAL_MS;
        // R3-FIX: 连续 region-not-acquirable 重试计数（成功 tick 后重置）
        private volatile int consecutiveRetries = 0;

        TickTask(final TickRegionScheduler.RegionScheduleHandle handle) {
            this.handle = handle;
        }

        long regionId() {
            return handle.region != null ? handle.region.id : GLOBAL_TICK_REGION_ID;
        }

        RegionTask toRegionTask() {
            final long rid = regionId();
            final Runnable r = this::executeTask;
            return RegionTask.builder(rid)
                    .task(r)
                    .name(handle.region != null ? "TickRegion#" + handle.region.id : "TickGlobal")
                    .build();
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
                // C-06 修复：next-tick gate —— 检查是否到了该 region 的下次允许 tick 时间
                if (handle.region != null) {
                    final long now = System.currentTimeMillis();
                    if (now < handle.nextAllowedTickTimeMillis) {
                        // 还没到时间，归还 RUNNING→IDLE 并延迟提交
                        state.tryMarkIdle();
                        resubmitDelayed(handle.nextAllowedTickTimeMillis - now);
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
                final long tickStartMillis;
                final boolean reschedule;
                final long tickElapsedMillis;
                try {
                    tickStartMillis = System.currentTimeMillis();
                    reschedule = handle.runTick();
                    tickElapsedMillis = System.currentTimeMillis() - tickStartMillis;
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
                        // R3-FIX: 连续重试次数超限 —— 放弃本次 tick 但不关闭服务器
                        LOGGER.warn("[MiliTickRegionScheduler] Region #{} not acquirable after {} retries, "
                                        + "skipping this tick (server will not shut down)",
                                regionId(), consecutiveRetries);
                        consecutiveRetries = 0;
                        currentBackoffMs = RETRY_BACKOFF_INITIAL_MS;
                        // 仍然重新调度下一次 tick（region 释放后即可恢复）
                        if (!handle.isMarkedAsNonSchedulable() && !halted.get()) {
                            resubmitDelayed(TIME_BETWEEN_TICKS_MS);
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
                state.tryMarkIdle();

                // 如果需要继续调度，延迟提交到统壹队列
                if (reschedule && !handle.isMarkedAsNonSchedulable() && !halted.get()) {
                    if (handle.region != null) {
                        // TPS 修复：基于实际 tick 计算执行时间自适应延迟
                        // 如果 tick 执行时间 < 50ms，等待剩余时间；如果超时，立即执行
                        long delay = TIME_BETWEEN_TICKS_MS - tickElapsedMillis;
                        if (delay < 0) delay = 0;
                        handle.nextAllowedTickTimeMillis = System.currentTimeMillis() + delay;
                        resubmitDelayed(delay);
                    } else {
                        // global region 仍使用固定延迟
                        resubmitDelayed(TIME_BETWEEN_TICKS_MS);
                    }
                }
            } catch (Throwable thr) {
                state.tryMarkIdle();
                handleRegionFailure(thr);
            }
        }

        /**
         * 延迟重新提交 tick 任务（避免忙等待）。
         */
        void resubmitDelayed(final long delayMs) {
            if (halted.get() || handle.isMarkedAsNonSchedulable()) return;
            // R2-08: CAS IDLE→QUEUED，防止重复提交
            if (!state.tryMarkQueued()) return;
            try {
                taskHandle = scheduler.scheduleDelayed(toRegionTask(),
                        Math.max(1, delayMs), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // scheduleDelayed 失败，状态仍是 QUEUED（不是 RUNNING），使用 forceCancel() 清理
                state.forceCancel();
            }
        }
    }

    /**
     * 获取或创建 handle 对应的 TickTask（identity-based，同一 handle 只有一个实例）。
     */
    private TickTask computeTask(final TickRegionScheduler.RegionScheduleHandle handle) {
        synchronized (REGISTRY) {
            TickTask existing = REGISTRY.get(handle);
            if (existing != null) return existing;
            final TickTask created = new TickTask(handle);
            REGISTRY.put(handle, created);
            return created;
        }
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
