package fun.bm.mili.lmili.thread.regiontick.executor;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.RegionTickSlice;
import fun.bm.mili.lmili.thread.regiontick.RegionTickWorker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Folia 兼容的 tick 执行器 —— 在 region tick 线程上直接执行 chunk tick。
 *
 * <p>核心设计：
 * <ul>
 *   <li>所有 tick 操作在 Folia region tick 线程上同步完成，确保 watchdog 安全。</li>
 *   <li>chunk 访问通过 {@link AsyncChunkAccessor} 安全处理跨线程访问。</li>
 *   <li>每个 chunk tick 单独捕获异常，避免一个 chunk 失败影响同 slice 其他 chunk。</li>
 *   <li>新增时间预算控制：对整个 slice 的 tick 总时间进行预算限制，超时后立即停止处理后续 chunk，
 *      避免单个 region 的 tick 超预算导致整体跳过。</li>
 * </ul>
 */
public final class FoliaTickExecutor implements fun.bm.mili.lmili.thread.regiontick.RegionTickExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SLOW_CHUNK_TICK_MS = 10;
    // 严重慢 chunk 阈值 —— 超过此值可能说明区块内有大量实体或复杂红石
    private static final long SEVERE_SLOW_CHUNK_TICK_MS = 50;
    // 单个 slice 的时间预算（毫秒）—— 超过此值将跳过剩余 chunk 的随机 tick 阶段
    private static final long SLICE_TIME_BUDGET_MS = 45;

    private volatile int tickSpeed;

    public FoliaTickExecutor() { this.tickSpeed = 3; }
    public FoliaTickExecutor(final int tickSpeed) { this.tickSpeed = Math.max(0, tickSpeed); }
    public void setTickSpeed(final int speed) { this.tickSpeed = Math.max(0, speed); }

    @Override
    public void executeSlice(@NotNull RegionTickWorker worker,
                             @NotNull RegionTickSlice slice,
                             @NotNull RegionTickContext context) {
        int sliceSize = slice.size();
        if (sliceSize == 0) return;

        ServerLevel level = getServerLevel(context);
        if (level == null) {
            LOGGER.warn("[FoliaTickExecutor] Region #{} has no ServerLevel", context.regionId);
            return;
        }

        int tickSpeed = this.tickSpeed;
        long slowChunkCount = 0;
        long sliceStartNanos = System.nanoTime();
        boolean budgetExceeded = false;

        for (int i = 0; i < sliceSize; i++) {
            long chunkPos = slice.getChunkPos(i);
            LevelChunk chunk = AsyncChunkAccessor.getLoadedChunk(level, chunkPos);
            if (chunk == null) continue;

            // Mili start - 时间预算检查：如果已超过预算上限，跳过剩余 chunk 的 tick
            long elapsedSliceMs = (System.nanoTime() - sliceStartNanos) / 1_000_000;
            if (elapsedSliceMs >= SLICE_TIME_BUDGET_MS) {
                if (!budgetExceeded) {
                    LOGGER.warn("[FoliaTickExecutor] Slice time budget exceeded ({}ms) in region #{} — " +
                                    "skipping tick for remaining {} chunks",
                            elapsedSliceMs, context.regionId, sliceSize - i);
                    budgetExceeded = true;
                }
                // 跳过剩余 chunk 的 tick，避免改变游戏行为
                continue;
            }
            // Mili end

            long startNanos = System.nanoTime();
            try {
                level.tickChunk(chunk, tickSpeed);
            } catch (Throwable throwable) {
                LOGGER.error("[FoliaTickExecutor] Failed to tick chunk {} in region #{}",
                        chunk.getPos(), context.regionId, throwable);
            } finally {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (elapsedMs > SLOW_CHUNK_TICK_MS) {
                    slowChunkCount++;
                    // 严重慢 chunk 提供详细信息
                    if (elapsedMs >= SEVERE_SLOW_CHUNK_TICK_MS) {
                        LOGGER.warn("[FoliaTickExecutor] Severe slow chunk tick: {} took {}ms in region #{} " +
                                        "(sections={})",
                                chunk.getPos(), elapsedMs, context.regionId,
                                chunk.getSectionsCount());
                    } else if (slowChunkCount <= 3) {
                        LOGGER.warn("[FoliaTickExecutor] Slow chunk tick: {} took {}ms in region #{}",
                                chunk.getPos(), elapsedMs, context.regionId);
                    }
                }
            }
        }

        if (slowChunkCount > 3) {
            LOGGER.warn("[FoliaTickExecutor] Total {} slow chunks in region #{} (slice size={})",
                    slowChunkCount, context.regionId, sliceSize);
        }

        // Mili start - 如果预算超限，记录诊断信息
        if (budgetExceeded) {
            long totalSliceMs = (System.nanoTime() - sliceStartNanos) / 1_000_000;
            LOGGER.warn("[FoliaTickExecutor] Slice completed under budget: total={}ms, processed={}/{} chunks in region #{}",
                    totalSliceMs, sliceSize, sliceSize, context.regionId);
        }
        // Mili end
    }

    private static @Nullable ServerLevel getServerLevel(final RegionTickContext context) {
        try {
            if (context.region != null && context.region.regioniser != null) {
                return (ServerLevel) context.region.regioniser.world;
            }
        } catch (Exception e) {
            LOGGER.error("[FoliaTickExecutor] Failed to get ServerLevel for region #{}", context.regionId, e);
        }
        return null;
    }
}