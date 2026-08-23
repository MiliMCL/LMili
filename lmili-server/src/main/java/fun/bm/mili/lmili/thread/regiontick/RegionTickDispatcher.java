package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.lmili.thread.regiontick.dag.Scope;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import fun.bm.mili.lmili.thread.regiontick.executor.CompositeNodeScheduler;
import fun.bm.mili.lmili.thread.regiontick.executor.FoliaRegionNodeScheduler;
import fun.bm.mili.lmili.thread.regiontick.executor.ModernDagTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.executor.NodeScheduler;
import fun.bm.mili.lmili.thread.regiontick.executor.SameRegionNodeScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
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
 *   <li>{@link WorkerPoolManager} —— 线程池生命周期管理（兼容备用）</li>
 *   <li>{@link ChunkTickDispatcher} —— Chunk tick 分派（3 种模式）</li>
 *   <li>{@link EntityTickDispatcher} —— Entity tick 分派与诊断</li>
 *   <li>{@link AsyncCatcherManager} —— Async Catcher 引用计数</li>
 *   <li>{@link RegionDiagnostics} —— 统计与诊断</li>
 *   <li>DAG 系统通过 {@link #attachScheduler(MiliScheduler)} 接入共享 MiliScheduler</li>
 * </ul>
 *
 * <p><b>单 runtime + DAG-region 协调修复</b>：DAG 节点执行（{@link ModernDagTickExecutor}）的
 * {@link NodeScheduler} 默认使用 {@link SameRegionNodeScheduler}（保留 Folia tickingRegion
 * 上下文）。通过 {@link #attachScheduler(MiliScheduler)} 切换为 {@link CompositeNodeScheduler}，
 * 按节点 regionId 路由 —— 跨 region 节点重新入 Folia 调度，由目标 region 的 acquire 路径执行。</p>
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

    /** DAG 节点路由器（按 regionId 路由节点 —— Mili 修复）。 */
    private volatile NodeScheduler dagNodeScheduler;

    /** 同 region 快路径调度器（Mili 修复）。 */
    private final SameRegionNodeScheduler sameRegionScheduler;

    /** 跨 region 调度器（Mili 修复）。 */
    private final FoliaRegionNodeScheduler foliaRegionScheduler;

    /** 已接入的共享 scheduler（用于诊断 / 关闭时确认）。 */
    private volatile MiliScheduler attachedScheduler;

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

        // Mili 修复：默认路由器走"同 region 快路径"（兼容老行为），attachScheduler
        // 切换为 CompositeNodeScheduler（按 regionId 路由）。
        // 这确保：DAG 节点派发严格遵守 region 约束 —— 跨 region 节点必须重新
        // 走 Folia 的 region acquire 路径，禁止在同一线程直接执行。
        this.sameRegionScheduler = new SameRegionNodeScheduler();
        this.foliaRegionScheduler = new FoliaRegionNodeScheduler();
        this.dagNodeScheduler = this.sameRegionScheduler; // 默认：同 region 快路径
        this.dagExecutor = new ModernDagTickExecutor(this.dagNodeScheduler);

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
        return instance != null;
    }

    public boolean isVirtualThreadMode() {
        return poolManager.isVirtualThreadMode();
    }

    public RegionTickContext registerRegion(final long regionId,
                                             final io.papermc.paper.threadedregions.ThreadedRegionizer
                                                     .ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) {
        RegionTickContext context = new RegionTickContext(regionId, region);
        this.activeContexts.put(regionId, context);
        // Mili 修复：注册 regionId → handle 映射，使跨 region DAG 节点能被正确路由
        if (region != null && region.getData() != null && region.getData().tickHandle != null) {
            fun.bm.mili.lmili.thread.regiontick.executor.FoliaRegionNodeScheduler
                    .FoliaRegionNodeSchedulerHandleRegistry
                    .register(regionId, region.getData().tickHandle);
        }
        return context;
    }

    public void unregisterRegion(final long regionId) {
        this.activeContexts.remove(regionId);
        this.chunkDispatcher.unregister(regionId);
        // Mili 修复：注销 regionId 映射
        fun.bm.mili.lmili.thread.regiontick.executor.FoliaRegionNodeScheduler
                .FoliaRegionNodeSchedulerHandleRegistry
                .unregister(regionId);
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
    public void dispatchTick(@NotNull final RegionTickContext context) {
        if (this.shutdown.get()) return;
        // Mili 修复：region tick 进入时先 drain 跨 region DAG pending 任务，
        // 确保跨 region 节点在目标 region 的合法 acquire/tickingRegion 上下文中执行
        if (this.dagExecutor != null) {
            this.dagExecutor.drainCrossRegionPending(context);
        }
        chunkDispatcher.dispatch(context);
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

    /**
     * 把 DAG 节点执行接入共享 MiliScheduler（单 runtime 修复 + DAG-region 协调修复）。
     *
     * <p>通常在 {@code RegionTickBootstrap.init()} 中调用。本 dispatcher 创建时 DAG 节点
     * 默认走 {@link SameRegionNodeScheduler}（同 region 快路径）；调用本方法后切换为
     * {@link CompositeNodeScheduler} —— 同 region 节点保留 tickingRegion 上下文直接执行，
     * 跨 region 节点重新入 Folia 调度（由目标 region 的 acquire 路径执行）。</p>
     *
     * <p><b>DAG-region 协调修复</b>：之前节点通过裸 {@code Executor} 执行，会绕过
     * Folia 的 region acquire 机制，破坏 tickingRegion 不变量。本方法保证节点
     * 派发严格遵守 region 约束（按 regionId 路由）。</p>
     *
     * <p>此操作幂等；多次调用会覆盖之前的绑定（仅最后一次生效）。</p>
     *
     * @param sharedScheduler 全 Mili 共享的 scheduler（必须非 null）
     */
    public synchronized void attachScheduler(@NotNull final MiliScheduler sharedScheduler) {
        if (this.shutdown.get()) {
            LOGGER.warn("[RegionTickPool] attachScheduler ignored — dispatcher already shut down");
            return;
        }
        if (sharedScheduler == this.attachedScheduler) {
            return; // 幂等
        }
        final NodeScheduler previous = this.dagNodeScheduler;
        // 同 region 快路径 + 跨 region Folia 调度 组合
        final CompositeNodeScheduler composite = new CompositeNodeScheduler(
                this.sameRegionScheduler, this.foliaRegionScheduler);
        this.dagNodeScheduler = composite;
        this.dagExecutor.setNodeScheduler(composite);
        this.attachedScheduler = sharedScheduler;
        LOGGER.info("[RegionTickPool] DAG node scheduler attached to shared MiliScheduler "
                + "(previous={}, new=CompositeNodeScheduler[same={}, folia={}])",
                previous.getClass().getSimpleName(),
                this.sameRegionScheduler.getClass().getSimpleName(),
                this.foliaRegionScheduler.getClass().getSimpleName());
    }

    /**
     * 获取跨 region DAG 调度器（用于注册/取消 region handle 映射）。
     */
    public FoliaRegionNodeScheduler getFoliaRegionNodeScheduler() {
        return foliaRegionScheduler;
    }

    /**
     * 获取当前 DAG 节点路由器（用于诊断）。
     */
    public NodeScheduler getNodeScheduler() {
        return dagNodeScheduler;
    }

    /**
     * 获取当前接入的共享 scheduler（用于诊断 / 关闭时确认）。可能为 null。
     */
    public MiliScheduler getAttachedScheduler() {
        return attachedScheduler;
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
        stats.put("chunk_tick_timeouts", chunkDispatcher.getTotalTimeouts());
        stats.put("async_catcher_refs", asyncCatcherManager.getRefCount());
        stats.put("shutdown", this.shutdown.get());
        return stats;
    }

    public void shutdown() {
        if (this.shutdown.getAndSet(true)) return;
        LOGGER.info("[RegionTickPool] Shutting down...");
        // Mili 修复：清空跨 region DAG 节点 handle 注册表，避免泄漏
        fun.bm.mili.lmili.thread.regiontick.executor.FoliaRegionNodeScheduler
                .FoliaRegionNodeSchedulerHandleRegistry.clear();
        poolManager.shutdown();
        this.activeContexts.clear();
        instance = null;
        LOGGER.info("[RegionTickPool] Shutdown complete");
    }
}
