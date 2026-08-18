package fun.bm.mili.lmili.thread.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.RegionTickDispatcher;
import fun.bm.mili.lmili.thread.regiontick.RegionTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.RegionTickSlice;
import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import fun.bm.mili.lmili.thread.scheduler.api.TaskHandle;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RegionTickDispatcher 适配器 —— 将现有 RegionTickDispatcher 桥接到新调度系统。
 *
 * <p>此适配器现在始终使用新调度系统，旧版调度路径已被移除。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 在 RegionTickDispatcher.init() 中
 * MiliScheduler scheduler = MiliSchedulerBuilder.create("region-scheduler")
 *     .carrierThreads(Runtime.getRuntime().availableProcessors())
 *     .build();
 * adapter = new RegionTickDispatcherAdapter(scheduler);
 * }</pre>
 */
public final class RegionTickDispatcherAdapter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 新调度器实例 */
    private final MiliScheduler scheduler;

    /** 旧版 dispatcher 引用（用于 entity tick） */
    private final RegionTickDispatcher legacyDispatcher;

    /**
     * 创建适配器。
     *
     * @param scheduler 新调度器实例
     * @param legacyDispatcher 旧版 dispatcher（用于 entity tick）
     */
    public RegionTickDispatcherAdapter(@NotNull MiliScheduler scheduler,
                                        @NotNull RegionTickDispatcher legacyDispatcher) {
        this.scheduler = scheduler;
        this.legacyDispatcher = legacyDispatcher;

        LOGGER.info("[RegionTickDispatcherAdapter] Created (new scheduler: {})",
                scheduler.getClass().getSimpleName());
    }

    /**
     * 创建适配器（自动创建默认新调度器）。
     *
     * @param legacyDispatcher 旧版 dispatcher
     */
    public RegionTickDispatcherAdapter(@NotNull RegionTickDispatcher legacyDispatcher) {
        this(createDefaultScheduler(), legacyDispatcher);
    }

    /**
     * 创建默认的新调度器。
     */
    private static @NotNull MiliScheduler createDefaultScheduler() {
        return MiliSchedulerBuilder.create("region-scheduler")
                .threadNamePrefix("MiliRegion-")
                .carrierThreads(Runtime.getRuntime().availableProcessors())
                .maxBlockingTasks(Math.max(2, Runtime.getRuntime().availableProcessors() / 2))
                .build();
    }

    /**
     * 分派 region tick —— 始终使用新调度路径。
     *
     * <p>这是适配器的主入口，替代 {@link RegionTickDispatcher#dispatchTick(RegionTickContext, long)}。
     *
     * @param context region tick 上下文
     * @param tickCount 当前 tick 数
     */
    public void dispatchTick(@NotNull RegionTickContext context, long tickCount) {
        dispatchTickNew(context, tickCount);
    }

    /**
     * 使用新调度系统分派 region tick。
     *
     * <p>核心逻辑：
     * <ol>
     *   <li>获取 region 的所有 chunk</li>
     *   <li>将 chunks 分组为 slices</li>
     *   <li>每个 slice 作为一个 RegionTask 提交到新调度器</li>
     *   <li>返回 CompletableFuture 追踪完成状态</li>
     * </ol>
     */
    private void dispatchTickNew(@NotNull RegionTickContext context, long tickCount) {
        if (context.regionId == 0L) return;

        var chunks = context.getOwnedChunks();
        int chunkCount = chunks.size();
        if (chunkCount == 0) return;

        // 小 region 直接同步执行
        if (chunkCount < 4) { // parallelismThreshold
            dispatchSingleSliceNew(context, chunks.toLongArray());
            return;
        }

        // 并行执行
        dispatchParallelNew(context, chunks.toLongArray(), chunkCount);
    }

    /**
     * 单 slice 同步 tick（新路径）。
     */
    private void dispatchSingleSliceNew(@NotNull RegionTickContext context, long[] chunkArray) {
        if (!context.tryBeginTick(1)) {
            LOGGER.debug("[RegionTickDispatcherAdapter] Region #{} single-slice tick skipped — already ticking",
                    context.regionId);
            return;
        }
        try {
            RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
            if (executor != null) {
                executor.executeSlice(null, new RegionTickSlice(context, chunkArray, 0), context);
            }
            context.arriveSlice();
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickDispatcherAdapter] Single-slice tick failed for region #{}",
                    context.regionId, throwable);
            context.arriveSlice();
            throw throwable;
        } finally {
            context.endTick();
        }
    }

    /**
     * 并行 tick（新路径）。
     *
     * <p>使用新调度器的 work-stealing 能力分配任务。
     */
    private void dispatchParallelNew(@NotNull RegionTickContext context, long[] chunkArray, int total) {
        int sliceSize = 16; // 与旧版保持一致
        int sliceCount = Math.max(1, (total + sliceSize - 1) / sliceSize);

        CompletableFuture<?>[] sliceFutures = new CompletableFuture[sliceCount];

        for (int i = 0; i < sliceCount; i++) {
            final int sliceIndex = i;
            int from = i * sliceSize;
            int to = Math.min(from + sliceSize, total);
            long[] sliceArray = java.util.Arrays.copyOfRange(chunkArray, from, to);
            RegionTickSlice slice = new RegionTickSlice(context, sliceArray, i);

            // 创建 RegionTask 并提交
            Runnable sliceTask = () -> {
                // 设置 region data 上下文
                try {
                    RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
                    if (executor != null) {
                        executor.executeSlice(null, slice, context);
                    }
                } catch (Throwable t) {
                    LOGGER.error("[RegionTickDispatcherAdapter] Slice #{} failed for region #{}",
                            sliceIndex, context.regionId, t);
                }
            };
            TaskHandle handle = scheduler.submit(RegionTask.builder(context.regionId)
                    .task(sliceTask)
                    .name("region-tick-" + context.regionId + "-slice-" + sliceIndex)
                    .build());

            sliceFutures[i] = handle.toCompletableFuture();
        }

        // 非阻塞：注册完成回调
        CompletableFuture.allOf(sliceFutures).whenComplete((result, throwable) -> {
            if (throwable != null) {
                LOGGER.error("[RegionTickDispatcherAdapter] Parallel tick failed for region #{}",
                        context.regionId, throwable);
            }
        });
    }

    /**
     * 分派 entity tick —— 始终使用旧路径（entity tick 必须在 region tick 线程上执行）。
     *
     * <p>Entity tick 依赖 Folia 的线程本地 region 数据，不能派发到其他线程。
     * 因此无论新旧路径，entity tick 都通过旧版 dispatcher 执行。
     */
    public void dispatchEntityTick(long regionId,
                                    @NotNull RegionTickContext context,
                                    @NotNull net.minecraft.server.level.ServerLevel level,
                                    @NotNull io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData) {
        legacyDispatcher.dispatchEntityTick(regionId, context, level, regionizedWorldData);
    }

    /**
     * 获取新调度器的性能快照。
     *
     * @return 性能快照，如果未使用新路径返回 null
     */
    public fun.bm.mili.lmili.thread.scheduler.api.PerformanceSnapshot getPerformanceSnapshot() {
        return scheduler.performanceSnapshot();
    }

    /**
     * 获取适配器的统计信息。
     */
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("schedulerType", "MiliScheduler");
        stats.put("legacyDispatcher", legacyDispatcher.getStats());
        if (scheduler instanceof MiliSchedulerImpl) {
            MiliSchedulerImpl impl = (MiliSchedulerImpl) scheduler;
            stats.put("newSchedulerMetrics", impl.metrics().diagnostics());
            stats.put("newSchedulerDiagnostics", impl.diagnostics().summary());
        }
        return stats;
    }

    /**
     * 优雅关闭适配器。
     *
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return true 如果正常关闭
     */
    public boolean shutdown(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        LOGGER.info("[RegionTickDispatcherAdapter] Shutting down...");
        boolean result = scheduler.shutdown(timeout, unit);
        LOGGER.info("[RegionTickDispatcherAdapter] Shutdown complete");
        return result;
    }

    /**
     * 获取新调度器实例。
     */
    @NotNull
    public MiliScheduler getScheduler() {
        return scheduler;
    }

    /**
     * 获取旧版 dispatcher 实例。
     */
    @NotNull
    public RegionTickDispatcher getLegacyDispatcher() {
        return legacyDispatcher;
    }
}
