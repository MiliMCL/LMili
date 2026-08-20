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
 * Mili Tick Region Scheduler —— Folia API 适配器（R2-07 双 Worker runtime 合并）。
 *
 * <p><b>R2-07 修复</b>：此类现在是纯 Folia API 适配器，不再拥有独立的 Worker 线程池。
 * 所有 tick 执行委派给 {@link MiliScheduler} 的统壹 Worker Pool
 * （{@link MiliTickThread} 实例作为 carrier 线程）。</p>
 *
 * <h3>与旧版的区别</h3>
 * <ul>
 *   <li>旧版：独立的 {@code TickRegionWorker[]} + 独立的队列 + 独立的 stealing 逻辑</li>
 *   <li>新版：单一 {@link MiliScheduler} Worker Pool，work-stealing 负载均衡</li>
 * </ul>
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

    // ---- R2-07: 单壹 MiliScheduler（统壹 Worker Pool） ----
    private final MiliScheduler scheduler;
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    // ---- R2-08/R2-13: handle → TickTask wrapper registry (identity-based) ----
    private static final Map<TickRegionScheduler.RegionScheduleHandle, TickTask> REGISTRY =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * 创建 Mili Tick Region Scheduler。
     *
     * <p>R2-07: 使用 tickThreads=true 创建 MiliTickThread worker（统壹 Worker Pool）。
     *
     * @param threadCount worker 线程数
     */
    public MiliTickRegionScheduler(final int threadCount) {
        // 至少使用2个worker线程，确保global tick不会被region tick阻塞
        final int workerCount = Math.max(2, threadCount);

        // R2-07: tickThreads=true → workers 是 MiliTickThread 实例
        this.scheduler = MiliSchedulerBuilder.create("tick-region-scheduler")
                .threadNamePrefix("MiliTick-")
                .carrierThreads(workerCount)
                .maxBlockingTasks(Math.max(2, workerCount / 2))
                .tickThreads(true)
                .build();

        LOGGER.info("[MiliTickRegionScheduler] Started with unified runtime ({} workers)", workerCount);
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
     * <p>R2-07: 委派给 MiliScheduler 统一关闭（不再需要独立的 worker 停止逻辑）。
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

        // 统壹关闭 MiliScheduler
        try {
            scheduler.shutdown(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

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

                // 执行 tick — runTick() 内部会调用 setTickingRegion() 设置上下文
                final boolean reschedule = handle.runTick();
                state.tryMarkIdle();

                // 如果需要继续调度，延迟提交到统壹队列
                if (reschedule && !handle.isMarkedAsNonSchedulable() && !halted.get()) {
                    if (handle.region != null) {
                        handle.nextAllowedTickTimeMillis = System.currentTimeMillis() + TIME_BETWEEN_TICKS_MS;
                    }
                    resubmitDelayed(TIME_BETWEEN_TICKS_MS);
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
