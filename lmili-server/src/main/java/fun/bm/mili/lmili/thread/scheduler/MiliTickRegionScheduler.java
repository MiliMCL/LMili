package fun.bm.mili.lmili.thread.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import io.papermc.paper.threadedregions.*;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Mili Tick Region Scheduler —— 完全替代 Folia 的 {@link TickRegionScheduler}。
 *
 * <p>使用 {@link MiliScheduler} 的 work-stealing 架构替代 Folia 的 EDF/StealingScheduledThreadPool。
 * Worker 线程是 {@link MiliTickThread}（extends {@link TickThread}），确保 Folia 的线程安全检查正常工作。
 *
 * <h3>与 Folia 的区别</h3>
 * <ul>
 *   <li>Folia: 每个 region 一个线程（或 EDF 调度），线程数 = region 数</li>
 *   <li>Mili: 固定数量的 worker 线程，work-stealing 负载均衡，region 共享线程</li>
 * </ul>
 *
 * <h3>Tick 调度流程</h3>
 * <pre>
 * scheduleRegion(handle)
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
 * 如果需要继续 tick → scheduleDelayed(nextTick, delay)
 * </pre>
 */
public final class MiliTickRegionScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final boolean MEASURE_CPU_TIME;
    static {
        MEASURE_CPU_TIME = THREAD_MX_BEAN.isThreadCpuTimeSupported();
        if (MEASURE_CPU_TIME) {
            THREAD_MX_BEAN.setThreadCpuTimeEnabled(true);
        }
    }

    public static final int TICK_RATE = 20;
    public static long TIME_BETWEEN_TICKS = 1_000_000_000L / TICK_RATE; // ns

    // Folia watchdog - 复用 Folia 的 watchdog
    public static final FoliaWatchdogThread WATCHDOG_THREAD = new FoliaWatchdogThread();
    static {
        WATCHDOG_THREAD.start();
    }

    // ---- 核心组件 ----
    private final MiliScheduler scheduler;
    private final Thread[] workerThreads;
    private final TickRegionWorker[] workers;
    private final AtomicInteger threadIdGen = new AtomicInteger();

    // C-05 修复：Round-robin 分配计数器，避免 Worker0 倾斜
    private final AtomicInteger nextWorkerCounter = new AtomicInteger(0);

    // ---- 状态 ----
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /**
     * 创建 Mili Tick Region Scheduler。
     *
     * @param threadCount worker 线程数
     */
    public MiliTickRegionScheduler(final int threadCount) {
        // 至少使用2个worker线程，确保global tick不会被region tick阻塞
        final int workerCount = Math.max(2, threadCount);

        // 创建 MiliScheduler（用于延迟任务等）
        this.scheduler = MiliSchedulerBuilder.create("tick-region-scheduler")
                .threadNamePrefix("MiliTick-")
                .carrierThreads(workerCount)
                .maxBlockingTasks(Math.max(2, workerCount / 2))
                .build();

        // 创建 MiliTickThread worker 线程
        this.workers = new TickRegionWorker[workerCount];
        this.workerThreads = new Thread[workerCount];

        for (int i = 0; i < workerCount; i++) {
            final TickRegionWorker worker = new TickRegionWorker(i);
            this.workers[i] = worker;
            final MiliTickThread thread = new MiliTickThread(worker, "Mili Tick Region Thread #" + threadIdGen.getAndIncrement());
            this.workerThreads[i] = thread;
            thread.start();
        }

        LOGGER.info("[MiliTickRegionScheduler] Started {} worker threads", workerCount);
    }

    /**
     * 启动调度器（所有线程已经在构造器中启动）。
     */
    public void start() {
        // 线程已在构造器中启动
    }

    /**
     * 设置线程数（当前实现不支持动态调整，仅记录日志）。
     */
    public void setThreads(final int threads) {
        LOGGER.info("[MiliTickRegionScheduler] Thread count change requested to {} (not supported, keeping {})",
                threads, workerThreads.length);
    }

    /**
     * 获取当前活跃的线程数。
     */
    public int getTotalThreadCount() {
        int count = 0;
        for (Thread thread : workerThreads) {
            if (thread.isAlive()) count++;
        }
        return count;
    }

    /**
     * 获取所有存活的线程。
     */
    public Thread[] getAliveThreads() {
        List<Thread> alive = new ArrayList<>();
        for (Thread thread : workerThreads) {
            if (thread.isAlive()) alive.add(thread);
        }
        return alive.toArray(new Thread[0]);
    }

    /**
     * 调度一个 region 进行 tick。
     *
     * <p>将 region 的 tick 任务提交到 work-stealing 队列。
     * Worker 线程会从队列中获取任务并执行。</p>
     *
     * <p>C-05 修复：使用 round-robin 分配，从随机起始点遍历，避免 Worker0 倾斜。</p>
     *
     * @param handle region 的调度句柄
     */
    public void scheduleRegion(final TickRegionScheduler.RegionScheduleHandle handle) {
        if (halted.get()) return;

        final int workerCount = workers.length;
        // Round-robin 分配，保证均匀分布
        int startIndex = Math.abs(nextWorkerCounter.getAndIncrement()) % workerCount;

        for (int i = 0; i < workerCount; i++) {
            int idx = (startIndex + i) % workerCount;
            if (workers[idx].submitRegion(handle)) {
                return;
            }
        }
        // 如果所有 worker 都拒绝了（已关闭），强制提交到起始 worker
        workers[startIndex].forceSubmitRegion(handle);
    }

    /**
     * 取消 region 的调度。
     */
    public void descheduleRegion(final TickRegionScheduler.RegionScheduleHandle handle) {
        handle.markNonSchedulable();
    }

    /**
     * 通知调度器 region 有中间任务需要执行。
     */
    public void setHasTasks(final TickRegionScheduler.RegionScheduleHandle handle) {
        // Mili 实现：worker 在空闲时会自动检查中间任务
        // 这里只需要 unpark 一个空闲 worker
        for (TickRegionWorker worker : workers) {
            if (worker.isIdle()) {
                final MiliTickThread t = worker.thread;
                if (t != null) LockSupport.unpark(t);
                return;
            }
        }
    }

    /**
     * 停止调度器。
     *
     * <p>C-22 修复：统一 drain/cancel 处理 —— 先 drain 所有 worker 队列中的 pending regions，
     * 调用它们的 markNonSchedulable()，然后停止 worker 线程。
     */
    public void halt() {
        if (!halted.compareAndSet(false, true)) return;
        LOGGER.info("[MiliTickRegionScheduler] Halting...");

        // 步骤 1：Drain 所有 worker 队列，取消 pending regions（C-22 修复）
        int cancelledCount = 0;
        for (TickRegionWorker worker : workers) {
            cancelledCount += worker.drainAndCancel();
        }
        if (cancelledCount > 0) {
            LOGGER.info("[MiliTickRegionScheduler] Cancelled {} pending regions during halt", cancelledCount);
        }

        // 步骤 2：停止所有 worker
        for (TickRegionWorker worker : workers) {
            worker.shutdown();
        }
        // Unpark 所有阻塞的 worker
        for (TickRegionWorker worker : workers) {
            final MiliTickThread t = worker.thread;
            if (t != null) LockSupport.unpark(t);
        }

        // 步骤 3：关闭 MiliScheduler
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
        for (Thread thread : workerThreads) {
            if (thread.isAlive()) {
                io.papermc.paper.util.TraceUtil.dumpTraceForThread(thread, reason);
            }
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

    // =========================================================================
    // TickRegionWorker - 核心 worker 实现
    // =========================================================================

    /**
     * Region Tick Worker —— 每个 worker 维护一个本地任务队列，从其他 worker 窃取任务。
     *
     * <p>这是替代 Folia 的 {@code TickThreadRunner} + {@code Scheduler} 的核心组件。</p>
     */
    private final class TickRegionWorker implements Runnable {
        private final int workerId;
        private volatile MiliTickThread thread; // 在 run() 开始时设置
        // Global tick（region==null）使用高优先级队列，确保登录等关键任务不被 region tick 饥饿
        private final ConcurrentLinkedQueue<TickRegionScheduler.RegionScheduleHandle> globalQueue = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<TickRegionScheduler.RegionScheduleHandle> taskQueue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile boolean idle = true;

        TickRegionWorker(final int workerId) {
            this.workerId = workerId;
        }

        boolean isIdle() {
            return idle && running.get();
        }

        boolean submitRegion(final TickRegionScheduler.RegionScheduleHandle handle) {
            if (!running.get()) return false;
            // Global tick（region==null）放入高优先级队列
            if (handle.region == null) {
                globalQueue.offer(handle);
            } else {
                taskQueue.offer(handle);
            }
            final MiliTickThread t = this.thread;
            if (t != null) LockSupport.unpark(t);
            return true;
        }

        // R2-12 修复：forceSubmitRegion 增加 lifecycle/state 检查
        // 只允许在 halted 前最后一个手段使用
        void forceSubmitRegion(final TickRegionScheduler.RegionScheduleHandle handle) {
            // 检查 scheduler lifecycle
            if (halted.get()) {
                return; // 已停止，拒绝强制提交
            }
            if (handle.region == null) {
                globalQueue.offer(handle);
            } else {
                taskQueue.offer(handle);
            }
            final MiliTickThread t = this.thread;
            if (t != null) LockSupport.unpark(t);
        }

        void shutdown() {
            running.set(false);
        }

        /**
         * C-22 修复：Drain 所有队列并取消 pending regions。
         *
         * <p>在 halt() 时调用，确保所有已调度但未执行的 region 被正确取消。
         *
         * @return 取消的 region 数量
         */
        int drainAndCancel() {
            int count = 0;
            TickRegionScheduler.RegionScheduleHandle handle;
            // Drain global queue
            while ((handle = globalQueue.poll()) != null) {
                handle.markNonSchedulable();
                count++;
            }
            // Drain task queue
            while ((handle = taskQueue.poll()) != null) {
                handle.markNonSchedulable();
                count++;
            }
            return count;
        }

        @Override
        public void run() {
            // 获取当前线程（MiliTickThread）并保存引用
            final MiliTickThread currentThread = (MiliTickThread) Thread.currentThread();
            this.thread = currentThread;

            while (running.get() && !halted.get()) {
                // 优先处理 global tick（登录等关键任务）
                TickRegionScheduler.RegionScheduleHandle handle = globalQueue.poll();

                if (handle == null) {
                    handle = taskQueue.poll();
                }

                if (handle == null) {
                    // 尝试从其他 worker 窃取
                    handle = stealWork();
                }

                if (handle == null) {
                    // 无任务，等待
                    idle = true;
                    if (!halted.get()) {
                        LockSupport.park(this);
                    }
                    idle = false;
                    continue;
                }

                // 检查是否已取消
                if (handle.isMarkedAsNonSchedulable()) {
                    continue;
                }

                // 执行 tick（global tick 不等待间隔，region tick 等待）
                executeRegionTick(currentThread, handle);
            }

            LOGGER.debug("[MiliTickRegionScheduler-Worker-{}] Stopped", workerId);
        }

        /**
         * 从其他 worker 窃取任务。
         */
        private TickRegionScheduler.RegionScheduleHandle stealWork() {
            // 优先窃取 global tick
            for (TickRegionWorker other : workers) {
                if (other == this) continue;
                TickRegionScheduler.RegionScheduleHandle stolen = other.globalQueue.poll();
                if (stolen != null) {
                    return stolen;
                }
            }
            // 然后窃取 region tick
            for (TickRegionWorker other : workers) {
                if (other == this) continue;
                TickRegionScheduler.RegionScheduleHandle stolen = other.taskQueue.poll();
                if (stolen != null) {
                    return stolen;
                }
            }
            return null;
        }

        /**
         * 执行 region tick —— 这是核心执行路径。
         *
         * <p>runTick() 内部通过 TickRegionScheduler.setTickingRegion() 设置线程的 region 上下文，
         * 无需在此手动设置。setTickingRegion() 已为 MiliTickThread 正确设置
         * currentTickingRegion 和 currentTickingWorldRegionizedData。</p>
         *
         * <p>C-06 修复：增加 next-tick gate，防止 region 在允许时间之前被重复 tick。</p>
         */
        private void executeRegionTick(final MiliTickThread thread,
                                        final TickRegionScheduler.RegionScheduleHandle handle) {
            try {
                // C-06 修复：next-tick gate —— 检查是否到了该 region 的下次允许 tick 时间
                if (handle.region != null) {
                    long now = System.currentTimeMillis();
                    if (now < handle.nextAllowedTickTimeMillis) {
                        // 还没到时间，重新提交到队列稍后处理
                        if (!handle.isMarkedAsNonSchedulable()) {
                            taskQueue.offer(handle);
                        }
                        return;
                    }
                }

                // 在执行 region tick 前，检查是否有 global tick 等待
                // 如果有，重新调度当前 region tick 以优先处理 global tick
                if (handle.region != null && !globalQueue.isEmpty()) {
                    // 有 global tick 等待，重新调度 region tick
                    if (!handle.isMarkedAsNonSchedulable()) {
                        taskQueue.offer(handle);
                    }
                    return;
                }

                // 执行 tick — runTick() 内部会调用 setTickingRegion() 设置上下文
                final boolean reschedule = handle.runTick();

                // 如果需要继续调度，重新提交到 worker 队列
                if (reschedule && !halted.get() && !handle.isMarkedAsNonSchedulable()) {
                    // C-06 修复：更新下次允许 tick 时间
                    if (handle.region != null) {
                        handle.nextAllowedTickTimeMillis = System.currentTimeMillis() + (1000L / TICK_RATE);
                    }
                    submitRegion(handle);
                }
            } catch (Throwable thr) {
                // Region 失败处理
                final TickRegions.TickRegionData regionData = handle.region;
                final String regionInfo = regionData != null ? "#" + regionData.id : "global";
                LOGGER.error("[MiliTickRegionScheduler] Exception during tick for region {}", regionInfo, thr);
                handleRegionFailure(handle, thr);
            }
        }

        /**
         * 处理 region tick 失败。
         */
        private void handleRegionFailure(final TickRegionScheduler.RegionScheduleHandle handle, final Throwable thr) {
            LOGGER.error("Region #{} failed to tick:", handle.region != null ? handle.region.id : -1, thr);
            halted.set(true);

            final net.minecraft.world.level.ChunkPos center = handle.region == null ? null : handle.region.region.getCenterChunk();
            final net.minecraft.server.level.ServerLevel world = handle.region == null ? null : handle.region.world;
            LOGGER.error("Region #{} centered at chunk {} in world '{}' failed to tick:",
                    handle.region == null ? -1L : handle.region.id,
                    center,
                    world == null ? "null" : world.getWorld().getName());

            MinecraftServer.getServer().stopServer();
        }
    }
}
