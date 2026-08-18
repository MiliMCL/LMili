package fun.bm.mili.chunk;

import com.mojang.logging.LogUtils;
import fun.bm.mili.chunk.phase.HotnessUpdatePhase;
import fun.bm.mili.chunk.phase.LifecyclePhase;
import fun.bm.mili.chunk.phase.ViewDistancePhase;
import fun.bm.mili.config.modules.optimizations.ChunkSystemConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mili 区块系统 —— 管理 chunk 生命周期、热度追踪、异步操作。
 *
 * <p>内部委托给 {@link ChunkPipeline} 执行阶段逻辑，保持静态入口向后兼容。
 */
public final class MiliChunkSystem {

    private MiliChunkSystem() {}

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static BukkitTask mainThreadTask;
    private static ScheduledExecutorService asyncExecutor;

    private static ChunkPipeline pipeline;
    private static final AsyncChunkProcessor asyncProcessor = new AsyncChunkProcessor();

    private static final AtomicLong totalChunkLoads = new AtomicLong(0);
    private static final AtomicLong totalChunkUnloads = new AtomicLong(0);
    private static final AtomicLong cacheHits = new AtomicLong(0);
    private static final AtomicLong cacheMisses = new AtomicLong(0);

    public static void init(org.bukkit.plugin.Plugin plugin) {
        if (!ChunkSystemConfig.enabled) return;
        if (!initialized.compareAndSet(false, true)) return;

        if (plugin == null) {
            initialized.set(false);
            throw new IllegalArgumentException("Mili plugin instance is required for MiliChunkSystem");
        }

        // 创建管线
        pipeline = new ChunkPipeline(List.of(
                new HotnessUpdatePhase(),
                new ViewDistancePhase(),
                new LifecyclePhase(totalChunkUnloads)
        ));

        asyncExecutor = Executors.newScheduledThreadPool(
                ChunkSystemConfig.asyncThreads,
                r -> {
                    Thread t = new Thread(r, "Mili-ChunkWorker");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY + 1);
                    return t;
                }
        );

        for (World world : Bukkit.getWorlds()) {
            registerWorld(world);
        }

        mainThreadTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                MiliChunkSystem::tick,
                1L,
                1L
        );

        asyncExecutor.scheduleAtFixedRate(
                asyncProcessor::processQueue,
                0,
                50,
                TimeUnit.MILLISECONDS
        );

        LogUtils.getLogger().info(
                "[Mili] MiliChunkSystem v3.0 initialized with {} async threads",
                ChunkSystemConfig.asyncThreads
        );
    }

    public static void shutdown() {
        if (!initialized.compareAndSet(true, false)) return;

        if (mainThreadTask != null) {
            mainThreadTask.cancel();
            mainThreadTask = null;
        }

        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            asyncExecutor = null;
        }

        if (pipeline != null) {
            pipeline.clear();
            pipeline = null;
        }
        asyncProcessor.clear();

        LogUtils.getLogger().info("[Mili] MiliChunkSystem shutdown complete");
    }

    private static void tick() {
        long startNanos = System.nanoTime();

        try {
            if (pipeline != null) {
                pipeline.tick();
            }
        } catch (Throwable e) {
            LogUtils.getLogger().error("[Mili] Chunk system tick error", e);
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        if (elapsedNanos > 5_000_000L) {
            LogUtils.getLogger().warn(
                    "[Mili] Chunk system tick took {}ms", elapsedNanos / 1_000_000L
            );
        }
    }

    public static void registerWorld(World world) {
        if (pipeline != null) {
            pipeline.registerWorld(world);
        }
    }

    public static void unregisterWorld(World world) {
        if (pipeline != null) {
            pipeline.unregisterWorld(world);
        }
    }

    public static void queueAsyncOperation(AsyncChunkProcessor.AsyncChunkOperation operation) {
        if (!asyncProcessor.enqueue(operation)) {
            operation.onRejected();
        }
    }

    public static ChunkHotness getChunkHotness(World world, int chunkX, int chunkZ) {
        if (pipeline == null) return null;
        WorldChunkData data = pipeline.getWorldData(world);
        if (data == null) return null;
        return data.getHotness(chunkX, chunkZ);
    }

    public static CompletableFuture<Void> preloadArea(World world, int centerX, int centerZ, int radius) {
        return asyncProcessor.preloadArea(world, centerX, centerZ, radius);
    }

    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_chunk_loads", totalChunkLoads.get());
        stats.put("total_chunk_unloads", totalChunkUnloads.get());
        stats.put("total_async_ops", asyncProcessor.getTotalOps());
        stats.put("cache_hits", cacheHits.get());
        stats.put("cache_misses", cacheMisses.get());
        stats.put("async_queue_size", asyncProcessor.queueSize());
        stats.put("registered_worlds", pipeline != null ? pipeline.getWorldCount() : 0);

        long totalHotChunks = 0;
        long activeChunks = 0;
        if (pipeline != null) {
            for (WorldChunkData data : pipeline.worldData.values()) {
                totalHotChunks += data.getTotalHotChunks();
                activeChunks += data.getActiveChunks();
            }
        }
        stats.put("hot_chunks", totalHotChunks);
        stats.put("active_chunks", activeChunks);

        return stats;
    }
}
