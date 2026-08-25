package fun.bm.mili.lmili.thread.regiontick;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.optimizations.EntityTickPerformanceConfig;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 玩家周围 chunk 异步预加载器 —— 用 Moonrise 的 {@code moonrise$loadChunksAsync} 让
 * chunk load 与玩家移动并行，减少"玩家到达未加载 chunk 时的加载 spike"。
 *
 * <p><b>工作原理</b>：每个 region tick 开头调用 {@link #preload}，遍历本地玩家，
 * 对每个玩家提交其当前 chunk 周围 {@code radiusChunks} 个 chunk 的异步加载请求（Priority = NORMAL）。
 * Folia 默认也有"load radius"（提前 1-2 chunk 加载），本类可<b>扩大半径</b>或<b>降低优先级</b>
 * （避免抢占主线程的紧急任务）。
 *
 * <p><b>跨 region 预加载</b>：调用 {@link #preload} 时若 {@link EntityTickPerformanceConfig#crossRegionChunkPreloadEnabled}
 * 为 true，会同时调用 {@link CrossRegionChunkPreloader} 预测并预加载玩家即将进入的 B region 的 chunks
 * （通过 Folia 的 {@code RegionizedTaskQueue} 跨 region 路由）。这样 chunk load 与玩家跨 region
 * 移动完全并行 —— 玩家到达 B region 第一 tick 时 chunks 已就绪。
 *
 * <p><b>为什么"提前"有意义</b>：Folia 的 {@code RegionizedPlayerChunkLoader} 监听玩家 tick，
 * 在玩家进入新 chunk 时同步触发 load。如果在玩家移动**之前**就提交 load 请求（用 onLoad callback
 * 监听），chunk 加载与玩家移动并行，玩家到达时 chunk 已 ready —— tick 路径无加载阻塞。
 *
 * <p><b>线程</b>：必须在 region tick 线程上调用（{@code ServerLevel} 与 {@code RegionizedWorldData}
 * 都是线程本地的）。
 */
public final class PlayerChunkPreloader {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * LRU 缓存最大条目数。
     * 修复：使用 LRU 缓存限制大小，避免无界增长导致内存泄漏。
     * 默认 256 个玩家条目，足以覆盖大多数场景。
     */
    private static final int MAX_CACHE_SIZE = 256;

    /** 上次预加载的玩家 chunk 位置（LRU 缓存，避免无界增长） */
    private static final Map<Long, Long> lastPreloadedChunkKey = new LinkedHashMap<Long, Long>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    /** 上次预加载的半径（LRU 缓存，避免无界增长） */
    private static final Map<Long, Integer> lastPreloadedRadius = new LinkedHashMap<Long, Integer>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private PlayerChunkPreloader() {}

    /**
     * 对 region 的本地玩家提交 chunk 异步预加载请求（同 region + 跨 region）。
     *
     * @param level              当前 region 的 ServerLevel
     * @param data               region 数据（提供 getLocalPlayers）
     * @param context            当前 region 的 tick 上下文（用于跨 region task 路由）
     * @param radiusChunks       同 region 预加载半径（chunk 数），如 2 = 5×5 chunks
     */
    public static void preload(final ServerLevel level, final RegionizedWorldData data,
                               final RegionTickContext context, final int radiusChunks) {
        final List<ServerPlayer> players = data.getLocalPlayers();
        if (players.isEmpty()) {
            return;
        }
        final ChunkSystemServerLevel chunkSystem = (ChunkSystemServerLevel) level;
        final int sameRegionRadius = Math.max(0, radiusChunks);
        final boolean crossRegionEnabled = EntityTickPerformanceConfig.crossRegionChunkPreloadEnabled;
        // 两层都关闭 → 提前 return（零开销）
        if (sameRegionRadius == 0 && !crossRegionEnabled) {
            return;
        }

        for (final ServerPlayer player : players) {
            // ─── 同 region 内预加载（玩家当前位置周围） ───
            if (sameRegionRadius > 0) {
                final BlockPos playerPos = player.blockPosition();
                final ChunkPos centerChunk = ChunkPos.containing(playerPos);
                final long cacheKey = (long) player.getId();
                final long centerChunkKey = centerChunk.pack();
                final Integer lastRadius = lastPreloadedRadius.get(cacheKey);
                final Long lastChunkKey = lastPreloadedChunkKey.get(cacheKey);
                if (lastRadius == null || lastRadius != sameRegionRadius || lastChunkKey == null || lastChunkKey != centerChunkKey) {
                    final Consumer<List<ChunkAccess>> onLoad = chunks -> {
                        if (chunks != null && !chunks.isEmpty() && LOGGER.isDebugEnabled()) {
                            LOGGER.debug("[ChunkPreload] async loaded {} chunks for player {} at {}",
                                chunks.size(), player.getName().getString(), centerChunk);
                        }
                    };
                    chunkSystem.moonrise$loadChunksAsync(playerPos, sameRegionRadius * 16,
                        ca.spottedleaf.concurrentutil.util.Priority.NORMAL, onLoad);
                    lastPreloadedChunkKey.put(cacheKey, centerChunkKey);
                    lastPreloadedRadius.put(cacheKey, sameRegionRadius);
                }
            }

            // ─── 跨 region 预加载（玩家即将进入的 B region） ───
            if (crossRegionEnabled) {
                try {
                    CrossRegionChunkPreloader.preload(level, player, context,
                        EntityTickPerformanceConfig.crossRegionChunkPreloadLookaheadChunks,
                        EntityTickPerformanceConfig.crossRegionChunkPreloadRadiusChunks);
                } catch (Throwable t) {
                    LOGGER.warn("[PlayerChunkPreloader] CrossRegionChunkPreloader failed for player {}: {}",
                        player.getName().getString(), t.getMessage());
                }
            }
        }
    }

    /**
     * 清理玩家缓存（玩家下线时调用，避免内存泄漏）。
     * 可以从 Bukkit 的 {@code PlayerQuitEvent} listener 调用。
     */
    public static synchronized void clearCacheForPlayer(final int playerEntityId) {
        final long key = (long) playerEntityId;
        synchronized (lastPreloadedChunkKey) {
            lastPreloadedChunkKey.remove(key);
        }
        synchronized (lastPreloadedRadius) {
            lastPreloadedRadius.remove(key);
        }
        CrossRegionChunkPreloader.clearCacheForPlayer(playerEntityId);
    }

    /**
     * 清理所有缓存（服务器关闭时调用）。
     */
    public static synchronized void clearAllCache() {
        synchronized (lastPreloadedChunkKey) {
            lastPreloadedChunkKey.clear();
        }
        synchronized (lastPreloadedRadius) {
            lastPreloadedRadius.clear();
        }
        CrossRegionChunkPreloader.clearAllCache();
    }

    /**
     * 获取缓存大小（用于监控）。
     */
    public static synchronized int getCacheSize() {
        synchronized (lastPreloadedChunkKey) {
            return lastPreloadedChunkKey.size();
        }
    }
}