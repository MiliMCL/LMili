package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.lmili.thread.regiontick.dag.Scope;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.concurrent.*;

/**
 * Chunk Tick 分派器 —— 管理 3 种 chunk tick 执行策略。
 *
 * <p>根据区域 chunk 数量决定执行策略：
 * <ul>
 *   <li>chunk 数低于 parallelismThreshold：单线程同步执行（无调度开销）</li>
 *   <li>virtual 模式：多 virtual thread 并行执行 slices</li>
 *   <li>platform 模式：分发给 worker 队列执行</li>
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

    // 并行 chunk tick 追踪 —— 防止 tick 重叠（前一 tick 未完成时跳过下一 tick）
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingChunkFutures = new ConcurrentHashMap<>();

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
     * 分派 region tick 任务 —— 主入口。
     */
    public void dispatch(@NotNull final RegionTickContext context, final long tickCount) {
        if (context.regionId == 0L) return;

        // 防止 tick 重叠：Tick N 未完成时，同 Region 不能进入 Tick N+1
        if (context.isTicking()) {
            LOGGER.debug("[RegionTickPool] Region #{} tick skipped — previous tick still in-flight (state={})",
                    context.regionId, context.getTickState());
            return;
        }

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
            LOGGER.error("[RegionTickPool] dispatchTick failed for region #{}", context.regionId, throwable);
            // 确保 tick 状态恢复
            context.finishTick();
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
                LOGGER.info("[RegionTickPool] Stats: total_ticks={}, errors={}, max_tick_ms={}, pending={}",
                        total, diagnostics.getTotalErrors(), diagnostics.getMaxTickDurationMs(),
                        pendingChunkFutures.size());
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
        RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
        if (executor != null) {
            try {
                executor.executeSlice(null, new RegionTickSlice(context, chunkArray, 0), context);
            } catch (Throwable throwable) {
                LOGGER.error("[RegionTickPool] Single-slice tick failed for region #{}", context.regionId, throwable);
            }
        }
        context.arriveSlice();
        context.endTick();
    }

    /**
     * Virtual Thread 并行模式 —— 将 chunks 拆分为多个 slice，
     * 每个 slice 作为一个 virtual thread 并行执行。
     *
     * <p><b>非阻塞设计</b>：chunk tick 提交后立即返回，不阻塞 region tick thread。
     * 使用 CompletableFuture 追踪完成状态，下一 tick 如果检测到前一 tick 仍在运行，
     * 则跳过本次 chunk tick（避免堆积）。
     */
    private void dispatchParallelVirtual(@NotNull final RegionTickContext context, final long[] chunkArray) {
        final long regionId = context.regionId;

        int total = chunkArray.length;
        int sliceCount = Math.max(1, (total + sliceSize - 1) / sliceSize);

        // 防止 tick 重叠：使用 CAS 状态机，Tick N 未完成时不能进入 Tick N+1
        if (!context.tryBeginTick(sliceCount)) {
            LOGGER.debug("[RegionTickPool] Region #{} virtual tick skipped — already ticking (state={})",
                    regionId, context.getTickState());
            return;
        }

        // 捕获当前 region 的 RegionizedWorldData，传递给每个虚拟线程
        io.papermc.paper.threadedregions.RegionizedWorldData currentRegionData = null;
        try {
            net.minecraft.world.level.Level world = context.region.getData().world;
            if (world instanceof net.minecraft.server.level.ServerLevel) {
                currentRegionData = world.getCurrentWorldData();
            }
        } catch (Exception e) {
            LOGGER.warn("[RegionTickPool] Failed to capture region data for virtual dispatch", e);
        }
        final io.papermc.paper.threadedregions.RegionizedWorldData regionData = currentRegionData;

        if (regionData == null) {
            // 无法获取 region data 时回退到单线程执行
            LOGGER.warn("[RegionTickPool] No region data for virtual dispatch in region #{} — falling back to single-slice",
                    regionId);
            RegionTickExecutor executor = RegionTickExecutor.getRegisteredExecutor();
            if (executor != null) {
                try {
                    executor.executeSlice(null, new RegionTickSlice(context, chunkArray, 0), context);
                } catch (Throwable throwable) {
                    LOGGER.error("[RegionTickPool] Fallback single-slice tick failed for region #{}", regionId, throwable);
                }
            }
            context.endTick();
            return;
        }

        // 创建 CompletableFuture 追踪本次 tick 的所有 slice
        CompletableFuture<Void>[] sliceFutures = new CompletableFuture[sliceCount];

        // Async Catcher 引用计数
        asyncCatcherManager.acquire();

        try {
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
                    }
                }, poolManager.getExecutor());
            }

            // 创建组合 future 追踪所有 slice 完成，但不阻塞当前线程
            CompletableFuture<Void> allSlices = CompletableFuture.allOf(sliceFutures);
            // 保存引用以便下一 tick 检查
            pendingChunkFutures.put(regionId, allSlices);

            // 设置超时和完成处理
            allSlices.orTimeout(RegionTickPoolConfig.virtualThreadTimeoutMs, TimeUnit.MILLISECONDS)
                    .handle((result, throwable) -> {
                        // 清理 pending 状态
                        pendingChunkFutures.remove(regionId);
                        if (throwable instanceof TimeoutException) {
                            LOGGER.warn("[RegionTickPool] Virtual tick timed out for region #{} after {}ms",
                                    regionId, RegionTickPoolConfig.virtualThreadTimeoutMs);
                        } else if (throwable != null) {
                            diagnostics.recordError();
                            LOGGER.error("[RegionTickPool] Virtual tick completed with error for region #{}", regionId, throwable);
                        }
                        // 恢复 async catcher
                        asyncCatcherManager.release();
                        // 完成 tick，回到 IDLE 状态
                        context.endTick();
                        return null;
                    });
        } catch (Exception e) {
            // 异常时恢复 async catcher
            asyncCatcherManager.release();
            // 异常时也要恢复 tick 状态
            context.endTick();
            throw e;
        }
    }

    /**
     * Platform Thread 并行模式 —— 将 slices 分配给 worker 队列。
     *
     * <p>阻塞式设计：等待所有 worker 完成。
     * 此模式适合保守部署（worker 数量有限，总执行时间可控）。
     */
    private void dispatchParallelPlatform(@NotNull final RegionTickContext context, final long[] chunkArray) {
        RegionTickWorker[] workers = poolManager.getWorkers();
        if (workers == null || workers.length == 0) return;

        RegionTickSlice[] slices = RegionTickSlice.fromChunkArray(context, chunkArray, sliceSize);
        // 防止 tick 重叠：使用 CAS 状态机
        if (!context.tryBeginTick(slices.length + 1)) {
            LOGGER.debug("[RegionTickPool] Region #{} platform tick skipped — already ticking",
                    context.regionId);
            return;
        }

        // 贪心负载均衡分发给 workers
        int actualWorkers = Math.min(slices.length, workers.length);
        int[] load = new int[actualWorkers];
        for (RegionTickSlice slice : slices) {
            int minIdx = 0;
            for (int i = 1; i < actualWorkers; i++) {
                if (load[i] < load[minIdx]) minIdx = i;
            }
            workers[minIdx].submit(slice);
            load[minIdx]++;
        }

        // 等待所有 worker 完成
        context.awaitTickCompletion();
        context.endTick();
    }

    /**
     * 注销 region —— 清理 pending 状态。
     */
    public void unregister(long regionId) {
        pendingChunkFutures.remove(regionId);
    }

    /**
     * 获取 pending chunk tick 数量。
     */
    public int getPendingChunkTickCount() {
        return pendingChunkFutures.size();
    }
}
