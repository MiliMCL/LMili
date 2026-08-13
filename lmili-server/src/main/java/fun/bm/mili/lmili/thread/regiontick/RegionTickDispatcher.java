package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.lmili.thread.regiontick.dag.Scope;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import fun.bm.mili.lmili.thread.regiontick.executor.DagBasedTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.executor.FoliaTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.migration.TickMigrationQueue;
import io.papermc.paper.threadedregions.TickRegions;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class RegionTickDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile RegionTickDispatcher instance;

    private final ExecutorService workerPool;
    private final RegionTickWorker[] workers;
    private final ConcurrentHashMap<Long, RegionTickContext> activeContexts = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private final int maxWorkersPerRegion;
    private final int parallelismThreshold;
    private final int sliceSize;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final DagBasedTickExecutor dagExecutor = new DagBasedTickExecutor();
    private final TickMigrationQueue migrationQueue = TickMigrationQueue.getInstance();
    private long totalTicksDispatched;
    private int migrationsCommitted;

    private RegionTickDispatcher(final int workerCount, final int maxWorkersPerRegion,
                                  final int parallelismThreshold, final int sliceSize) {
        this.maxWorkersPerRegion = maxWorkersPerRegion;
        this.parallelismThreshold = parallelismThreshold;
        this.sliceSize = sliceSize;
        this.workers = new RegionTickWorker[workerCount];
        for (int i = 0; i < workerCount; i++) this.workers[i] = new RegionTickWorker("RegionTickPool-Worker-" + i);

        this.workerPool = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "RegionTickPool-Worker");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("[RegionTickPool] Uncaught exception", e));
            return thread;
        });

        for (RegionTickWorker worker : this.workers) this.workerPool.submit(worker);

        if (dagExecutor.getSystemCount() > 0) {
            RegionTickExecutor.register(dagExecutor);
            LOGGER.info("[RegionTickPool] Using DagBasedTickExecutor (systems={})", dagExecutor.getSystemCount());
        } else {
            RegionTickExecutor.register(new FoliaTickExecutor());
        }

        LOGGER.info("[RegionTickPool] Dispatcher initialized with {} workers", workerCount);
    }

    public static RegionTickDispatcher init() {
        RegionTickDispatcher existing = instance;
        if (existing != null) return existing;
        synchronized (RegionTickDispatcher.class) {
            if (instance == null) {
                RegionTickPoolConfig config = new RegionTickPoolConfig();
                instance = new RegionTickDispatcher(
                        config.getWorkerCount(), config.getMaxWorkersPerRegion(),
                        config.parallelismThreshold, config.sliceSize);
            }
            return instance;
        }
    }

    public static RegionTickDispatcher getInstance() { return instance; }

    public RegionTickContext registerRegion(final long regionId,
                                             final io.papermc.paper.threadedregions.ThreadedRegionizer
                                                     .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        RegionTickContext context = new RegionTickContext(regionId, region);
        this.activeContexts.put(regionId, context);
        return context;
    }

    public void unregisterRegion(final long regionId) { this.activeContexts.remove(regionId); }

    public void dispatchTick(@NotNull final RegionTickContext context, final long tickCount) {
        if (this.shutdown.get()) return;

        var chunks = context.getOwnedChunks();
        int chunkCount = chunks.size();

        if (chunkCount < parallelismThreshold || context.computeDesiredWorkers(maxWorkersPerRegion, parallelismThreshold) <= 1) {
            dispatchSingleThread(context, tickCount);
            return;
        }

        RegionTickSlice[] slices = RegionTickSlice.fromChunkList(context, chunks, sliceSize);
        context.beginTick(slices.length);
        distributeSlices(slices, Math.min(context.computeDesiredWorkers(maxWorkersPerRegion, parallelismThreshold), this.workers.length));
        context.awaitTickCompletion();
        commitMigrations();
        context.endTick();
        this.totalTicksDispatched++;
    }

    private void dispatchSingleThread(final RegionTickContext context, final long tickCount) {
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
        commitMigrations();
        context.endTick();
        this.totalTicksDispatched++;
    }

    private void commitMigrations() {
        int committed = migrationQueue.commitAll();
        if (committed > 0) this.migrationsCommitted += committed;
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

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("active_regions", this.activeContexts.size());
        stats.put("total_ticks_dispatched", this.totalTicksDispatched);
        stats.put("migrations_committed", this.migrationsCommitted);
        stats.put("worker_count", this.workers.length);
        stats.put("dag_systems", dagExecutor.getSystemCount());
        stats.put("dag_build_nanos", dagExecutor.getDagBuildNanos());
        stats.put("pending_migrations", migrationQueue.pendingCount());
        stats.put("shutdown", this.shutdown.get());
        return stats;
    }

    public void shutdown() {
        if (this.shutdown.getAndSet(true)) return;
        LOGGER.info("[RegionTickPool] Shutting down...");
        for (RegionTickWorker worker : this.workers) worker.shutdown();
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
