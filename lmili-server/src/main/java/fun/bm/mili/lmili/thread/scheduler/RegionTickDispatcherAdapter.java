package fun.bm.mili.lmili.thread.scheduler;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.RegionTickDispatcher;
import fun.bm.mili.lmili.thread.regiontick.RegionTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.RegionTickSlice;
import fun.bm.mili.lmili.thread.runtime.generation.TickGeneration;
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
 * <h3>Generation 安全</h3>
 * <ul>
 *   <li>使用新的 TickGeneration 生命周期管理</li>
 *   <li>每个 slice 创建时绑定 generationId</li>
 *   <li>完成时使用 generationId 验证</li>
 * </ul>
 */
public final class RegionTickDispatcherAdapter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 新调度器实例 */
    private final MiliScheduler scheduler;

    /** 旧版 dispatcher 引用（用于 entity tick） */
    private final RegionTickDispatcher legacyDispatcher;

    public RegionTickDispatcherAdapter(@NotNull MiliScheduler scheduler,
                                        @NotNull RegionTickDispatcher legacyDispatcher) {
        this.scheduler = scheduler;
        this.legacyDispatcher = legacyDispatcher;

        LOGGER.info("[RegionTickDispatcherAdapter] Created (new scheduler: {})",
                scheduler.getClass().getSimpleName());
    }

    public RegionTickDispatcherAdapter(@NotNull RegionTickDispatcher legacyDispatcher) {
        this(createDefaultScheduler(), legacyDispatcher);
    }

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
     * @param context region tick 上下文
     * @param tickCount 当前 tick 数（用于兼容性，实际不使用）
     */
    public void dispatchTick(@NotNull RegionTickContext context, long tickCount) {
        dispatchTickNew(context);
    }

    /**
     * 使用新调度系统分派 region tick。
     */
    private void dispatchTickNew(@NotNull RegionTickContext context) {
        if (context.regionId == 0L) return;

        var chunks = context.getOwnedChunks();
        int chunkCount = chunks.size();
        if (chunkCount == 0) return;

        // 小 region 直接同步执行
        if (chunkCount < 4) {
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
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
        TickGeneration gen = context.tryBeginTick(1, deadline);
        if (gen == null) {
            LOGGER.debug("[RegionTickDispatcherAdapter] Region #{} single-slice tick skipped — already ticking",
                    context.regionId);
            return;
        }

        final long generationId = gen.generationId();
        try {
            RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
            if (executor != null) {
                RegionTickSlice slice = new RegionTickSlice(context, chunkArray, 0, generationId);
                executor.executeSlice(null, slice, context);
            }
            context.arriveSlice(generationId);
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickDispatcherAdapter] Single-slice tick failed for region #{}",
                    context.regionId, throwable);
            context.failSlice(generationId, throwable);
        } finally {
            context.endTick();
        }
    }

    /**
     * 并行 tick（新路径）。
     */
    private void dispatchParallelNew(@NotNull RegionTickContext context, long[] chunkArray, int total) {
        int sliceSize = 16;
        int sliceCount = Math.max(1, (total + sliceSize - 1) / sliceSize);

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
        TickGeneration gen = context.tryBeginTick(sliceCount, deadline);
        if (gen == null) {
            LOGGER.debug("[RegionTickDispatcherAdapter] Region #{} parallel tick skipped — already ticking",
                    context.regionId);
            return;
        }

        final long generationId = gen.generationId();

        CompletableFuture<?>[] sliceFutures = new CompletableFuture[sliceCount];

        for (int i = 0; i < sliceCount; i++) {
            final int sliceIndex = i;
            int from = i * sliceSize;
            int to = Math.min(from + sliceSize, total);
            long[] sliceArray = java.util.Arrays.copyOfRange(chunkArray, from, to);
            RegionTickSlice slice = new RegionTickSlice(context, sliceArray, i, generationId);

            Runnable sliceTask = () -> {
                try {
                    RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
                    if (executor != null) {
                        executor.executeSlice(null, slice, context);
                    }
                } catch (Throwable t) {
                    LOGGER.error("[RegionTickDispatcherAdapter] Slice #{} failed for region #{}",
                            sliceIndex, context.regionId, t);
                    context.failSlice(generationId, t);
                } finally {
                    context.arriveSlice(generationId);
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
            context.endTick();
        });
    }

    /**
     * 分派 entity tick —— 始终使用旧路径（entity tick 必须在 region tick 线程上执行）。
     */
    public void dispatchEntityTick(long regionId,
                                    @NotNull RegionTickContext context,
                                    @NotNull net.minecraft.server.level.ServerLevel level,
                                    @NotNull io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData) {
        legacyDispatcher.dispatchEntityTick(regionId, context, level, regionizedWorldData);
    }

    public fun.bm.mili.lmili.thread.scheduler.api.PerformanceSnapshot getPerformanceSnapshot() {
        return scheduler.performanceSnapshot();
    }

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

    public boolean shutdown(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        LOGGER.info("[RegionTickDispatcherAdapter] Shutting down...");
        boolean result = scheduler.shutdown(timeout, unit);
        LOGGER.info("[RegionTickDispatcherAdapter] Shutdown complete");
        return result;
    }

    @NotNull
    public MiliScheduler getScheduler() {
        return scheduler;
    }

    @NotNull
    public RegionTickDispatcher getLegacyDispatcher() {
        return legacyDispatcher;
    }
}
