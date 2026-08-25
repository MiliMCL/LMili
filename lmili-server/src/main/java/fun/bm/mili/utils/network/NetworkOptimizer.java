package fun.bm.mili.utils.network;

import fun.bm.mili.config.modules.optimizations.NetworkOptimizerConfig;
import org.bukkit.Bukkit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Network optimizer
 * Improvements:
 * - Per-player packet rate limiting (caps packets per tick per player to prevent client-side overflow)
 * - Entity track packet rate limiting (prevents redstone/farm machines from flooding entity track updates)
 * - Batch chunk send optimization
 * - Silent chunk loading (reduces unnecessary load packets)
 * - Bounded entity track cache with periodic cleanup (prevents OOM)
 * - Adaptive compression level based on server TPS
 * - Join-phase packet burst control (prevents stampede of chunk sends on join)
 */
public class NetworkOptimizer {

    private static final LongAdder packetsThrottled = new LongAdder();
    private static final LongAdder chunksBatchSent = new LongAdder();
    private static final LongAdder bytesCompressed = new LongAdder();
    private static final LongAdder joinPacketsSent = new LongAdder();
    private static final LongAdder packetsRateLimited = new LongAdder();

    /**
     * 硬编码的最大缓存大小 — 即使配置为 0（无限制）也强制限制，防止 OOM。
     * 修复：防止无界 map 增长导致内存泄漏。默认 10000 个实体条目。
     */
    private static final int HARD_MAX_CACHE_SIZE = 10000;

    private static final ConcurrentHashMap<java.util.UUID, Long> lastEntityTrackSend = new ConcurrentHashMap<>();

    // Per-player packet counter for the current tick, reset every tick via swap
    // Mili start - fix: non-final so it can be swapped atomically instead of cleared
    private static volatile ConcurrentHashMap<java.util.UUID, AtomicInteger> playerPacketCounts = new ConcurrentHashMap<>();
    // Mili end
    // Mili start - fix: use AtomicLong for thread-safe tick comparison
    private static final AtomicLong currentTick = new AtomicLong(-1);
    // Mili end

    // Periodic cleanup state
    private static volatile long lastCleanupTime = 0;
    private static final long CLEANUP_INTERVAL_MS = 30_000; // 修复：从 60 秒缩短到 30 秒，更频繁清理

    // Adaptive compression state
    private static volatile int adaptiveCompressionLevel = -1;

    public static void init() {
        if (!NetworkOptimizerConfig.enabled) return;
        Bukkit.getLogger().info("[Mili Network] Network optimizer enabled");
        // Register tick task for per-player packet counting and adaptive compression
        org.bukkit.Bukkit.getScheduler().runTaskTimer(
                org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(NetworkOptimizer.class),
                () -> onServerTick(getServerTick()),
                1L, 1L
        );
    }

    private static long getServerTick() {
        return org.bukkit.Bukkit.getCurrentTick();
    }

    /**
     * Check if an entity track packet should be throttled.
     * Used to prevent redstone/farm machines from generating excessive entity track updates.
     */
    public static boolean shouldThrottleEntityTrack(java.util.UUID entityId) {
        if (NetworkOptimizerConfig.entityTrackSendRateLimitMs <= 0) return false;

        // Periodic cleanup — prevents unbounded cache growth
        maybeCleanupStaleEntries();

        long now = System.currentTimeMillis();
        Long last = lastEntityTrackSend.get(entityId);
        if (last != null && (now - last) < NetworkOptimizerConfig.entityTrackSendRateLimitMs) {
            packetsThrottled.increment();
            return true;
        }
        lastEntityTrackSend.put(entityId, now);
        return false;
    }

    /**
     * Get recommended packet compression level (adaptive based on TPS).
     * Returns cached adaptive level; falls back to configured level.
     */
    public static int getRecommendedCompressionLevel() {
        if (adaptiveCompressionLevel >= 0) {
            return adaptiveCompressionLevel;
        }
        return NetworkOptimizerConfig.packetCompressionLevel;
    }

    /**
     * Should be called once per server tick to reset per-player packet counters
     * and update adaptive compression.
     */
    public static void onServerTick(long serverTick) {
        if (!NetworkOptimizerConfig.enabled) return;
        // Mili start - fix: use CAS to atomically check and update tick, preventing race condition
        // where two threads both pass the deduplication check
        long prev = currentTick.getAndSet(serverTick);
        if (prev == serverTick) return; // already processed this tick
        // Mili end

        // Mili start - fix: use swap-and-expire pattern instead of clear() to avoid losing
        // in-flight data from concurrent Folia region threads
        ConcurrentHashMap<java.util.UUID, AtomicInteger> oldCounts = playerPacketCounts;
        playerPacketCounts = new ConcurrentHashMap<>();
        // oldCounts is now isolated — any concurrent readers/writers on the old map
        // will complete their operations safely, and the old map will be GC'd
        // Mili end

        // Update adaptive compression level every 20 ticks (1 second)
        if (serverTick % 20 == 0) {
            updateAdaptiveCompression();
        }
    }

    /**
     * Check if a packet for the given player should be rate-limited.
     * Returns true if the packet should be dropped/queued for next tick.
     */
    public static boolean shouldRateLimitPlayerPacket(java.util.UUID playerId) {
        if (!NetworkOptimizerConfig.enabled) return false;
        if (NetworkOptimizerConfig.maxPacketsPerTickPerPlayer <= 0) return false;

        AtomicInteger counter = playerPacketCounts.computeIfAbsent(playerId, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        if (count > NetworkOptimizerConfig.maxPacketsPerTickPerPlayer) {
            packetsRateLimited.increment();
            return true;
        }
        return false;
    }

    /**
     * Track packets sent during player join phase (for rate control).
     * Returns true if within burst limit.
     */
    public static boolean trackJoinPacket(int count) {
        joinPacketsSent.add(count);
        int maxBurst = NetworkOptimizerConfig.maxJoinBurstPackets;
        if (maxBurst <= 0) return true; // no limit
        return joinPacketsSent.sum() <= maxBurst;
    }

    /**
     * Get the optimal chunk send batch size based on current server conditions.
     */
    public static int getOptimalChunkBatchSize() {
        return NetworkOptimizerConfig.chunkSendBatchSize;
    }

    /**
     * Check whether silent chunk loading is enabled.
     */
    public static boolean isSilentChunkLoadsEnabled() {
        return NetworkOptimizerConfig.silentChunkLoads;
    }

    /**
     * Update adaptive compression level based on current TPS.
     * High TPS -> use configured compression (higher ratio, save bandwidth).
     * Low TPS -> reduce compression level to save CPU.
     */
    private static void updateAdaptiveCompression() {
        double tps = getCurrentTps();
        if (tps >= 19.5) {
            // Server healthy, use configured compression for bandwidth savings
            adaptiveCompressionLevel = NetworkOptimizerConfig.packetCompressionLevel;
        } else if (tps >= 17.0) {
            // Mild stress, reduce compression slightly to save CPU
            adaptiveCompressionLevel = Math.max(1, NetworkOptimizerConfig.packetCompressionLevel - 2);
        } else if (tps >= 14.0) {
            // Moderate stress, use minimal compression
            adaptiveCompressionLevel = 1;
        } else {
            // Heavy stress, disable compression to save CPU
            adaptiveCompressionLevel = 0;
        }
    }

    private static double getCurrentTps() {
        // Mili - fix: use the accurate TPSTracker (always initialized by LagRemover at startup)
        // instead of reading TPS from a scoreboard objective. The previous approach depended on an
        // external "mili_tps" objective that may not exist, silently returning 20.0 and defeating
        // adaptive compression under real load.
        return fun.bm.mili.utils.performance.TPSTracker.getTPS();
    }

    /**
     * Periodic cleanup of stale entity track entries.
     * Runs at most once per CLEANUP_INTERVAL_MS.
     * Also enforces a hard cap on cache size if configured.
     */
    private static void maybeCleanupStaleEntries() {
        long now = System.currentTimeMillis();
        if ((now - lastCleanupTime) < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupTime = now;

        // Remove entries older than 30 seconds (修复：从 60 秒缩短到 30 秒)
        long cutoff = now - 30_000;
        lastEntityTrackSend.entrySet().removeIf(e -> e.getValue() < cutoff);

        // Hard cap: 使用配置值和硬编码最小值中的较小者
        int configuredMaxSize = NetworkOptimizerConfig.entityTrackCacheMaxSize;
        int effectiveMaxSize = configuredMaxSize > 0
                ? Math.min(configuredMaxSize, HARD_MAX_CACHE_SIZE)
                : HARD_MAX_CACHE_SIZE;

        if (lastEntityTrackSend.size() > effectiveMaxSize) {
            // Remove oldest 25% of entries to avoid frequent cleanups
            int toRemove = lastEntityTrackSend.size() - effectiveMaxSize + (effectiveMaxSize / 4);
            lastEntityTrackSend.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .limit(toRemove)
                    .forEach(e -> lastEntityTrackSend.remove(e.getKey()));
        }
    }

    /**
     * Force cleanup of stale entries (called externally every 60 seconds).
     */
    public static void cleanupStaleEntries() {
        long cutoff = System.currentTimeMillis() - 30_000;
        lastEntityTrackSend.entrySet().removeIf(e -> e.getValue() < cutoff);

        // 强制大小限制
        if (lastEntityTrackSend.size() > HARD_MAX_CACHE_SIZE) {
            int toRemove = lastEntityTrackSend.size() - HARD_MAX_CACHE_SIZE + (HARD_MAX_CACHE_SIZE / 4);
            lastEntityTrackSend.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .limit(toRemove)
                    .forEach(e -> lastEntityTrackSend.remove(e.getKey()));
        }
    }

    public static Map<String, Object> getStats() {
        return Map.of(
                "packets_throttled", packetsThrottled.sum(),
                "packets_rate_limited", packetsRateLimited.sum(),
                "chunks_batch_sent", chunksBatchSent.sum(),
                "join_packets_sent", joinPacketsSent.sum(),
                "tracked_entities", lastEntityTrackSend.size(),
                "compression_level", getRecommendedCompressionLevel(),
                "adaptive_compression", adaptiveCompressionLevel >= 0,
                "enabled", NetworkOptimizerConfig.enabled
        );
    }

    public static void shutdown() {
        lastEntityTrackSend.clear();
        playerPacketCounts.clear();
        packetsThrottled.reset();
        packetsRateLimited.reset();
        chunksBatchSent.reset();
        joinPacketsSent.reset();
        bytesCompressed.reset();
        adaptiveCompressionLevel = -1;
    }
}
