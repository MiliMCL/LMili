package fun.bm.mili.lmili.thread.regiontick;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 跨 region chunk 预加载器 —— 在玩家还在 A region 时，预先提交 B region 的 chunk 加载任务。
 *
 * <p><b>动机</b>：{@link PlayerChunkPreloader} 处理的是<b>同一 region 内</b>的 chunk 预加载。
 * 但 region 是 8×8 chunks，玩家在 region 边缘时跨 region 移动会触发：
 * 玩家到达 B region 第一 tick → B region 才开始加载 chunks → 加载 spike（chunk load 阻塞 tick）。
 *
 * <p><b>本类的方案</b>：在 A region tick 中检测玩家速度/方向，预测下一 tick 将进入的 chunk
 * （在 A 内或 B 内）。如果目标 chunk 跨 region，通过 Bukkit 主线程调度器将 chunk load 请求
 * <b>路由到目标 region 的线程</b>。这样 chunk load 与玩家移动<b>完全并行</b> ——
 * 玩家到达 B region 第一 tick 时 chunks 已就绪。
 *
 * <p><b>线程</b>：从 region tick 线程调用（在 EntityTickDispatcher.dispatch 内调用）。
 */
public final class CrossRegionChunkPreloader {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 上次跨 region 预加载的 chunk key（去重） */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> lastCrossPreloadChunkKey = new java.util.concurrent.ConcurrentHashMap<>();
    /** 上次预加载的半径 */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Integer> lastCrossPreloadRadius = new java.util.concurrent.ConcurrentHashMap<>();

    /** 节点 ID 自增生成器（DAG-style ID，避免与 system DAG 节点冲突） */
    private static final AtomicInteger NEXT_NODE_ID = new AtomicInteger(0x10000000);

    private CrossRegionChunkPreloader() {}

    /**
     * 对玩家预测即将进入的 region 提交 chunk 异步预加载任务。
     *
     * <p>预测策略：用玩家当前速度向量（{@link ServerPlayer#getDeltaMovement()}）单位化后投影到
     * chunk 边界。如果预测 chunk 在另一 region，则跨 region 提交 task。
     *
     * @param level               当前 region 的 ServerLevel
     * @param player              玩家
     * @param currentContext      当前 region 的 tick 上下文
     * @param lookaheadChunks     预测未来 N 个 chunks（如 2 = 玩家 2 个 chunk 后会到达的位置）
     * @param radiusChunks        预加载半径（以预测 chunk 为中心的周围 chunks 数）
     */
    public static void preload(final ServerLevel level, final ServerPlayer player,
                               final RegionTickContext currentContext,
                               final int lookaheadChunks, final int radiusChunks) {
        // 当前 context 必须存在（region tick 进入时由 RegionTickDispatcher.getOrCreateContext 创建）
        if (currentContext == null || currentContext.region == null) {
            return;
        }

        final Vec3 motion = player.getDeltaMovement();
        final double motionLenSq = motion.x * motion.x + motion.z * motion.z;
        // 玩家移动速度过低（< 0.01 blocks/tick）→ 不预测（避免无意义预加载）
        if (motionLenSq < 0.0001) {
            return;
        }
        // 单位化水平方向（xz 平面），忽略 y
        final double motionLen = Math.sqrt(motionLenSq);
        final double dirX = motion.x / motionLen;
        final double dirZ = motion.z / motionLen;
        // 预测未来 lookaheadChunks 个 chunks 的位置（每个 chunk 16 方块）
        final BlockPos playerPos = player.blockPosition();
        final int currentChunkX = playerPos.getX() >> 4;
        final int currentChunkZ = playerPos.getZ() >> 4;
        final int lookaheadChunksAbs = Math.max(1, lookaheadChunks);
        // 用 player.getId() 作为 cache key（每个玩家独立）
        final long playerKey = (long) player.getId();
        final int predictedChunkX = currentChunkX + (int) Math.round(dirX * lookaheadChunksAbs);
        final int predictedChunkZ = currentChunkZ + (int) Math.round(dirZ * lookaheadChunksAbs);
        final long predictedChunkKey = ((long) predictedChunkX << 32) | (predictedChunkZ & 0xFFFFFFFFL);

        // 去重：与上次预加载位置 + 半径一致就跳过
        final Integer lastRadius = lastCrossPreloadRadius.get(playerKey);
        final Long lastChunkKey = lastCrossPreloadChunkKey.get(playerKey);
        final int effectiveRadius = Math.max(0, radiusChunks);
        if (lastRadius != null && lastRadius == effectiveRadius && lastChunkKey != null
            && lastChunkKey == predictedChunkKey) {
            return;
        }

        // 检查预测 chunk 是否在另一 region —— 如果当前线程不负责该 chunk，就是跨 region
        final boolean sameRegion = ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, predictedChunkX, predictedChunkZ);
        if (sameRegion) {
            // 同一 region —— 让 PlayerChunkPreloader 处理（不要重复加载）
            return;
        }

        // 计算目标 regionId —— 从 regioniser 获取真实 region.id
        final io.papermc.paper.threadedregions.ThreadedRegionizer.ThreadedRegion<
            io.papermc.paper.threadedregions.TickRegions.TickRegionData,
            io.papermc.paper.threadedregions.TickRegions.TickRegionSectionData> targetRegionObj =
            level.regioniser.getRegionAtUnsynchronised(predictedChunkX, predictedChunkZ);
        if (targetRegionObj == null) {
            // 目标 region 不存在（边界外？chunk 未加载？）
            LOGGER.trace("[CrossRegionChunkPreload] target region not found for chunk [{}, {}]",
                predictedChunkX, predictedChunkZ);
            return;
        }
        final long targetRegionId = targetRegionObj.getData().id;
        if (targetRegionId == 0L) {
            LOGGER.warn("[CrossRegionChunkPreload] unexpected global regionId for chunk [{}, {}]",
                predictedChunkX, predictedChunkZ);
            return;
        }

        // 准备任务 body：在目标 region 线程上调用 moonrise$loadChunksAsync
        final BlockPos predictedCenter = new BlockPos(
            (predictedChunkX << 4) + 8,
            playerPos.getY(),
            (predictedChunkZ << 4) + 8);
        final int radiusBlocks = effectiveRadius * 16;
        final Consumer<List<ChunkAccess>> onLoad = chunks -> {
            if (chunks != null && !chunks.isEmpty() && LOGGER.isDebugEnabled()) {
                LOGGER.debug("[CrossRegionChunkPreload] async loaded {} chunks for player {} at [{}, {}]",
                    chunks.size(), player.getName().getString(), predictedChunkX, predictedChunkZ);
            }
        };
        final Runnable body = () -> {
            try {
                ((ChunkSystemServerLevel) level).moonrise$loadChunksAsync(predictedCenter, radiusBlocks, Priority.NORMAL, onLoad);
            } catch (Throwable t) {
                LOGGER.warn("[CrossRegionChunkPreload] moonrise$loadChunksAsync failed for player {}: {}",
                    player.getName().getString(), t.getMessage());
            }
        };

        // 通过 Bukkit 主线程调度器路由到目标 region
        try {
            final int nodeId = NEXT_NODE_ID.incrementAndGet();
            if (Bukkit.isPrimaryThread()) {
                // 已经在主线程，直接执行
                body.run();
                lastCrossPreloadChunkKey.put(playerKey, predictedChunkKey);
                lastCrossPreloadRadius.put(playerKey, effectiveRadius);
            } else {
                // 修复：安全获取插件引用，避免服务器关闭时 getPlugins()[0] 抛出 ArrayIndexOutOfBoundsException
                org.bukkit.plugin.Plugin[] plugins = org.bukkit.Bukkit.getPluginManager().getPlugins();
                if (plugins.length == 0 || !plugins[0].isEnabled()) {
                    LOGGER.debug("[CrossRegionChunkPreload] No available plugin for dispatch, skipping");
                    return;
                }
                org.bukkit.plugin.Plugin plugin = plugins[0];
                
                // 修复：检查服务器是否正在关闭
                if (Bukkit.getServer().isStopping()) {
                    LOGGER.debug("[CrossRegionChunkPreload] Server is stopping, skipping cross-region preload");
                    return;
                }
                
                // 异步调度到主线程
                Bukkit.getScheduler().runTask(
                    plugin,
                    () -> {
                        // 再次检查服务器状态和插件状态
                        if (Bukkit.getServer().isStopping() || !plugin.isEnabled()) {
                            return;
                        }
                        body.run();
                        lastCrossPreloadChunkKey.put(playerKey, predictedChunkKey);
                        lastCrossPreloadRadius.put(playerKey, effectiveRadius);
                    }
                );
            }
        } catch (Throwable t) {
            LOGGER.warn("[CrossRegionChunkPreload] dispatch failed for player {}: {}",
                player.getName().getString(), t.getMessage());
        }
    }

    /**
     * 清理玩家缓存（玩家下线时调用，避免内存泄漏）。
     */
    public static void clearCacheForPlayer(final int playerEntityId) {
        final long key = (long) playerEntityId;
        lastCrossPreloadChunkKey.remove(key);
        lastCrossPreloadRadius.remove(key);
    }

    /**
     * 清理所有缓存（服务器关闭时调用）。
     */
    public static void clearAllCache() {
        lastCrossPreloadChunkKey.clear();
        lastCrossPreloadRadius.clear();
    }

    /**
     * 获取缓存大小（用于监控）。
     */
    public static int getCacheSize() {
        return lastCrossPreloadChunkKey.size();
    }
}
