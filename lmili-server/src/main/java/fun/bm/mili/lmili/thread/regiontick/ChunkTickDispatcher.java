package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.runtime.AdaptiveSlicer;
import fun.bm.mili.lmili.thread.runtime.SliceCostCalculator;
import fun.bm.mili.lmili.thread.runtime.generation.TickGeneration;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chunk Tick 分派器 —— 管理 region 内部的并行 chunk tick。
 *
 * <p>根据区域 chunk 数量决定执行策略：
 * <ul>
 *   <li>chunk 数低于 parallelismThreshold：单线程同步执行（无调度开销）</li>
 *   <li>virtual 模式：多 virtual thread 并行执行 slices</li>
 *   <li>platform 模式：分发给 worker 队列执行</li>
 * </ul>
 *
 * <h3>Generation 保证</h3>
 * <ul>
 *   <li>每个 slice 创建时绑定 generationId</li>
 *   <li>完成时使用 generationId 验证</li>
 *   <li>超时/cancel 不会影响新 Generation</li>
 * </ul>
 *
 * <h3>稳定性保证</h3>
 * <ul>
 *   <li>所有路径都有 try-finally 保护，确保 tick 状态始终恢复</li>
 *   <li>超时后进入 DRAINING → CANCELLED 状态机</li>
 *   <li>单个 slice 失败不影响其他 slice 执行</li>
 *   <li>资源泄漏防护：asyncCatcher 引用计数始终正确释放</li>
 * </ul>
 */
public final class ChunkTickDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SLOW_TICK_THRESHOLD_MS = 50;
    // 超时时间：4秒（留1秒给 watchdog）
    private static final long TICK_TIMEOUT_MS = 4000;
    // Tick 间隔：50ms (20 TPS)
    private static final long TICK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final WorkerPoolManager poolManager;
    private final int parallelismThreshold;
    private final int sliceSize;
    private final AsyncCatcherManager asyncCatcherManager;
    private final RegionDiagnostics diagnostics;

    // P1: Adaptive Slicer 用于动态 slice 大小调整
    private final AdaptiveSlicer adaptiveSlicer;
    private final ConcurrentHashMap<Long, AdaptiveSlicer.RegionAdaptiveSlicer> regionSlicers = new ConcurrentHashMap<>();

    // 并行 chunk tick 追踪
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingChunkFutures = new ConcurrentHashMap<>();

    // 全局统计
    private final AtomicInteger totalTimeouts = new AtomicInteger(0);
    private final AtomicInteger totalErrors = new AtomicInteger(0);

    public ChunkTickDispatcher(WorkerPoolManager poolManager,
                                int parallelismThreshold,
                                int sliceSize,
                                AsyncCatcherManager asyncCatcherManager,
                                RegionDiagnostics diagnostics) {
        this.poolManager = poolManager;
        this.parallelismThreshold = parallelismThreshold;
        this.sliceSize = sliceSize;
        this.asyncCatcherManager = asyncCatcherManager;
        this.diagnostics = diagnostics;
        this.adaptiveSlicer = new AdaptiveSlicer();
    }

    /**
     * 分派 region chunk tick 任务 —— 主入口。
     *
     * <p>此方法在 region tick 线程上调用，会阻塞直到所有 chunk tick 完成或超时。</p>
     */
    public void dispatch(@NotNull final RegionTickContext context) {
        if (context.regionId == 0L) return;

        var chunks = context.getOwnedChunks();
        int chunkCount = chunks.size();
        if (chunkCount == 0) return;

        long startNanos = System.nanoTime();
        try {
            if (chunkCount < parallelismThreshold) {
                dispatchSingleSlice(context, chunks.toLongArray());
            } else if (poolManager.isVirtualThreadMode()) {
                dispatchParallelVirtual(context, chunks.toLongArray());
            } else {
                dispatchParallelPlatform(context, chunks.toLongArray());
            }
        } catch (Throwable throwable) {
            diagnostics.recordError();
            totalErrors.incrementAndGet();
            LOGGER.error("[RegionTickPool] dispatchTick failed for region #{}", context.regionId, throwable);
            // 确保 tick 状态恢复
            context.forceReset();
        } finally {
            long elapsed = System.nanoTime() - startNanos;
            diagnostics.recordTick(elapsed);

            // 慢 tick 告警
            long elapsedMs = elapsed / 1_000_000;
            if (elapsedMs > SLOW_TICK_THRESHOLD_MS) {
                LOGGER.warn("[RegionTickPool] SLOW tick in region #{}: {}ms (chunks={}, mode={})",
                        context.regionId, elapsedMs, chunkCount,
                        poolManager.isVirtualThreadMode() ? "virtual" : "platform");
            }

            // 诊断日志（按配置间隔输出，0 为禁用）
            final int statsInterval = fun.bm.mili.config.modules.experiment.RegionTickPoolConfig.statsLogInterval;
            if (statsInterval > 0) {
                long total = diagnostics.getTotalTicksDispatched();
                if (total % statsInterval == 0L) {
                    LOGGER.info("[RegionTickPool] Stats: total_ticks={}, errors={}, timeouts={}, max_tick_ms={}, pending={}",
                            total, diagnostics.getTotalErrors(), totalTimeouts.get(),
                            diagnostics.getMaxTickDurationMs(), pendingChunkFutures.size());
                }
            }
        }
    }

    /**
     * 单 slice 同步 tick —— 小 region 专用。
     */
    private void dispatchSingleSlice(@NotNull final RegionTickContext context, final long[] chunkArray) {
        long deadline = System.nanoTime() + TICK_INTERVAL_NANOS;
        TickGeneration gen = context.tryBeginTick(1, deadline);
        if (gen == null) {
            LOGGER.debug("[RegionTickPool] Region #{} single-slice tick skipped — already ticking",
                    context.regionId);
            return;
        }

        long generationId = gen.generationId();
        try {
            RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
            if (executor != null) {
                RegionTickSlice slice = new RegionTickSlice(context, chunkArray, 0, generationId);
                executor.executeSlice(null, slice, context);
            }
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Single-slice tick failed for region #{}", context.regionId, throwable);
            context.failSlice(generationId, throwable);
        } finally {
            // 确保 slice 完成计数和 tick 状态恢复
            context.arriveSlice(generationId);
            context.endTick();
        }
    }

    /**
     * Virtual Thread 并行模式 —— 将 chunks 拆分为多个 slice，
     * 每个 slice 作为一个 virtual thread 并行执行。
     *
     * <p><b>稳定性保证</b>：
     * <ul>
     *   <li>asyncCatcher 引用计数在 finally 中释放</li>
     *   <li>超时后进入 DRAINING 状态</li>
     *   <li>单个 slice 失败不影响其他 slice</li>
     *   <li>旧 Generation 的迟到完成不会影响新 Generation</li>
     * </ul>
     *
     * <h3>P1 Adaptive Slicing</h3>
     * <p>根据 {@link AdaptiveSlicer} 动态计算 slice 大小，
     * 目标约 2-4ms estimated work/slice。
     */
    private void dispatchParallelVirtual(@NotNull final RegionTickContext context, final long[] chunkArray) {
        final long regionId = context.regionId;
        int total = chunkArray.length;

        // P1: 使用 Adaptive Slicer 动态计算 slice 大小
        int dynamicSliceSize = calculateDynamicSliceSize(regionId, total);
        int actualSliceSize = Math.max(1, Math.min(sliceSize, dynamicSliceSize));
        int sliceCount = Math.max(1, (total + actualSliceSize - 1) / actualSliceSize);

        long deadline = System.nanoTime() + TICK_INTERVAL_NANOS;
        TickGeneration gen = context.tryBeginTick(sliceCount, deadline);
        if (gen == null) {
            LOGGER.debug("[RegionTickPool] Region #{} virtual tick skipped — already ticking", regionId);
            return;
        }

        final long generationId = gen.generationId();

        // 捕获当前 region 的 RegionizedWorldData
        final RegionizedWorldData regionData = captureRegionData(context);
        if (regionData == null) {
            LOGGER.warn("[RegionTickPool] No region data for virtual dispatch in region #{} — falling back to single-slice",
                    regionId);
            fallbackSingleSlice(context, chunkArray, generationId);
            return;
        }

        // 创建 CompletableFuture 数组追踪所有 slice
        CompletableFuture<Void>[] sliceFutures = new CompletableFuture[sliceCount];

        // 在 try 块之前获取 asyncCatcher，确保 finally 中释放
        asyncCatcherManager.acquire();
        try {
            // 提交所有 slice 任务
            for (int i = 0; i < sliceCount; i++) {
                int from = i * actualSliceSize;
                int to = Math.min(from + actualSliceSize, total);
                long[] sliceArray = java.util.Arrays.copyOfRange(chunkArray, from, to);
                RegionTickSlice slice = new RegionTickSlice(context, sliceArray, i, generationId);
                final int sliceIndex = i;
                final int chunksInSlice = to - from;

                sliceFutures[i] = CompletableFuture.runAsync(() -> {
                    // 在虚拟线程中设置 region data 回退
                    RegionDataThreadLocal.setCurrent(regionData);
                    long sliceStartNanos = System.nanoTime();
                    try {
                        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
                        if (executor != null) {
                            executor.executeSlice(null, slice, context);
                        }
                    } catch (Throwable throwable) {
                        LOGGER.error("[RegionTickPool] Virtual slice #{} failed for region #{}",
                                sliceIndex, regionId, throwable);
                        context.failSlice(generationId, throwable);
                    } finally {
                        RegionDataThreadLocal.clear();
                        // P1: 记录 slice 执行时间用于 Adaptive Slicing
                        long sliceElapsedNanos = System.nanoTime() - sliceStartNanos;
                        recordSliceExecution(regionId, chunksInSlice, sliceElapsedNanos);
                        context.arriveSlice(generationId);
                    }
                }, poolManager.getExecutor());
            }

            // 创建组合 future 追踪所有 slice 完成
            CompletableFuture<Void> allSlices = CompletableFuture.allOf(sliceFutures);
            pendingChunkFutures.put(regionId, allSlices);

            // 等待所有 slice 完成（带超时）
            boolean completed = context.awaitTickCompletion();

            if (!completed) {
                totalTimeouts.incrementAndGet();
                LOGGER.warn("[RegionTickPool] Virtual tick timed out for region #{} (completed={}/{})",
                        regionId, context.getCompletedSlices(), context.getExpectedSlices());

                // 标记超时并进入 DRAINING
                context.checkTimeout();

                // 尝试取消未完成的 slice（中断正在执行的任务）
                // 注意：cancel(true) 只能视为 interrupt request，不能视为任务已经停止
                for (CompletableFuture<Void> future : sliceFutures) {
                    future.cancel(true);
                }
            }

        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Virtual dispatch failed for region #{}", regionId, throwable);
            totalErrors.incrementAndGet();
        } finally {
            // 清理资源
            pendingChunkFutures.remove(regionId);
            asyncCatcherManager.release();
            context.endTick();
        }
    }

    /**
     * Platform Thread 并行模式 —— 将 slices 分配给 worker 队列。
     *
     * <p><b>稳定性保证</b>：
     * <ul>
     *   <li>超时后进入 DRAINING 状态</li>
     *   <li>单个 slice 失败不影响其他 slice</li>
     *   <li>旧 Generation 的迟到完成不会影响新 Generation</li>
     * </ul>
     *
     * <h3>P1 Adaptive Slicing</h3>
     * <p>根据 {@link AdaptiveSlicer} 动态计算 slice 大小，
     * 目标约 2-4ms estimated work/slice。
     */
    private void dispatchParallelPlatform(@NotNull final RegionTickContext context, final long[] chunkArray) {
        RegionTickWorker[] workers = poolManager.getWorkers();
        if (workers == null || workers.length == 0) {
            LOGGER.warn("[RegionTickPool] No workers available for platform dispatch in region #{} — falling back to single-slice",
                    context.regionId);
            long deadline = System.nanoTime() + TICK_INTERVAL_NANOS;
            TickGeneration gen = context.tryBeginTick(1, deadline);
            if (gen != null) {
                fallbackSingleSlice(context, chunkArray, gen.generationId());
            }
            return;
        }

        final long regionId = context.regionId;
        int total = chunkArray.length;

        // P1: 使用 Adaptive Slicer 动态计算 slice 大小
        int dynamicSliceSize = calculateDynamicSliceSize(regionId, total);
        int actualSliceSize = Math.max(1, Math.min(sliceSize, dynamicSliceSize));

        RegionTickSlice[] slices = RegionTickSlice.fromChunkArray(context, chunkArray, actualSliceSize, 0);
        int sliceCount = slices.length;

        long deadline = System.nanoTime() + TICK_INTERVAL_NANOS;
        TickGeneration gen = context.tryBeginTick(sliceCount, deadline);
        if (gen == null) {
            LOGGER.debug("[RegionTickPool] Region #{} platform tick skipped — already ticking",
                    context.regionId);
            return;
        }

        final long generationId = gen.generationId();

        // 使用正确的 generationId 重新创建 slices
        slices = RegionTickSlice.fromChunkArray(context, chunkArray, actualSliceSize, generationId);

        try {
            // 贪心负载均衡分发给 workers
            int actualWorkers = Math.min(sliceCount, workers.length);
            int[] load = new int[actualWorkers];
            for (RegionTickSlice slice : slices) {
                int minIdx = 0;
                for (int i = 1; i < actualWorkers; i++) {
                    if (load[i] < load[minIdx]) minIdx = i;
                }
                workers[minIdx].submit(slice);
                load[minIdx]++;
            }

            // 等待所有 slice 完成
            boolean completed = context.awaitTickCompletion();

            if (!completed) {
                totalTimeouts.incrementAndGet();
                LOGGER.warn("[RegionTickPool] Platform tick timed out for region #{} (completed={}/{})",
                        regionId, context.getCompletedSlices(), context.getExpectedSlices());

                // 标记超时
                context.checkTimeout();
            }

        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Platform dispatch failed for region #{}", regionId, throwable);
            totalErrors.incrementAndGet();
        } finally {
            context.endTick();
        }
    }

    /**
     * 捕获当前 region 的 RegionizedWorldData。
     */
    private RegionizedWorldData captureRegionData(@NotNull final RegionTickContext context) {
        try {
            net.minecraft.world.level.Level world = context.region.getData().world;
            if (world instanceof ServerLevel) {
                return world.getCurrentWorldData();
            }
        } catch (Throwable throwable) {
            LOGGER.warn("[RegionTickPool] Failed to capture region data for region #{}", context.regionId, throwable);
        }
        return null;
    }

    /**
     * 回退到单线程执行 —— 当无法获取 region data 或无可用 worker 时使用。
     */
    private void fallbackSingleSlice(@NotNull final RegionTickContext context, final long[] chunkArray, final long generationId) {
        try {
            RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
            if (executor != null) {
                RegionTickSlice slice = new RegionTickSlice(context, chunkArray, 0, generationId);
                executor.executeSlice(null, slice, context);
            }
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Fallback single-slice tick failed for region #{}", context.regionId, throwable);
            context.failSlice(generationId, throwable);
        } finally {
            context.arriveSlice(generationId);
            context.endTick();
        }
    }

    /**
     * 취销 region —— 清理 pending 状態与 region 统计。
     */
    public void unregister(long regionId) {
        // R4-修复: 清理 pending chunk tick future
        CompletableFuture<Void> future = pendingChunkFutures.remove(regionId);
        if (future != null) {
            future.cancel(true);
        }
        // R4-修复: 清理 Adaptive Slicer 统计（region 已销毁，无需保留其 slice 历史）
        regionSlicers.remove(regionId);
    }

    /**
     * 获取 pending chunk tick 数量。
     */
    public int getPendingChunkTickCount() {
        return pendingChunkFutures.size();
    }

    /**
     * 获取总超时次数。
     */
    public int getTotalTimeouts() {
        return totalTimeouts.get();
    }

    // ---- P1: Adaptive Slicing 支持 ----

    /**
     * 获取指定 Region 的 AdaptiveSlicer。
     */
    public AdaptiveSlicer.RegionAdaptiveSlicer getRegionSlicer(long regionId) {
        return regionSlicers.computeIfAbsent(regionId,
                id -> new AdaptiveSlicer.RegionAdaptiveSlicer(id, 0.1));
    }

    /**
     * 计算动态 slice 大小（基于历史执行时间）。
     *
     * @param regionId     Region ID
     * @param totalChunks  总 chunk 数
     * @return 推荐的每个 slice 的 chunk 数量
     */
    public int calculateDynamicSliceSize(long regionId, int totalChunks) {
        AdaptiveSlicer.RegionAdaptiveSlicer slicer = getRegionSlicer(regionId);
        return slicer.getChunksPerSlice();
    }

    /**
     * 记录一次 slice 的执行时间，用于更新预测。
     *
     * @param regionId        Region ID
     * @param chunksInSlice   本次 slice 包含的 chunk 数
     * @param executionNanos  实际执行时间（纳秒）
     */
    public void recordSliceExecution(long regionId, int chunksInSlice, long executionNanos) {
        AdaptiveSlicer.RegionAdaptiveSlicer slicer = getRegionSlicer(regionId);
        slicer.recordSliceExecution(chunksInSlice, executionNanos / 1_000_000.0);
    }
}
