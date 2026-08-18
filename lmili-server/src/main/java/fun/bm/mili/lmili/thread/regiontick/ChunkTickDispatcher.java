package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
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
 * <h3>稳定性改进</h3>
 * <ul>
 *   <li>使用 CountDownLatch 替代 Phaser，更简单可靠</li>
 *   <li>超时后强制恢复状态，防止永久卡死</li>
 *   <li>完善的错误处理和资源清理</li>
 *   <li>背压机制：前一 tick 未完成时跳过当前 tick</li>
 * </ul>
 */
public final class ChunkTickDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SLOW_TICK_THRESHOLD_MS = 50;

    private final WorkerPoolManager poolManager;
    private final int parallelismThreshold;
    private final int sliceSize;
    private final AsyncCatcherManager asyncCatcherManager;
    private final RegionDiagnostics diagnostics;

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
    }

    /**
     * 分派 region chunk tick 任务 —— 主入口。
     *
     * <p>此方法在 region tick 线程上调用，会阻塞直到所有 chunk tick 完成或超时。</p>
     */
    public void dispatch(@NotNull final RegionTickContext context, final long tickCount) {
        if (context.regionId == 0L) return;

        var chunks = context.getOwnedChunks();
        int chunkCount = chunks.size();
        if (chunkCount == 0) return;

        long startNanos = System.nanoTime();
        try {
            if (chunkCount < parallelismThreshold) {
                // 小 region 直接同步执行，避免调度开销
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

            // 诊断日志（每 1000 tick）
            long total = diagnostics.getTotalTicksDispatched();
            if (total % 1000L == 0L) {
                LOGGER.info("[RegionTickPool] Stats: total_ticks={}, errors={}, timeouts={}, max_tick_ms={}, pending={}",
                        total, diagnostics.getTotalErrors(), totalTimeouts.get(),
                        diagnostics.getMaxTickDurationMs(), pendingChunkFutures.size());
            }
        }
    }

    /**
     * 单 slice 同步 tick —— 小 region 专用。
     */
    private void dispatchSingleSlice(@NotNull final RegionTickContext context, final long[] chunkArray) {
        if (!context.tryBeginTick(1)) {
            LOGGER.debug("[RegionTickPool] Region #{} single-slice tick skipped — already ticking",
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
            LOGGER.error("[RegionTickPool] Single-slice tick failed for region #{}", context.regionId, throwable);
            context.arriveSlice(); // 确保 latch 被释放
            throw throwable;
        } finally {
            context.endTick();
        }
    }

    /**
     * Virtual Thread 并行模式 —— 将 chunks 拆分为多个 slice，
     * 每个 slice 作为一个 virtual thread 并行执行。
     *
     * <p><b>阻塞设计</b>：等待所有 slice 完成或超时。
     * 超时后强制恢复状态，避免永久卡死。</p>
     */
    private void dispatchParallelVirtual(@NotNull final RegionTickContext context, final long[] chunkArray) {
        final long regionId = context.regionId;
        int total = chunkArray.length;
        int sliceCount = Math.max(1, (total + sliceSize - 1) / sliceSize);

        // 尝试开始 tick
        if (!context.tryBeginTick(sliceCount)) {
            LOGGER.debug("[RegionTickPool] Region #{} virtual tick skipped — already ticking", regionId);
            return;
        }

        // 捕获当前 region 的 RegionizedWorldData
        final RegionizedWorldData regionData = captureRegionData(context);
        if (regionData == null) {
            // 无法获取 region data，回退到单线程执行
            LOGGER.warn("[RegionTickPool] No region data for virtual dispatch in region #{} — falling back to single-slice",
                    regionId);
            fallbackSingleSlice(context, chunkArray);
            return;
        }

        // 创建 CompletableFuture 数组追踪所有 slice
        CompletableFuture<Void>[] sliceFutures = new CompletableFuture[sliceCount];
        asyncCatcherManager.acquire();

        try {
            // 提交所有 slice 任务
            for (int i = 0; i < sliceCount; i++) {
                int from = i * sliceSize;
                int to = Math.min(from + sliceSize, total);
                long[] sliceArray = java.util.Arrays.copyOfRange(chunkArray, from, to);
                RegionTickSlice slice = new RegionTickSlice(context, sliceArray, i);
                final int sliceIndex = i;

                sliceFutures[i] = CompletableFuture.runAsync(() -> {
                    // 在虚拟线程中设置 region data 回退
                    RegionDataThreadLocal.setCurrent(regionData);
                    try {
                        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
                        if (executor != null) {
                            executor.executeSlice(null, slice, context);
                        }
                    } catch (Throwable throwable) {
                        LOGGER.error("[RegionTickPool] Virtual slice #{} failed for region #{}",
                                sliceIndex, regionId, throwable);
                    } finally {
                        RegionDataThreadLocal.clear();
                        // 通知 slice 完成
                        context.arriveSlice();
                    }
                }, poolManager.getExecutor());
            }

            // 创建组合 future 追踪所有 slice 完成
            CompletableFuture<Void> allSlices = CompletableFuture.allOf(sliceFutures);
            pendingChunkFutures.put(regionId, allSlices);

            // 等待所有 slice 完成（阻塞）
            boolean completed = context.awaitTickCompletion();

            if (!completed) {
                // 超时处理
                totalTimeouts.incrementAndGet();
                LOGGER.warn("[RegionTickPool] Virtual tick timed out for region #{} after {}ms (completed={}/{})",
                        regionId, RegionTickPoolConfig.virtualThreadTimeoutMs,
                        context.getCompletedSlices(), context.getExpectedSlices());

                // 取消未完成的 slice
                for (CompletableFuture<Void> future : sliceFutures) {
                    future.cancel(true);
                }
            }

        } catch (Exception e) {
            LOGGER.error("[RegionTickPool] Virtual dispatch failed for region #{}", regionId, e);
            totalErrors.incrementAndGet();
        } finally {
            // 清理
            pendingChunkFutures.remove(regionId);
            asyncCatcherManager.release();
            context.endTick();
        }
    }

    /**
     * Platform Thread 并行模式 —— 将 slices 分配给 worker 队列。
     *
     * <p>阻塞式设计：等待所有 worker 完成。</p>
     */
    private void dispatchParallelPlatform(@NotNull final RegionTickContext context, final long[] chunkArray) {
        RegionTickWorker[] workers = poolManager.getWorkers();
        if (workers == null || workers.length == 0) return;

        RegionTickSlice[] slices = RegionTickSlice.fromChunkArray(context, chunkArray, sliceSize);
        int sliceCount = slices.length;

        // 尝试开始 tick
        if (!context.tryBeginTick(sliceCount)) {
            LOGGER.debug("[RegionTickPool] Region #{} platform tick skipped — already ticking",
                    context.regionId);
            return;
        }

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
                        context.regionId, context.getCompletedSlices(), context.getExpectedSlices());
            }

        } catch (Exception e) {
            LOGGER.error("[RegionTickPool] Platform dispatch failed for region #{}", context.regionId, e);
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
            if (world instanceof ServerLevel serverLevel) {
                return world.getCurrentWorldData();
            }
        } catch (Exception e) {
            LOGGER.warn("[RegionTickPool] Failed to capture region data for region #{}", context.regionId, e);
        }
        return null;
    }

    /**
     * 回退到单线程执行。
     */
    private void fallbackSingleSlice(@NotNull final RegionTickContext context, final long[] chunkArray) {
        try {
            RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
            if (executor != null) {
                executor.executeSlice(null, new RegionTickSlice(context, chunkArray, 0), context);
            }
            context.arriveSlice();
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Fallback single-slice tick failed for region #{}", context.regionId, throwable);
            context.arriveSlice();
            throw throwable;
        } finally {
            context.endTick();
        }
    }

    /**
     * 注销 region —— 清理 pending 状态。
     */
    public void unregister(long regionId) {
        CompletableFuture<Void> future = pendingChunkFutures.remove(regionId);
        if (future != null) {
            future.cancel(true);
        }
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
}
