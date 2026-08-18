package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.lmili.thread.regiontick.dag.Scope;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import fun.bm.mili.lmili.thread.regiontick.executor.ModernDagTickExecutor;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Region tick 调度器 —— 协调各子组件完成 region tick 调度。
 *
 * <p>拆分为以下子组件：
 * <ul>
 *   <li>{@link WorkerPoolManager} —— 线程池生命周期管理</li>
 *   <li>{@link ChunkTickDispatcher} —— Chunk tick 分派（3 种模式）</li>
 *   <li>{@link EntityTickDispatcher} —— Entity tick 分派与诊断</li>
 *   <li>{@link AsyncCatcherManager} —— Async Catcher 引用计数</li>
 *   <li>{@link RegionDiagnostics} —— 统计与诊断</li>
 * </ul>
 */
public final class RegionTickDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile RegionTickDispatcher instance;

    private final WorkerPoolManager poolManager;
    private final ChunkTickDispatcher chunkDispatcher;
    private final EntityTickDispatcher entityDispatcher;
    private final AsyncCatcherManager asyncCatcherManager;
    private final RegionDiagnostics diagnostics;
    private final ModernDagTickExecutor dagExecutor;
    private final ConcurrentHashMap<Long, RegionTickContext> activeContexts = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private RegionTickDispatcher(final int workerCount, final int maxWorkersPerRegion,
                                  final int minWorkersPerRegion, final int parallelismThreshold,
                                  final int sliceSize,
                                  final boolean useVirtualThreads) {
        // 创建子组件
        this.poolManager = new WorkerPoolManager(workerCount, useVirtualThreads);
        this.asyncCatcherManager = new AsyncCatcherManager();
        this.diagnostics = new RegionDiagnostics();
        this.chunkDispatcher = new ChunkTickDispatcher(
                poolManager, parallelismThreshold, sliceSize,
                asyncCatcherManager, diagnostics);
        this.entityDispatcher = new EntityTickDispatcher();

        // 初始化现代 DAG 执行器
        this.dagExecutor = new ModernDagTickExecutor(poolManager.getExecutor());

        // 选择 executor
        RegionTickExecutor foliaExec = new fun.bm.mili.lmili.thread.regiontick.executor.FoliaTickExecutor();
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
        return poolManager.isVirtualThreadMode();
    }

    public RegionTickContext registerRegion(final long regionId,
                                             final io.papermc.paper.threadedregions.ThreadedRegionizer
                                                     .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        RegionTickContext context = new RegionTickContext(regionId, region);
        this.activeContexts.put(regionId, context);
        return context;
    }

    public void unregisterRegion(final long regionId) {
        this.activeContexts.remove(regionId);
        this.chunkDispatcher.unregister(regionId);
    }

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
     */
    public void dispatchTick(@NotNull final RegionTickContext context, final long tickCount) {
        if (this.shutdown.get()) return;
        chunkDispatcher.dispatch(context, tickCount);
    }

    /**
     * 实体 tick 调度 —— 在 region tick 线程上同步执行。
     */
    public void dispatchEntityTick(final long regionId,
                                    @NotNull final RegionTickContext context,
                                    @NotNull final ServerLevel level,
                                    @NotNull final RegionizedWorldData regionizedWorldData) {
        if (this.shutdown.get()) return;
        entityDispatcher.dispatch(regionId, context, level, regionizedWorldData);
    }

    public void registerDagSystem(@NotNull final String name,
                                   @NotNull final SystemProfile profile,
                                   @NotNull final Scope scope,
                                   @NotNull final java.util.function.BiConsumer<SystemProfile, Scope> executor) {
        dagExecutor.registerSystem(name, profile, scope, (prof, scp) -> executor.accept(prof, (Scope) scp));
        RegionTickExecutor.register(dagExecutor);
        LOGGER.info("[RegionTickPool] DAG system '{}' registered, total systems={}", name, dagExecutor.getSystemCount());
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.putAll(diagnostics.getStats());
        stats.putAll(entityDispatcher.getStats());
        stats.put("active_regions", this.activeContexts.size());
        stats.put("worker_count", poolManager.isVirtualThreadMode() ? "unlimited (virtual)" : poolManager.getWorkerCount());
        stats.put("use_virtual_threads", poolManager.isVirtualThreadMode());
        stats.put("dag_systems", dagExecutor.getSystemCount());
        stats.put("pending_chunk_ticks", chunkDispatcher.getPendingChunkTickCount());
        stats.put("async_catcher_refs", asyncCatcherManager.getRefCount());
        stats.put("shutdown", this.shutdown.get());
        return stats;
    }

    public void shutdown() {
        if (this.shutdown.getAndSet(true)) return;
        LOGGER.info("[RegionTickPool] Shutting down...");
        poolManager.shutdown();
        this.activeContexts.clear();
        instance = null;
        LOGGER.info("[RegionTickPool] Shutdown complete");
    }
}
