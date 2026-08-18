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

    // ---- 状态 ----
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /**
     * 创建 Mili Tick Region Scheduler。
     *
     * @param threadCount worker 线程数
     */
    public MiliTickRegionScheduler(final int threadCount) {
        final int workerCount = Math.max(1, threadCount);

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
     * @param handle region 的调度句柄
     */
    public void scheduleRegion(final TickRegionScheduler.RegionScheduleHandle handle) {
        if (halted.get()) return;

        // 立即提交到 worker 队列（不使用延迟调度，简化实现）
        for (TickRegionWorker worker : workers) {
            if (worker.submitRegion(handle)) {
                return;
            }
        }
        // 如果所有 worker 都满了，强制提交到第一个
        workers[0].forceSubmitRegion(handle);
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
     */
    public void halt() {
        if (!halted.compareAndSet(false, true)) return;
        LOGGER.info("[MiliTickRegionScheduler] Halting...");

        // 停止所有 worker
        for (TickRegionWorker worker : workers) {
            worker.shutdown();
        }
        // Unpark 所有阻塞的 worker
        for (TickRegionWorker worker : workers) {
            final MiliTickThread t = worker.thread;
            if (t != null) LockSupport.unpark(t);
        }

        // 关闭 MiliScheduler
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
            taskQueue.offer(handle);
            final MiliTickThread t = this.thread;
            if (t != null) LockSupport.unpark(t);
            return true;
        }

        void forceSubmitRegion(final TickRegionScheduler.RegionScheduleHandle handle) {
            taskQueue.offer(handle);
            final MiliTickThread t = this.thread;
            if (t != null) LockSupport.unpark(t);
        }

        void shutdown() {
            running.set(false);
        }

        @Override
        public void run() {
            // 获取当前线程（MiliTickThread）并保存引用
            final MiliTickThread currentThread = (MiliTickThread) Thread.currentThread();
            this.thread = currentThread;

            while (running.get() && !halted.get()) {
                TickRegionScheduler.RegionScheduleHandle handle = taskQueue.poll();

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

                // 执行 region tick
                executeRegionTick(currentThread, handle);
            }

            LOGGER.debug("[MiliTickRegionScheduler-Worker-{}] Stopped", workerId);
        }

        /**
         * 从其他 worker 窃取任务。
         */
        private TickRegionScheduler.RegionScheduleHandle stealWork() {
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
         * <p>设置线程的 region 上下文，调用 handle.runTick()，然后清理上下文。
         * 对于 global tick（region == null），跳过 region 上下文设置直接执行。</p>
         */
        private void executeRegionTick(final MiliTickThread thread,
                                        final TickRegionScheduler.RegionScheduleHandle handle) {
            final TickRegions.TickRegionData regionData = handle.region;

            // Global tick (regionData == null) 不需要设置 region 上下文
            if (regionData != null) {
                final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = regionData.region;
                if (region != null && region.regioniser != null && region.regioniser.world != null) {
                    // 获取并设置 RegionizedWorldData
                    final io.papermc.paper.threadedregions.RegionizedWorldData worldData =
                            region.regioniser.world.worldRegionData.get();
                    if (worldData != null) {
                        thread.setTickingRegion(region, worldData);
                    } else {
                        LOGGER.debug("[MiliTickRegionScheduler] WorldData is null for region #{}, proceeding without context",
                                regionData.id);
                    }
                }
            }

            try {
                // 执行 tick
                final boolean reschedule = handle.runTick();

                // 如果需要继续调度，重新提交到 worker 队列
                if (reschedule && !halted.get() && !handle.isMarkedAsNonSchedulable()) {
                    submitRegion(handle);
                }
            } catch (Throwable thr) {
                // Region 失败处理
                final String regionInfo = regionData != null ? "#" + regionData.id : "global";
                LOGGER.error("[MiliTickRegionScheduler] Exception during tick for region {}", regionInfo, thr);
                handleRegionFailure(handle, thr);
            } finally {
                // 清除 region 上下文
                thread.clearTickingRegion();
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
