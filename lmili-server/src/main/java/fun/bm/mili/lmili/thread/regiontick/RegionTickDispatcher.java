package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.lmili.thread.regiontick.dag.Scope;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import fun.bm.mili.lmili.thread.regiontick.executor.DagBasedTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.executor.FoliaTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.suspend.MiliThreadFactory;
import fun.bm.mili.lmili.thread.regiontick.suspend.VirtualThreadScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Region tick 调度器 —— 管理 region tick 的 worker 线程池和任务分派。
 *
 * <p>支持两种 worker 模式：
 * <ul>
 *   <li><b>Virtual Thread 模式</b>（推荐，JDK 24+）：使用虚拟线程作为 worker，
 *       适合高并发场景，不受 OS 线程数限制。</li>
 *   <li><b>Platform Thread 模式</b>：传统固定大小线程池，适合保守部署或兼容性需求。</li>
 * </ul>
 *
 * <h3>执行模式</h3>
 * <ul>
 *   <li>Virtual 模式下：将 chunks 拆分为多个 slice，每个 slice 一个 virtual thread 并行执行，
 *       使用 StructuredScope 管理生命周期。</li>
 *   <li>Platform 模式下：按 worker 数量将 slices 分发给固定 worker 线程队列消费。</li>
 * </ul>
 */
public final class RegionTickDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile RegionTickDispatcher instance;

    private final ExecutorService workerPool;
    private final boolean useVirtualThreads;
    private final RegionTickWorker[] workers;
    private final ConcurrentHashMap<Long, RegionTickContext> activeContexts = new ConcurrentHashMap<>();
    private final int maxWorkersPerRegion;
    private final int minWorkersPerRegion;
    private final int parallelismThreshold;
    private final int sliceSize;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final DagBasedTickExecutor dagExecutor = new DagBasedTickExecutor();
    private final LongAdder totalTicksDispatched = new LongAdder();
    private final LongAdder totalTickErrors = new LongAdder();
    private final AtomicLong maxTickDurationNanos = new AtomicLong(0);
    private static final long SLOW_TICK_THRESHOLD_MS = 50;

    private RegionTickDispatcher(final int workerCount, final int maxWorkersPerRegion,
                                  final int minWorkersPerRegion, final int parallelismThreshold,
                                  final int sliceSize,
                                  final boolean useVirtualThreads) {
        this.maxWorkersPerRegion = maxWorkersPerRegion;
        this.minWorkersPerRegion = minWorkersPerRegion;
        this.parallelismThreshold = parallelismThreshold;
        this.sliceSize = sliceSize;
        this.useVirtualThreads = useVirtualThreads;

        if (useVirtualThreads) {
            this.workers = null;
            this.workerPool = Executors.newThreadPerTaskExecutor(
                    MiliThreadFactory.virtual("RegionTickPool-Virtual-"));
            LOGGER.info("[RegionTickPool] Worker pool: virtual thread per task");
        } else {
            this.workers = new RegionTickWorker[workerCount];
            for (int i = 0; i < workerCount; i++) {
                this.workers[i] = new RegionTickWorker("RegionTickPool-Worker-" + i);
            }
            this.workerPool = Executors.newFixedThreadPool(workerCount,
                    MiliThreadFactory.platform("RegionTickPool-Worker-"));
            LOGGER.info("[RegionTickPool] Worker pool: {} platform threads", workerCount);

            for (RegionTickWorker worker : this.workers) {
                this.workerPool.submit(worker);
            }
        }

        // 选择 executor
        RegionTickExecutor foliaExec = new FoliaTickExecutor();
        RegionTickExecutor.register(foliaExec);
        LOGGER.info("[RegionTickPool] Dispatcher initialized (virtualThreads={}, workers={})",
                useVirtualThreads, useVirtualThreads ? "unlimited" : workerCount);
    }

    public static RegionTickDispatcher init() {
        RegionTickDispatcher existing = instance;
        if (existing != null) return existing;
        synchronized (RegionTickDispatcher.class) {
            if (instance == null) {
                RegionTickPoolConfig config = new RegionTickPoolConfig();
                instance = new RegionTickDispatcher(
                        config.getWorkerCount(), config.getMaxWorkersPerRegion(),
                        config.getMinWorkersPerRegion(), config.parallelismThreshold,
                        config.sliceSize,
                        config.useVirtualThreads);
            }
            return instance;
        }
    }

    public static RegionTickDispatcher getInstance() { return instance; }

    /**
     * Returns true if the tick dispatcher is initialized and ready.
     */
    public static boolean isRunning() {
        return instance != null && RegionTickPoolConfig.enabled;
    }

    public boolean isVirtualThreadMode() {
        return useVirtualThreads;
    }

    public RegionTickContext registerRegion(final long regionId,
                                             final io.papermc.paper.threadedregions.ThreadedRegionizer
                                                     .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        RegionTickContext context = new RegionTickContext(regionId, region);
        this.activeContexts.put(regionId, context);
        return context;
    }

    public void unregisterRegion(final long regionId) { this.activeContexts.remove(regionId); }

    public RegionTickContext getOrCreateContext(
            final io.papermc.paper.threadedregions.TickRegions.TickRegionData regionData) {
        final long regionId = regionData.id;
        RegionTickContext context = this.activeContexts.get(regionId);
        if (context != null) return context;
        synchronized (this) {
            context = this.activeContexts.get(regionId);
            if (context != null) return context;
            context = new RegionTickContext(regionId, regionData.region);
            this.activeContexts.put(regionId, context);
            return context;
        }
    }

    /**
     * 分派 region tick 任务 —— 主入口。
     *
     * <p>根据区域 chunk 数量决定执行策略：
     * <ul>
     *   <li>global region (id==0) 或空 chunk：跳过</li>
     *   <li>chunk 数低于 parallelismThreshold：单线程同步执行（无调度开销）</li>
     *   <li>virtual 模式：多 virtual thread 并行执行 slices</li>
     *   <li>platform 模式：分发给 worker 队列执行</li>
     * </ul>
     */
    public void dispatchTick(@NotNull final RegionTickContext context, final long tickCount) {
        if (this.shutdown.get()) return;

        if (context.regionId == 0L) return;

        var chunks = context.getOwnedChunks();
        int chunkCount = chunks.size();
        if (chunkCount == 0) return;

        long startNanos = System.nanoTime();
        try {
            if (chunkCount < parallelismThreshold) {
                // 小 region 直接同步执行，避免调度开销
                dispatchSingleSlice(context, chunks.toLongArray());
            } else if (useVirtualThreads) {
                dispatchParallelVirtual(context, chunks.toLongArray());
            } else {
                dispatchParallelPlatform(context, chunks.toLongArray());
            }
        } catch (Throwable throwable) {
            totalTickErrors.increment();
            LOGGER.error("[RegionTickPool] dispatchTick failed for region #{}", context.regionId, throwable);
        } finally {
            long elapsed = System.nanoTime() - startNanos;
            totalTicksDispatched.increment();
            maxTickDurationNanos.accumulateAndGet(elapsed, Math::max);

            // 慢 tick 告警
            long elapsedMs = elapsed / 1_000_000;
            if (elapsedMs > SLOW_TICK_THRESHOLD_MS) {
                LOGGER.warn("[RegionTickPool] SLOW tick in region #{}: {}ms (chunks={}, mode={})",
                        context.regionId, elapsedMs, chunkCount,
                        useVirtualThreads ? "virtual" : "platform");
            }

            // 诊断日志（每 1000 tick）
            long total = totalTicksDispatched.sum();
            if (total % 1000L == 0L) {
                LOGGER.info("[RegionTickPool] Stats: total_ticks={}, errors={}, max_tick_ms={}, regions={}",
                        total, totalTickErrors.sum(), maxTickDurationNanos.get() / 1_000_000,
                        activeContexts.size());
            }
        }
    }

    /**
     * 单 slice 同步 tick —— 小 region 专用。
     */
    private void dispatchSingleSlice(@NotNull final RegionTickContext context, final long[] chunkArray) {
        context.beginTick(1);
        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
        if (executor != null) {
            try {
                executor.executeSlice(null, new RegionTickSlice(context, chunkArray, 0), context);
            } catch (Throwable throwable) {
                LOGGER.error("[RegionTickPool] Single-slice tick failed for region #{}", context.regionId, throwable);
            }
        }
        context.arriveSlice();
        context.endTick();
    }

    /**
     * Virtual Thread 并行模式 —— 将 chunks 拆分为多个 slice，
     * 每个 slice 作为一个 virtual thread 并行执行，使用 CountDownLatch 等待全部完成。
     *
     * <p>兼容性说明：Folia 通过 {@code TickThreadRunner.currentTickingWorldRegionizedData} 跟踪 region data，
     * 虚拟线程不在该体系中。本方法通过 {@link RegionDataThreadLocal} 将当前 region data 传播到每个虚拟线程，
     * 确保 chunk tick 内的 {@code getCurrentWorldData()} / {@code getLocalPlayers()} 等调用不返回 null。
     */
    private void dispatchParallelVirtual(@NotNull final RegionTickContext context, final long[] chunkArray) {
        int total = chunkArray.length;
        int sliceCount = Math.max(1, (total + sliceSize - 1) / sliceSize);
        CountDownLatch latch = new CountDownLatch(sliceCount);
        AtomicBoolean hasError = new AtomicBoolean(false);

        // Mili start: 捕获当前 region 的 RegionizedWorldData，传递给每个虚拟线程
        io.papermc.paper.threadedregions.RegionizedWorldData currentRegionData = null;
        try {
            net.minecraft.world.level.Level world = context.region.getData().world;
            if (world instanceof net.minecraft.server.level.ServerLevel) {
                currentRegionData = world.getCurrentWorldData();
            }
        } catch (Exception e) {
            LOGGER.warn("[RegionTickPool] Failed to capture region data for virtual dispatch", e);
        }
        final io.papermc.paper.threadedregions.RegionizedWorldData regionData = currentRegionData;

        if (regionData == null) {
            // 无法获取 region data 时回退到单线程执行
            LOGGER.warn("[RegionTickPool] No region data for virtual dispatch in region #{} — falling back to single-slice",
                    context.regionId);
            dispatchSingleSlice(context, chunkArray);
            return;
        }
        // Mili end

        for (int i = 0; i < sliceCount; i++) {
            int from = i * sliceSize;
            int to = Math.min(from + sliceSize, total);
            long[] sliceArray = java.util.Arrays.copyOfRange(chunkArray, from, to);
            RegionTickSlice slice = new RegionTickSlice(context, sliceArray, i);
            final int sliceIndex = i;

            workerPool.submit(() -> {
                // Mili start: 在虚拟线程中设置 region data 回退
                RegionDataThreadLocal.setCurrent(regionData);
                try {
                    RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
                    if (executor != null) {
                        executor.executeSlice(null, slice, context);
                    }
                } catch (Throwable throwable) {
                    hasError.set(true);
                    LOGGER.error("[RegionTickPool] Virtual slice #{} failed for region #{}",
                            sliceIndex, context.regionId, throwable);
                } finally {
                    RegionDataThreadLocal.clear();
                    latch.countDown();
                }
                // Mili end
            });
        }

        try {
            // 等待所有 slice 完成（超时 4.5s，留给 watchdog 余量）
            if (!latch.await(4500, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("[RegionTickPool] Virtual tick timed out for region #{} (remaining: {})",
                        context.regionId, latch.getCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[RegionTickPool] Virtual tick interrupted for region #{}", context.regionId);
        }

        if (hasError.get()) {
            totalTickErrors.increment();
        }
    }

    /**
     * Platform Thread 并行模式 —— 将 slices 分配给 worker 队列。
     */
    private void dispatchParallelPlatform(@NotNull final RegionTickContext context, final long[] chunkArray) {
        if (workers == null || workers.length == 0) return;

        RegionTickSlice[] slices = RegionTickSlice.fromChunkArray(context, chunkArray, sliceSize);
        context.beginTick(slices.length);

        // 贪心负载均衡分发给 workers
        int actualWorkers = Math.min(slices.length, workers.length);
        int[] load = new int[actualWorkers];
        for (RegionTickSlice slice : slices) {
            int minIdx = 0;
            for (int i = 1; i < actualWorkers; i++) {
                if (load[i] < load[minIdx]) minIdx = i;
            }
            workers[minIdx].submit(slice);
            load[minIdx]++;
        }

        // 等待所有 worker 完成
        context.awaitTickCompletion();
        context.endTick();
    }

    /**
     * 实体 tick 调度 —— 在 region tick 线程上同步执行。
     *
     * <p>注意：实体 tick 会调用 {@code Level.getLocalPlayers()}，依赖 Folia 的线程本地 region 数据，
     * 因此必须在 region tick 线程上直接执行，不能派发到其他线程。
     */
    public void dispatchEntityTick(final long regionId,
                                    @NotNull final RegionTickContext context,
                                    @NotNull final ServerLevel level,
                                    @NotNull final io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData) {
        if (this.shutdown.get()) return;

        regionizedWorldData.forEachTickingEntity(entity -> {
            if (entity.isRemoved()) return;
            if (level.tickRateManager().isEntityFrozen(entity)) return;
            try {
                entity.checkDespawn();
            } catch (Throwable throwable) {
                LOGGER.error("[RegionTickPool] Entity checkDespawn failed for {} in region #{}", entity, regionId, throwable);
            }
            if (entity.isRemoved()) return;
            Entity vehicle = entity.getVehicle();
            if (vehicle != null) {
                if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) return;
                entity.stopRiding();
            }
            level.guardEntityTick(level::tickNonPassenger, entity);
        });
    }

    public void registerDagSystem(@NotNull final String name,
                                   @NotNull final SystemProfile profile,
                                   @NotNull final Scope scope,
                                   @NotNull final java.util.function.BiConsumer<SystemProfile, Scope> executor) {
        dagExecutor.registerSystem(name, profile, scope, executor);
        RegionTickExecutor.register(dagExecutor);
        LOGGER.info("[RegionTickPool] DAG system '{}' registered, total systems={}", name, dagExecutor.getSystemCount());
    }

    public void unregisterDagSystem(@NotNull final String name) {
        dagExecutor.unregisterSystem(name);
        LOGGER.info("[RegionTickPool] DAG system '{}' unregistered, total systems={}", name, dagExecutor.getSystemCount());
    }

    public void executeDagSystems(final long regionId,
                                   @NotNull final RegionTickContext context,
                                   @NotNull final List<Map.Entry<SystemProfile, Scope>> systemScopePairs) {
        dagExecutor.executeSystems(regionId, context, systemScopePairs);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("active_regions", this.activeContexts.size());
        stats.put("total_ticks_dispatched", this.totalTicksDispatched.sum());
        stats.put("total_tick_errors", this.totalTickErrors.sum());
        stats.put("max_tick_duration_ms", this.maxTickDurationNanos.get() / 1_000_000);
        stats.put("worker_count", useVirtualThreads ? "unlimited (virtual)" : (workers != null ? workers.length : 0));
        stats.put("use_virtual_threads", useVirtualThreads);
        stats.put("dag_systems", dagExecutor.getSystemCount());
        stats.put("dag_build_nanos", dagExecutor.getDagBuildNanos());
        stats.put("shutdown", this.shutdown.get());
        return stats;
    }

    public void shutdown() {
        if (this.shutdown.getAndSet(true)) return;
        LOGGER.info("[RegionTickPool] Shutting down...");
        if (workers != null) {
            for (RegionTickWorker worker : this.workers) worker.shutdown();
        }
        this.workerPool.shutdown();
        try {
            if (!this.workerPool.awaitTermination(5, TimeUnit.SECONDS)) this.workerPool.shutdownNow();
        } catch (InterruptedException e) {
            this.workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        this.activeContexts.clear();
        instance = null;
        LOGGER.info("[RegionTickPool] Shutdown complete");
    }
}
