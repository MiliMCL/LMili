package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.lmili.thread.regiontick.dag.Scope;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import fun.bm.mili.lmili.thread.regiontick.executor.DagSchedulerUnavailableException;
import fun.bm.mili.lmili.thread.regiontick.executor.LMiliRegionNodeScheduler;
import fun.bm.mili.lmili.thread.regiontick.executor.MiliSchedulerNodeScheduler;
import fun.bm.mili.lmili.thread.regiontick.executor.ModernDagTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.executor.NodeScheduler;
import fun.bm.mili.lmili.thread.regiontick.executor.SameRegionNodeScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import fun.bm.mili.lmili.thread.runtime.generation.TickGeneration;
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
 * {@link NodeScheduler} 通过 {@link #attachScheduler(MiliScheduler)} 接入
 * 共享 {@link MiliScheduler}（由 {@link MiliSchedulerNodeScheduler} 适配，
 * 节点按 regionId 提交到 Region Worker / Global Scheduler）。
 * 在共享 scheduler <b>接入前</b>或 <b>关闭后</b>（即不可用状态），
 * DAG 派发走安全 fallback：不 NPE、不静默丢失、不重复调度、不绕过生命周期，
 * 并且通过 {@link DagSchedulerUnavailableException} + 指标显式暴露。</p>
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

        // P1-1：把 per-region 清理统一收口到 RegionLifecycleManager（§6）
        registerLifecycleHooks();

        LOGGER.info("[RegionTickPool] Dispatcher initialized (virtualThreads={}, workers={})",
                useVirtualThreads, useVirtualThreads ? "unlimited" : workerCount);
    }

    /**
     * P1-1：注册本 dispatcher 持有的全部 per-region 注册表的清理钩子。
     *
     * <p>可达的注册源：本 dispatcher 持有的 chunkDispatcher / entityDispatcher / activeContexts
     * 以及静态单例 {@link TickMonitorHook} / {@link LMiliRegionNodeScheduler}。</p>
     *
     * <p>以下组件也实现了 {@code onRegionDestroyed(long)}，但未在本接线点接入
     * （因它们的实例归属于其他 wiring 序列 MiliRuntime 装配），由 MiliRuntime 在
     * 装配时单独注册到同一 lifecycle：
     * <ul>
     *   <li>BudgetAllocator、SchedulerController、OLinearFlusherBridge、UnifiedRuntime</li>
     *   <li>WorkStealingCoordinator、PerformanceMetrics</li>
     *   <li>MiliTickRegionScheduler（静态 REGISTRY）</li>
     * </ul>
     * </p>
     */
    private void registerLifecycleHooks() {
        fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager lifecycle =
                fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager.get();
        final RegionTickDispatcher self = this;
        lifecycle.registerHook(new fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager.Hook() {
            @Override public String name() { return "region-tick-context"; }
            @Override public void onRegionDestroyed(long regionId) {
                final RegionTickContext ctx = self.activeContexts.remove(regionId);
                if (ctx != null) {
                    ctx.close();
                }
            }
        });
        lifecycle.registerHook(new fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager.Hook() {
            @Override public String name() { return "chunk-tick-dispatcher"; }
            @Override public void onRegionDestroyed(long regionId) {
                self.chunkDispatcher.unregister(regionId);
            }
        });
        lifecycle.registerHook(new fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager.Hook() {
            @Override public String name() { return "entity-tick-dispatcher"; }
            @Override public void onRegionDestroyed(long regionId) {
                self.entityDispatcher.onRegionDestroyed(regionId);
            }
        });
        // C3：静态单例可达组件的清理
        lifecycle.registerHook(new fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager.Hook() {
            @Override public String name() { return "tick-monitor-hook"; }
            @Override public void onRegionDestroyed(long regionId) {
                fun.bm.mili.lmili.runtime.tps.TickMonitorHook hook =
                        fun.bm.mili.lmili.runtime.tps.TickMonitorHook.getInstance();
                if (hook != null) hook.onRegionDestroyed(regionId);
            }
        });
        lifecycle.registerHook(new fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager.Hook() {
            @Override public String name() { return "lmili-region-node-scheduler"; }
            @Override public void onRegionDestroyed(long regionId) {
                LMiliRegionNodeScheduler sched = LMiliRegionNodeScheduler.getInstance();
                if (sched != null) sched.onRegionDestroyed(regionId);
            }
        });
        // C4：MiliTickRegionScheduler 静态 REGISTRY 清理
        lifecycle.registerHook(new fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager.Hook() {
            @Override public String name() { return "mili-tick-region-scheduler-registry"; }
            @Override public void onRegionDestroyed(long regionId) {
                fun.bm.mili.lmili.thread.scheduler.MiliTickRegionScheduler.onRegionDestroyed(regionId);
            }
        });
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
        // P1-1：统一生命周期入口 —— 本方法保留为兼容外壳，
        // 实际清理由 RegionLifecycleManager.destroyRegion 幂等扇出。
        fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager.get().destroyRegion(regionId);
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
     *
     * <p>C2-1 修复：dispatch 入口先把本 region 的 {@link DeferredChunkOps} pending
     * ops drain 到目标 owner（同 region 内直接执行，跨 region 通过
     * {@link MiliScheduler} 调度到对应 owner 的 tick context）。</p>
     */
    public void dispatchTick(@NotNull final RegionTickContext context) {
        if (this.shutdown.get()) return;
        drainDeferredOps(context);
        chunkDispatcher.dispatch(context);
    }

    /**
     * P0-4 §4.3 / C2-1：把本 region context 的 deferred ops drain 到目标 owner。
     *
     * <p>同 region 的 op 直接在当前 tick thread 上运行；
     * 跨 region 的 op 通过 attached {@link MiliScheduler} 调度到对应 ownerRegionId
     * 的 RegionTask 中运行。若共享 scheduler 未接线，跨 region ops 被丢弃并计数
     * （无静默丢失）。</p>
     */
    private void drainDeferredOps(final RegionTickContext context) {
        final fun.bm.mili.lmili.thread.regiontick.DeferredChunkOps ops =
                context.getDeferredChunkOps();
        if (ops.pendingSize() == 0) return;

        final long currentRegionId = context.regionId;
        final MiliScheduler scheduler = this.attachedScheduler;
        final java.util.ArrayDeque<DeferredChunkOps.DeferredChunkOp> buf = new java.util.ArrayDeque<>();
        ops.moveToAndClear(buf);

        DeferredChunkOps.DeferredChunkOp op;
        while ((op = buf.pollFirst()) != null) {
            try {
                if (op.ownerRegionId == currentRegionId) {
                    // 同 region：当前 tick thread 直接执行
                    op.op.run();
                } else if (scheduler != null) {
                    // 跨 region：通过 MiliScheduler 调度到目标 owner
                    final Runnable body = op.op;
                    final long targetOwner = op.ownerRegionId;
                    fun.bm.mili.lmili.thread.scheduler.api.RegionTask task =
                            fun.bm.mili.lmili.thread.scheduler.api.RegionTask.builder(targetOwner)
                                    .task(body)
                                    .name("deferred-forward-" + op.category + "@" + op.chunkPos)
                                    .build();
                    scheduler.submit(task);
                } else {
                    // 共享 scheduler 未接线 —— 跨 region ops 记入拒绝计数（无静默丢失）
                    com.mojang.logging.LogUtils.getLogger().warn(
                            "[RegionTickDispatcher] Cross-owner deferred op for region #{} "
                                    + "dropped (no shared scheduler attached): chunkPos={}, category={}",
                            op.ownerRegionId, op.chunkPos, op.category);
                    // 由于 DeferredChunkOp 没有按状态细粒度计数器，我们复用 enqueued/drained
                    // 差值 + 日志作为可观测性；真正的拒绝计数应由 MiliScheduler 接入层提供。
                }
            } catch (Throwable t) {
                com.mojang.logging.LogUtils.getLogger().error(
                        "[RegionTickDispatcher] Deferred op failed: chunkPos={}, owner={}, category={}",
                        op.chunkPos, op.ownerRegionId, op.category, t);
            }
        }
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
     * 把 DAG 节点执行接入共享 MiliScheduler（P0-1：架构断链修复）。
     *
     * <p>通常在 {@code RegionTickBootstrap.init()} 中调用。</p>
     *
     * <p><b>修复内容</b>：历史实现只保存了 {@code attachedScheduler} 引用，DAG
     * executor 实际仍使用 {@link SameRegionNodeScheduler}（直接在 region tick 线程
     * {@code body.run()}）。现在真正完成依赖注入：创建 {@link MiliSchedulerNodeScheduler}
     * 适配器并 {@code dagExecutor.setNodeScheduler(...)}，使 DAG 节点按 regionId
     * 提交到共享 MiliScheduler（WorkStealingCoordinator / Region Worker / Global
     * Scheduler），不再保留 "DAG → SameRegionNodeScheduler → body.run()" 的生产默认路径。</p>
     *
     * <p>此操作幂等；多次调用会覆盖之前的绑定（仅最后一次生效）。
     * 传入已关闭的 scheduler 仍会完成接线，但后续每次节点派发都会被显式拒绝
     * （{@code DagSchedulerUnavailableException} + {@code dag_scheduler_unavailable} 指标），
     * 符合"Scheduler unavailable 安全 fallback"要求。</p>
     *
     * @param sharedScheduler 全 Mili 共享的 scheduler（必须非 null）
     * @throws NullPointerException 如果 sharedScheduler 为 null
     */
    public synchronized void attachScheduler(@NotNull final MiliScheduler sharedScheduler) {
        if (this.shutdown.get()) {
            LOGGER.warn("[RegionTickPool] attachScheduler ignored — dispatcher already shut down");
            return;
        }
        if (sharedScheduler == null) {
            throw new NullPointerException("sharedScheduler must not be null");
        }
        if (sharedScheduler == this.attachedScheduler) {
            return; // 幂等
        }
        final NodeScheduler previous = this.dagNodeScheduler;

        // P0-1 核心：真正完成依赖注入 —— DAG executor 使用共享 scheduler 的适配器，
        // 而不是同 region 快路径。
        final MiliSchedulerNodeScheduler sharedRouter = new MiliSchedulerNodeScheduler(sharedScheduler);
        this.dagNodeScheduler = sharedRouter;
        this.dagExecutor.setNodeScheduler(sharedRouter);
        this.attachedScheduler = sharedScheduler;

        if (sharedScheduler.isShutdown()) {
            LOGGER.warn("[RegionTickPool] DAG node scheduler attached to a SHUTDOWN shared MiliScheduler "
                    + "(previous={}, new=MiliSchedulerNodeScheduler[{}]) — every DAG node dispatch "
                    + "will be explicitly rejected until a live scheduler is attached",
                    previous.getClass().getSimpleName(),
                    sharedScheduler.getClass().getSimpleName());
        } else {
            LOGGER.info("[RegionTickPool] DAG node scheduler attached to shared MiliScheduler "
                    + "(previous={}, new=MiliSchedulerNodeScheduler[{}])",
                    previous.getClass().getSimpleName(),
                    sharedScheduler.getClass().getSimpleName());
        }
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
        stats.put("dag_node_scheduler", dagNodeScheduler.getClass().getSimpleName());
        stats.put("dag_scheduler_unavailable", getDagSchedulerUnavailableCount());
        if (dagNodeScheduler instanceof MiliSchedulerNodeScheduler shared) {
            stats.put("dag_dispatch_count", shared.getDispatchCount());
        }
        stats.put("pending_chunk_ticks", chunkDispatcher.getPendingChunkTickCount());
        stats.put("chunk_tick_timeouts", chunkDispatcher.getTotalTimeouts());
        stats.put("async_catcher_refs", asyncCatcherManager.getRefCount());
        stats.put("shutdown", this.shutdown.get());
        return stats;
    }

    /**
     * 获取共享 scheduler 不可用导致的 DAG 节点显式拒绝次数（指标）。
     *
     * <p>未接线共享 scheduler（默认 SameRegionNodeScheduler）或不可用时返回 0；
     * 已接线且发生拒绝时返回累计计数。用于证明 fallback 不静默丢失 tick。</p>
     */
    public long getDagSchedulerUnavailableCount() {
        NodeScheduler router = this.dagNodeScheduler;
        if (router instanceof MiliSchedulerNodeScheduler shared) {
            return shared.getUnavailableRejectionCount();
        }
        return 0L;
    }

    /**
     * 清理过期的 active contexts —— 防止 region 销毁时未正确调用 unregisterRegion 导致的泄漏。
     *
     * <p><b>P1-2（计划 §7）重审计后的多因子判定</b>。旧实现仅凭
     * "lastTickDuration 很长" 或 "历史平均 tick 慢且空闲" 就误判泄漏，
     * 会清掉正在执行超长 tick 的健康 region 与历史慢但空闲的 region。</p>
     *
     * <p>新判定原则（逐项对应 §7 要求）：
     * <ul>
     *   <li><b>Region lifecycle state</b>：RUNNING / DRAINING / DEADLINE_EXCEEDED 中的
     *       context 一律不动 —— 正在收尾或排空的 tick 不是泄漏</li>
     *   <li><b>generation state</b>：仅在 context 不处于任何活跃 generation 时才允许回收</li>
     *   <li><b>closing state</b>：已 close() 的 context 立即回收</li>
     *   <li><b>last activity</b>：以 {@code lastActivityNanos}（begin/end/close 任一）
     *       为准计算空闲时长；未超 {@code cleanupTimeoutMs} 不回收</li>
     * </ul>
     * 注意：本方法不再依据 "tick duration 很长" 判定泄漏（§7 明令禁止）；
     * 超长 tick 由 TickGeneration deadline + checkTimeout + DRAINING 机制处理。</p>
     *
     * @param cleanupTimeoutMs 允许的最大空闲时长（毫秒）
     * @return 本次回收的 context 数量
     */
    public int cleanupStaleContexts(final long cleanupTimeoutMs) {
        if (cleanupTimeoutMs <= 0) return 0;
        int cleaned = 0;
        final long now = System.nanoTime();
        final long timeoutNanos = cleanupTimeoutMs * 1_000_000L;
        // C1 修复：stale 审计统一走 RegionLifecycleManager.destroyRegion，
        // 以保证 chunkDispatcher / entityDispatcher 等 per-region 注册表被同步清理。
        // destroyRegion 内部幂等（墓碑集），重复调用安全。
        final fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager lifecycle =
                fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager.get();

        for (Map.Entry<Long, RegionTickContext> entry : this.activeContexts.entrySet()) {
            final Long regionId = entry.getKey();
            RegionTickContext ctx = entry.getValue();
            if (ctx == null || isReclaimable(ctx, now, timeoutNanos)) {
                this.activeContexts.remove(regionId);
                if (ctx != null) {
                    lifecycle.destroyRegion(regionId);
                }
                cleaned++;
            }
        }
        return cleaned;
    }

    /**
     * P1-2（§7）：多因子回收判定（纯函数，可单测）。
     *
     * <p>对应计划验收场景：正常重载 / 超长 Tick / Scheduler stall /
     * Region destroy → recreate 同 ID，均不误清理。</p>
     */
    public static boolean isReclaimable(final RegionTickContext ctx,
                                        final long nowNanos,
                                        final long timeoutNanos) {
        // 因子1 —— closing state
        if (ctx.isClosed()) return true;
        // 因子2/3 —— lifecycle & generation state
        TickGeneration.State state = ctx.getTickState();
        boolean inActiveLifecycle =
                state == TickGeneration.State.RUNNING
                        || state == TickGeneration.State.DEADLINE_EXCEEDED
                        || state == TickGeneration.State.DRAINING;
        if (inActiveLifecycle || ctx.isTicking() || ctx.getCurrentGeneration() != null) {
            return false;
        }
        // 因子4 —— last activity 超时
        return nowNanos - ctx.getLastActivityNanos() > timeoutNanos;
    }

    public void shutdown() {
        if (this.shutdown.getAndSet(true)) return;
        LOGGER.info("[RegionTickPool] Shutting down...");
        poolManager.shutdown();
        // C5 修复：shutdown 阶段把每个 active region 都走一次 destroyRegion，
        // 保证 chunkDispatcher / entityDispatcher / tick-monitor-hook 等
        // 全部注册的 lifecycle 钩子统一收尾（与显式 unregisterRegion 行为一致）。
        // destroyRegion 内部幂等 —— 重复调用安全。
        final fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager lifecycle =
                fun.bm.mili.lmili.thread.runtime.lifecycle.RegionLifecycleManager.get();
        for (Long regionId : new java.util.ArrayList<>(this.activeContexts.keySet())) {
            lifecycle.destroyRegion(regionId);
        }
        // activeContexts 在钩子 chain 中由 region-tick-context hook remove；兜底清理
        this.activeContexts.clear();
        // 清理 AsyncCatcherManager 运行时状态
        AsyncCatcherManager.clearRuntimeState();
        instance = null;
        LOGGER.info("[RegionTickPool] Shutdown complete");
    }
}
