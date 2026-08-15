package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.lmili.thread.regiontick.MiliGameSystems;
import fun.bm.mili.lmili.thread.regiontick.dag.Scope;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import fun.bm.mili.lmili.thread.regiontick.executor.DagBasedTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.executor.FoliaTickExecutor;
import io.papermc.paper.threadedregions.TickRegions;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final int minWorkersPerRegion;
    private final int parallelismThreshold;
    private final int sliceSize;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final DagBasedTickExecutor dagExecutor = new DagBasedTickExecutor();
    private long totalTicksDispatched;

    private RegionTickDispatcher(final int workerCount, final int maxWorkersPerRegion,
                                  final int minWorkersPerRegion, final int parallelismThreshold,
                                  final int sliceSize) {
        this.maxWorkersPerRegion = maxWorkersPerRegion;
        this.minWorkersPerRegion = minWorkersPerRegion;
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
                        config.getMinWorkersPerRegion(), config.parallelismThreshold,
                        config.sliceSize);
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

    public void dispatchTick(@NotNull final RegionTickContext context, final long tickCount) {
        if (this.shutdown.get()) return;

        // Mili start - skip the dispatcher entirely for the global region (id==0L).
        // The global region thread is the ONLY thread that ticks config-phase connections
        // (RegionizedServer#tickConnections → prepare_spawn / AsyncKeepalive) and runs chunk
        // stage progression (drainGlobalChunkTasks). Handing it to the dispatcher starves both,
        // leaving the player stuck on "Joining World" until the 30s keepalive times out.
        //
        // The global region also owns no chunks (Folia's private regions hold the chunks), so
        // skipping dispatch here loses no chunk-tick work — it only restores the original
        // MinecraftServer#tickServer path that runs on this thread for non-chunk global work.
        //
        // This inner safety net is defense-in-depth: even if the outer TickRegions#tickRegion
        // patch is absent from the deployed jar (e.g. paperclip-launched servers), the global
        // thread can never be hijacked by the dispatcher.
        if (context.regionId == 0L) {
            LOGGER.info("[RegionTickPool] dispatchTick: global region id==0 -- skipping dispatcher for this tick");
            return;
        }
        // Mili end diagnostic: log every 500th dispatch so we can see that the dispatcher
        // is processing non-global regions at all (helps rule out outer-patch scenarios).
        if ((this.totalTicksDispatched) % 500L == 0L) {
            LOGGER.info("[RegionTickPool] dispatchTick #{}: non-global region id={}, owned chunks={}, thread={}",
                this.totalTicksDispatched, context.regionId, context.getOwnedChunks().size(), Thread.currentThread().getName());
        }

        var chunks = context.getOwnedChunks();
        int chunkCount = chunks.size();

        // Mili start - fix: Execute synchronously on the Folia scheduler thread.
        // Previously we blocked the scheduler thread waiting on a Phaser for worker threads
        // to complete, which caused Folia's watchdog to detect a hang. The Mili worker pool
        // model is not fully compatible with Folia's non-blocking scheduler contract.
        // Executing directly on the scheduler thread is the safest approach.
        context.beginTick(1);
        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
        if (executor != null) {
            try {
                executor.executeSlice(null, new RegionTickSlice(context, chunks.toLongArray(), 0), context);
            } catch (Throwable throwable) {
                LOGGER.error("[RegionTickPool] Tick failed for region #{}", context.regionId, throwable);
            }
        }
        context.arriveSlice();
        context.endTick();
        this.totalTicksDispatched++;
        // Mili end
    }

    private void dispatchSingleThread(final RegionTickContext context, final long tickCount) {
        // Mili start - skip global region for the same reason as dispatchTick: its thread runs
        // connection ticks and chunk stage progression that must never be hijacked.
        if (context.regionId == 0L) {
            return;
        }
        // Mili end
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
     * DAG 并行化实体 + 方块实体 tick。
     *
     * <p>将 region 内的 ticking 实体按所在 chunk 分组，每个 chunk 生成一个
     * {@code ChunkScope}，连同系统 Profile 一起提交给 DAG 执行器。
     * 不同 chunk 上的实体因 Scope 不重叠可安全并行 tick。
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

        // 按 chunk 分组实体
        final Long2ObjectOpenHashMap<List<Entity>> entitiesByChunk = new Long2ObjectOpenHashMap<>();
        regionizedWorldData.forEachTickingEntity(entity -> {
            long chunkKey = ChunkPos.pack(entity.blockPosition());
            List<Entity> list = entitiesByChunk.get(chunkKey);
            if (list == null) {
                list = new ArrayList<>();
                entitiesByChunk.put(chunkKey, list);
            }
            list.add(entity);
        });

        if (entitiesByChunk.isEmpty()) return;

        // 构建 (SystemProfile, Scope) 对 — 每个 chunk 一个 entity_tick 系统实例
        final List<Map.Entry<SystemProfile, Scope>> systemScopePairs = new ArrayList<>(entitiesByChunk.size());
        for (long chunkKey : entitiesByChunk.keySet()) {
            LongSet chunks = new LongOpenHashSet();
            chunks.add(chunkKey);
            Scope scope = new Scope.ChunkScope(regionId, chunks);
            systemScopePairs.add(new AbstractMap.SimpleEntry<>(MiliGameSystems.ENTITY_TICK, scope));
        }

        // 构建 level-aware executors
        final Map<String, java.util.function.BiConsumer<Scope, ServerLevel>> executors = new HashMap<>();
        executors.put("entity_tick", (scope, lvl) -> {
            if (scope instanceof Scope.ChunkScope chunkScope) {
                for (long chunkKey : chunkScope.chunkPositions()) {
                    List<Entity> entities = entitiesByChunk.get(chunkKey);
                    if (entities == null) continue;
                    for (Entity entity : entities) {
                        if (entity.isRemoved()) continue;
                        if (lvl.tickRateManager().isEntityFrozen(entity)) continue;
                        entity.checkDespawn();
                        if (entity.isRemoved()) continue;
                        Entity vehicle = entity.getVehicle();
                        if (vehicle != null) {
                            if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) continue;
                            entity.stopRiding();
                        }
                        lvl.guardEntityTick(lvl::tickNonPassenger, entity);
                    }
                }
            }
        });

        dagExecutor.executeSystems(regionId, context, systemScopePairs, level, executors);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("active_regions", this.activeContexts.size());
        stats.put("total_ticks_dispatched", this.totalTicksDispatched);
        stats.put("worker_count", this.workers.length);
        stats.put("dag_systems", dagExecutor.getSystemCount());
        stats.put("dag_build_nanos", dagExecutor.getDagBuildNanos());
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
