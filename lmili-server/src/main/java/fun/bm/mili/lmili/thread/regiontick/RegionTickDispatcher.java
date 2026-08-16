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

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Region tick 调度器 —— 管理 region tick 的 worker 线程池和任务分派。
 *
 * <p>支持两种 worker 模式：
 * <ul>
 *   <li><b>Virtual Thread 模式</b>（推荐，JDK 24+）：使用虚拟线程作为 worker，
 *       适合高并发场景，不受 OS 线程数限制。backend pool 为 {@code newVirtualThreadPerTaskExecutor}。</li>
 *   <li><b>Platform Thread 模式</b>：传统固定大小线程池，适合保守部署或兼容性需求。</li>
 * </ul>
 *
 * <p>当前同步执行模式：由于 Folia 的 scheduler 契约要求，
 * 当前 tick 直接在 Folia 调度线程上执行（避免 watchdog 超时）。
 * 当 {@link RegionTickPoolConfig#useVirtualThreads} 启用时，
 * 后续可将 tick slice 派发到 virtual thread 池中并行执行。
 */
public final class RegionTickDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile RegionTickDispatcher instance;

    private final ExecutorService workerPool;
    private final boolean useVirtualThreads;
    private final RegionTickWorker[] workers;
    private final ConcurrentHashMap<Long, RegionTickContext> activeContexts = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private final int maxWorkersPerRegion;
    private final int minWorkersPerRegion;
    private final int parallelismThreshold;
    private final int sliceSize;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final DagBasedTickExecutor dagExecutor = new DagBasedTickExecutor();
    private long totalTicksDispatched;

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
            // Mili start - Virtual Thread 模式：每个任务一个虚拟线程
            this.workers = null; // virtual thread 模式下不使用固定 worker 数组
            this.workerPool = Executors.newThreadPerTaskExecutor(
                    MiliThreadFactory.virtual("RegionTickPool-Virtual-"));
            LOGGER.info("[RegionTickPool] Worker pool: virtual thread per task");
        } else {
            // Platform Thread 模式：传统固定线程池
            this.workers = new RegionTickWorker[workerCount];
            for (int i = 0; i < workerCount; i++) {
                this.workers[i] = new RegionTickWorker("RegionTickPool-Worker-" + i);
            }
            this.workerPool = Executors.newFixedThreadPool(workerCount,
                    MiliThreadFactory.platform("RegionTickPool-Worker-"));
            LOGGER.info("[RegionTickPool] Worker pool: {} platform threads", workerCount);

            // 提交 worker 到线程池
            for (RegionTickWorker worker : this.workers) {
                this.workerPool.submit(worker);
            }
        }
        // Mili end

        if (dagExecutor.getSystemCount() > 0) {
            RegionTickExecutor.register(dagExecutor);
            LOGGER.info("[RegionTickPool] Using DagBasedTickExecutor (systems={})", dagExecutor.getSystemCount());
        } else {
            RegionTickExecutor.register(new FoliaTickExecutor());
        }

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

    /**
     * 是否启用了 virtual thread 模式。
     */
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
     * 分派 region tick 任务。
     *
     * <p>当 virtual thread 模式启用时，将 slice 提交到虚拟 thread pool 并行执行。
     * 否则回退到在当前线程上同步执行（Folia watchdog 安全）。
     */
    public void dispatchTick(@NotNull final RegionTickContext context, final long tickCount) {
        if (this.shutdown.get()) return;

        // Mili start - skip the dispatcher entirely for the global region (id==0L).
        if (context.regionId == 0L) {
            return;
        }
        // Mili end

        // 诊断日志（每 500 tick）
        if ((this.totalTicksDispatched) % 500L == 0L) {
            LOGGER.info("[RegionTickPool] dispatchTick #{}: region id={}, chunks={}, mode={}",
                    this.totalTicksDispatched, context.regionId,
                    context.getOwnedChunks().size(),
                    useVirtualThreads ? "virtual" : "platform");
        }

        var chunks = context.getOwnedChunks();
        int chunkCount = chunks.size();

        // Mili start - 根据模式选择执行方式
        if (useVirtualThreads) {
            dispatchVirtual(context, chunks.toLongArray(), tickCount);
        } else {
            dispatchSynchronous(context, chunks.toLongArray(), tickCount);
        }
        this.totalTicksDispatched++;
        // Mili end
    }

    /**
     * Virtual Thread 模式：将 slice 提交到虚拟线程池并行执行。
     *
     * <p>注意：Folia 的 watchdog 依赖 arriveSlice() 及时调用，因此 virtual thread
     * 模式下使用同步等待确保时序安全。virtual thread 的优势在于不占用 OS 线程，
     * 允许更高并发的 IO 操作，而非避免阻塞。
     *
     * <p>未来可扩展为多 slice 并行 tick：将 chunks 拆分为多个 slice，
     * 每个 slice 一个 virtual thread，然后用 CountDownLatch 等待完成。
     */
    private void dispatchVirtual(@NotNull final RegionTickContext context,
                                  final long[] chunkArray,
                                  final long tickCount) {
        context.beginTick(1);
        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
        if (executor != null) {
            // 提交到 virtual thread pool 并等待完成（同步模式确保 watchdog 安全）
            Future<?> future = workerPool.submit(() -> {
                try {
                    executor.executeSlice(null,
                            new RegionTickSlice(context, chunkArray, 0), context);
                } catch (Throwable throwable) {
                    LOGGER.error("[RegionTickPool] Virtual tick failed for region #{}",
                            context.regionId, throwable);
                }
            });
            try {
                // 等待 virtual thread 完成（超时 4.5s，留给 watchdog 余量）
                future.get(4500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                LOGGER.warn("[RegionTickPool] Virtual tick timed out for region #{}", context.regionId);
            } catch (Exception e) {
                LOGGER.error("[RegionTickPool] Virtual tick error for region #{}", context.regionId, e);
            }
        }
        context.arriveSlice();
        context.endTick();
    }

    /**
     * Platform Thread 模式 / 同步执行：直接在当前线程执行。
     */
    private void dispatchSynchronous(@NotNull final RegionTickContext context,
                                      final long[] chunkArray,
                                      final long tickCount) {
        context.beginTick(1);
        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
        if (executor != null) {
            try {
                executor.executeSlice(null, new RegionTickSlice(context, chunkArray, 0), context);
            } catch (Throwable throwable) {
                LOGGER.error("[RegionTickPool] Tick failed for region #{}", context.regionId, throwable);
            }
        }
        context.arriveSlice();
        context.endTick();
    }

    private void dispatchSingleThread(final RegionTickContext context, final long tickCount) {
        if (context.regionId == 0L) {
            return;
        }
        if (context.getOwnedChunks().isEmpty()) return;
        context.beginTick(1);
        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
        if (executor != null) {
            try {
                executor.executeSlice(null, new RegionTickSlice(context, context.getOwnedChunks().toLongArray(), 0), context);
            } catch (Throwable throwable) {
                LOGGER.error("[RegionTickPool] Single-thread tick failed for region #{}", context.regionId, throwable);
            }
        }
        context.arriveSlice();
        context.endTick();
        this.totalTicksDispatched++;
    }

    private void distributeSlices(final RegionTickSlice[] slices, final int workerCount) {
        int actualWorkers = Math.min(workerCount, this.workers.length);
        int[] load = new int[actualWorkers];
        for (RegionTickSlice slice : slices) {
            int minIdx = 0;
            for (int i = 1; i < actualWorkers; i++) if (load[i] < load[minIdx]) minIdx = i;
            this.workers[minIdx].submit(slice);
            load[minIdx]++;
        }
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

    /**
     * 实体 tick 调度。
     *
     * <p>注意：实体 tick（尤其是 {@link net.minecraft.world.entity.Entity#checkDespawn()}）会调用
     * {@code Level.getLocalPlayers()}，而该方法依赖 Folia 的线程本地 region 数据，
     * 只在当前 region 的 tick 线程上安全。因此本类不会把实体 tick 派发到
     * ForkJoinPool worker，而是在调用方（region tick）线程上同步执行。
     *
     * @param regionId          区域 ID
     * @param context           tick 上下文
     * @param level             当前 ServerLevel
     * @param regionizedWorldData 当前 region 数据
     */
    public void dispatchDagTick(final long regionId,
                                 @NotNull final RegionTickContext context,
                                 @NotNull final ServerLevel level,
                                 @NotNull final io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData) {
        if (this.shutdown.get()) return;

        // 直接在当前线程（region tick 线程）上跑实体 tick，避免 ForkJoin 线程导致的
        // Level#getLocalPlayers 等调用抛出 NPE。
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

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("active_regions", this.activeContexts.size());
        stats.put("total_ticks_dispatched", this.totalTicksDispatched);
        stats.put("worker_count", useVirtualThreads ? "unlimited (virtual)" : this.workers.length);
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
