package fun.bm.mili.lmili.thread.regiontick.executor;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.RegionTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.RegionTickSlice;
import fun.bm.mili.lmili.thread.regiontick.RegionTickWorker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * LMili Tick 执行器 —— 使用 LMili API 实现 chunk tick 执行。
 *
 * <p><b>设计目标</b>：替代 Folia 的 tick 执行器，使用 LMili 统一调度 API
 * 实现高效、安全的 chunk tick 执行。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>Slice -based tick 执行：将 chunk 分组执行，便于时间预算控制</li>
 *   <li>异常隔离：单个 chunk tick 失败不影响同 slice 其他 chunk</li>
 *   <li>性能监控：记录每个 slice 和 chunk 的执行时间</li>
 *   <li>Tick speed 控制：支持动态调整 tick 速率</li>
 * </ul>
 *
 * <h3>性能目标</h3>
 * <ul>
 *   <li>单 chunk tick P99 < 5ms</li>
 *   <li>单 slice tick（16 chunks）P99 < 45ms</li>
 *   <li>异常隔离：单个 chunk OOM 不影响同 slice</li>
 * </ul>
 *
 * @since 2.0.0
 */
public final class LMiliTickExecutor implements RegionTickExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 慢 chunk  tick 阈值（毫秒） */
    private static final long SLOW_CHUNK_TICK_MS = 10;

    /** 严重慢 chunk tick 阈值（毫秒） */
    private static final long SEVERE_SLOW_CHUNK_TICK_MS = 50;

    /** 单 slice 时间预算（毫秒） */
    private static final long SLICE_TIME_BUDGET_MS = 45;

    /** 单 slice 严重超时阈值（毫秒） */
    private static final long SLICE_SEVERE_TIMEOUT_MS = 100;

    /** LMili 系统所有者 ID（用于获取系统调度器） */
    private static final fun.bm.mili.lmili.api.identity.PluginId SYSTEM_OWNER =
            fun.bm.mili.lmili.api.LMili.SYSTEM_OWNER_ID;

    private volatile int tickSpeed;
    private volatile long slowChunkTickMs = SLOW_CHUNK_TICK_MS;
    private volatile long sliceTimeBudgetMs = SLICE_TIME_BUDGET_MS;

    /** 统计指标 */
    private final java.util.concurrent.atomic.AtomicLong totalSlicesExecuted =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalChunksExecuted =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalSlowChunks =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong totalSliceTimeouts =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 创建 LMili Tick 执行器。
     */
    public LMiliTickExecutor() {
        this.tickSpeed = 3;
    }

    /**
     * 创建 LMili Tick 执行器（指定 tick speed）。
     *
     * @param tickSpeed tick 速率
     */
    public LMiliTickExecutor(final int tickSpeed) {
        this.tickSpeed = Math.max(0, tickSpeed);
    }

    @Override
    public void executeSlice(@NotNull RegionTickWorker worker,
                             @NotNull RegionTickSlice slice,
                             @NotNull RegionTickContext context) {
        int sliceSize = slice.size();
        if (sliceSize == 0) return;

        ServerLevel level = getServerLevel(context);
        if (level == null) {
            LOGGER.warn("[LMiliTickExecutor] Region #{} has no ServerLevel", context.regionId);
            return;
        }

        long sliceStart = System.nanoTime();
        int chunksExecuted = 0;

        for (int i = 0; i < sliceSize; i++) {
            long chunkPosLong = slice.getChunkPos(i);
            int chunkX = net.minecraft.world.level.ChunkPos.getX(chunkPosLong);
            int chunkZ = net.minecraft.world.level.ChunkPos.getZ(chunkPosLong);
            LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
            if (chunk == null) continue;

            long chunkStart = System.nanoTime();
            try {
                // 执行 chunk tick
                tickChunk(worker, chunk, level, context);
                chunksExecuted++;
            } catch (OutOfMemoryError oom) {
                // OOM 是严重问题，需要立即记录
                LOGGER.error("[LMiliTickExecutor] OOM ticking chunk [{}, {}] in region #{}",
                        chunk.getPos().x(), chunk.getPos().z(), context.regionId, oom);
                // 尝试释放一些内存
                System.gc();
            } catch (Throwable t) {
                LOGGER.error("[LMiliTickExecutor] Error ticking chunk [{}, {}] in region #{}",
                        chunk.getPos().x(), chunk.getPos().z(), context.regionId, t);
            } finally {
                long chunkElapsedMs = (System.nanoTime() - chunkStart) / 1_000_000;
                if (chunkElapsedMs > slowChunkTickMs) {
                    totalSlowChunks.incrementAndGet();
                    if (chunkElapsedMs > SEVERE_SLOW_CHUNK_TICK_MS) {
                        LOGGER.warn("[LMiliTickExecutor] SEVERE slow chunk tick [{}, {}] in region #{} took {}ms",
                                chunk.getPos().x(), chunk.getPos().z(), context.regionId, chunkElapsedMs);
                    } else if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("[LMiliTickExecutor] Slow chunk tick [{}, {}] in region #{} took {}ms",
                                chunk.getPos().x(), chunk.getPos().z(), context.regionId, chunkElapsedMs);
                    }
                }
            }
        }

        // slice 级统计
        long sliceElapsedMs = (System.nanoTime() - sliceStart) / 1_000_000;
        totalSlicesExecuted.incrementAndGet();
        totalChunksExecuted.addAndGet(chunksExecuted);

        if (sliceElapsedMs > sliceTimeBudgetMs) {
            totalSliceTimeouts.incrementAndGet();
            if (sliceElapsedMs > SLICE_SEVERE_TIMEOUT_MS) {
                LOGGER.warn("[LMiliTickExecutor] SEVERE slice timeout region #{} took {}ms (budget {}ms), chunks: {}/{}",
                        context.regionId, sliceElapsedMs, sliceTimeBudgetMs, chunksExecuted, sliceSize);
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[LMiliTickExecutor] Slice timeout region #{} took {}ms (budget {}ms), chunks: {}/{}",
                        context.regionId, sliceElapsedMs, sliceTimeBudgetMs, chunksExecuted, sliceSize);
            }
        } else if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("[LMiliTickExecutor] Region #{} slice took {}ms, chunks: {}/{}",
                    context.regionId, sliceElapsedMs, chunksExecuted, sliceSize);
        }
    }

    /**
     * 执行单个 chunk tick。
     *
     * <p>使用 LMili 的同步任务执行器确保安全和性能。
     */
    private void tickChunk(@NotNull RegionTickWorker worker,
                           @NotNull LevelChunk chunk,
                           @NotNull ServerLevel level,
                           @NotNull RegionTickContext context) {
        // 检查 chunk 是否仍然有效
        if (!chunk.getLevel().isLoaded(chunk.getPos().getWorldPosition())) {
            return;
        }

        // 使用 LMili 的 SyncTaskExecutor 确保安全的同步执行
        fun.bm.mili.api.SyncTaskResult<Void> result = fun.bm.mili.api.SyncTaskExecutor.getInstance().execute(
                SYSTEM_OWNER,
                () -> {
                    // 执行实际的 chunk tick 逻辑
                    executeChunkTick(worker, chunk, level, context);
                    return null;
                },
                fun.bm.mili.api.SyncTaskConstraints.DEFAULT
        );

        // 检查结果
        if (result.isTimeout()) {
            LOGGER.warn("[LMiliTickExecutor] Chunk tick timed out [{}, {}] in region #{}",
                    chunk.getPos().x(), chunk.getPos().z(), context.regionId);
        } else if (result.isFailure() && !result.isTimeout()) {
            LOGGER.debug("[LMiliTickExecutor] Chunk tick failed [{}, {}]: {}",
                    chunk.getPos().x(), chunk.getPos().z(), result.failureReason());
        }
    }

    /**
     * 执行实际的 chunk tick 逻辑。
     */
    private void executeChunkTick(@NotNull RegionTickWorker worker,
                                  @NotNull LevelChunk chunk,
                                  @NotNull ServerLevel level,
                                  @NotNull RegionTickContext context) {
        // 根据 tickSpeed 调整 tick 次数
        if (tickSpeed > 0) {
            // 调用 Paper/兼容的 chunk tick 方法
            // 注意：这里使用 Paper API 而不是 Folia API
            for (int i = 0; i < tickSpeed; i++) {
                if (chunk.getLevel().isLoaded(chunk.getPos().getWorldPosition())) {
                    // 执行 game normal tick（随机 tick、block tick 等）
                    level.tickChunk(chunk, 1);
                }
            }
        }
    }

    /**
     * 从 context 获取 ServerLevel。
     */
    private ServerLevel getServerLevel(@NotNull RegionTickContext context) {
        if (context.region != null && context.region.getData() != null) {
            return context.region.getData().world;
        }
        return null;
    }

    // ---- 配置方法 ----

    public void setTickSpeed(final int speed) { this.tickSpeed = Math.max(0, speed); }

    public int getTickSpeed() { return tickSpeed; }

    public void setSlowChunkTickMs(long ms) { this.slowChunkTickMs = Math.max(1, ms); }

    public void setSliceTimeBudgetMs(long ms) { this.sliceTimeBudgetMs = Math.max(10, ms); }

    // ---- 统计方法 ----

    public long getTotalSlicesExecuted() { return totalSlicesExecuted.get(); }

    public long getTotalChunksExecuted() { return totalChunksExecuted.get(); }

    public long getTotalSlowChunks() { return totalSlowChunks.get(); }

    public long getTotalSliceTimeouts() { return totalSliceTimeouts.get(); }

    /**
     * 获取统计信息。
     *
     * @return 统计信息映射
     */
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalSlicesExecuted", totalSlicesExecuted.get());
        stats.put("totalChunksExecuted", totalChunksExecuted.get());
        stats.put("totalSlowChunks", totalSlowChunks.get());
        stats.put("totalSliceTimeouts", totalSliceTimeouts.get());
        stats.put("tickSpeed", tickSpeed);
        stats.put("slowChunkTickMs", slowChunkTickMs);
        stats.put("sliceTimeBudgetMs", sliceTimeBudgetMs);
        return stats;
    }
}
