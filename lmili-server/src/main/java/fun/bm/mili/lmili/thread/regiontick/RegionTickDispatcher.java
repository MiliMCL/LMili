package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.lmili.thread.regiontick.dag.Scope;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import fun.bm.mili.lmili.thread.regiontick.executor.ModernDagTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.executor.NodeScheduler;
import fun.bm.mili.lmili.thread.regiontick.executor.SameRegionNodeScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import io.papermc.paper.threadedregions.RegionizedWorldData;
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
 * <p><b>DAG-region 协调</b>：DAG 节点执行（{@link ModernDagTickExecutor}）的
 * {@link NodeScheduler} 使用 {@link SameRegionNodeScheduler} 在合法的
 * region tick 上下文中执行节点。</p>
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

    /** DAG 节点路由器（按 regionId 路由节点）。 */
    private volatile NodeScheduler dagNodeScheduler;

    /** 同 region 快路径调度器。 */
    private final SameRegionNodeScheduler sameRegionScheduler;

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

        // 默认路由器走"同 region 快路径"
        this.sameRegionScheduler = new SameRegionNodeScheduler();
        this.dagNodeScheduler = this.sameRegionScheduler;
        this.dagExecutor = new ModernDagTickExecutor(this.dagNodeScheduler);

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
                                                     .ThreadedRegion<io.papermc.paper.threadedregions.TickRegions.TickRegionData, io.papermc.paper.threadedregions.TickRegions.TickRegionSectionData> region) {
        RegionTickContext context = new RegionTickContext(regionId, region);
        this.activeContexts.put(regionId, context);
        return context;
    }

    public void unregisterRegion(final long regionId) {
        // R4-修复: 关闭 context 释放资源，而不仅仅是 remove
        final RegionTickContext ctx = this.activeContexts.remove(regionId);
        if (ctx != null) {
            ctx.close();
        }
        this.chunkDispatcher.unregister(regionId);
        // 通知 EntityTickDispatcher 清理该 region 的 per-region 缓存
        this.entityDispatcher.onRegionDestroyed(regionId);
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
     * 把 DAG 节点执行接入共享 MiliScheduler（单 runtime 修复）。
     *
     * <p>通常在 {@code RegionTickBootstrap.init()} 中调用。</p>
     *
     * <p>此操作幂等；多次调用会覆盖之前的绑定（仅最后一次生效）。</p>
     *
     * @param sharedScheduler 全 Mili共享的 scheduler（必须非 null）
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
        this.dagExecutor.setNodeScheduler(this.sameRegionScheduler);
        this.attachedScheduler = sharedScheduler;
        LOGGER.info("[RegionTickPool] DAG node scheduler attached to shared MiliScheduler "
                + "(previous={}, new=SameRegionNodeScheduler)",
                previous.getClass().getSimpleName());
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

    /**
     * 清理过期的 active contexts —— 防止 region 销毁时未正确调用 unregisterRegion 导致的泄漏。
     *
     * <p>扫描所有 active contexts，如果某个 context 长时间没有完成 tick（超过 cleanupTimeoutMs），
     * 则认为它已过期并清理。
     *
     * @param cleanupTimeoutMs 超时时间（毫秒），超过此时间未完成的 context 将被清理
     */
    public int cleanupStaleContexts(final long cleanupTimeoutMs) {
        if (cleanupTimeoutMs <= 0) return 0;
        int cleaned = 0;
        final long now = System.nanoTime();
        final long timeoutNanos = cleanupTimeoutMs * 1_000_000L;

        for (Map.Entry<Long, RegionTickContext> entry : this.activeContexts.entrySet()) {
            RegionTickContext ctx = entry.getValue();
            if (ctx == null) {
                this.activeContexts.remove(entry.getKey());
                cleaned++;
                continue;
            }
            // 检查 context 是否长时间没有完成 tick
            long lastTickDuration = ctx.getLastTickDurationNanos();
            boolean isStale = false;

            // 如果 context 处于 RUNNING 状态且耗时超过阈值
            if (ctx.isTicking() && lastTickDuration > timeoutNanos) {
                LOGGER.warn("[RegionTickPool] Cleaning up stale context in region #{}: ticking for {}ms",
                        entry.getKey(), lastTickDuration / 1_000_000);
                isStale = true;
            }
            // 如果 context 已经完成但很长时间没有新 tick
            if (ctx.getMaxTickDurationMs() > 0) {
                long avgTickMs = ctx.getAverageTickDurationMs();
                // 如果平均 tick 时间很长且当前没有活跃 tick，可能是泄漏
                if (avgTickMs > 100 && !ctx.isTicking() && lastTickDuration == 0) {
                    isStale = true;
                }
            }

            if (isStale) {
                this.activeContexts.remove(entry.getKey());
                ctx.close();
                cleaned++;
            }
        }
        return cleaned;
    }

    public void shutdown() {
        if (this.shutdown.getAndSet(true)) return;
        LOGGER.info("[RegionTickPool] Shutting down...");
        poolManager.shutdown();
        // 修复：关闭所有 active contexts 释放资源
        for (RegionTickContext ctx : this.activeContexts.values()) {
            if (ctx != null) {
                ctx.close();
            }
        }
        this.activeContexts.clear();
        // 清理 AsyncCatcherManager 运行时状态
        AsyncCatcherManager.clearRuntimeState();
        instance = null;
        LOGGER.info("[RegionTickPool] Shutdown complete");
    }
}
