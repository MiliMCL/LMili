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
    // Mili start - fix: make fields volatile for cross-thread visibility
    private static volatile BukkitTask mainThreadTask;
    private static volatile ScheduledExecutorService asyncExecutor;
    private static volatile ChunkPipeline pipeline;
    // Mili end
    private static final AsyncChunkProcessor asyncProcessor = new AsyncChunkProcessor();

    // Mili start - fix: remove dead counters that are never incremented (always report 0)
    // totalChunkUnloads is passed to LifecyclePhase and incremented there
    private static final AtomicLong totalChunkUnloads = new AtomicLong(0);
    // Mili end

    public static void init(org.bukkit.plugin.Plugin plugin) {
        if (!ChunkSystemConfig.enabled) return;
        // Mili start - fix: use synchronized to prevent init() race where initialized is set to true
        // before pipeline is assigned, causing other threads to see null pipeline.
        synchronized (MiliChunkSystem.class) {
            if (initialized.get()) return;

            if (plugin == null) {
                throw new IllegalArgumentException("Mili plugin instance is required for MiliChunkSystem");
            }

            // 创建管线
            ChunkPipeline newPipeline = new ChunkPipeline(List.of(
                    new HotnessUpdatePhase(),
                    new ViewDistancePhase(),
                    new LifecyclePhase(totalChunkUnloads)
            ));

            ScheduledExecutorService newExecutor = Executors.newScheduledThreadPool(
                    ChunkSystemConfig.asyncThreads,
                    r -> {
                        // Mili start - fix: append unique suffix to thread name for debugging
                        Thread t = new Thread(r, "Mili-ChunkWorker-" + Thread.currentThread().threadId());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY + 1);
                        return t;
                        // Mili end
                    }
            );

            for (World world : Bukkit.getWorlds()) {
                newPipeline.registerWorld(world);
            }

            // Assign fields BEFORE setting initialized to true
            pipeline = newPipeline;
            asyncExecutor = newExecutor;

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

            initialized.set(true);

            LogUtils.getLogger().info(
                    "[Mili] MiliChunkSystem v3.0 initialized with {} async threads",
                    ChunkSystemConfig.asyncThreads
            );
        }
        // Mili end
    }

    public static void shutdown() {
        // Mili start - fix: use synchronized to match init() and prevent race conditions
        synchronized (MiliChunkSystem.class) {
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
        // Mili end
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
        // Mili start - fix: removed dead counters (totalChunkLoads, cacheHits, cacheMisses)
        // that were never incremented and always reported 0
        stats.put("total_chunk_unloads", totalChunkUnloads.get());
        stats.put("total_async_ops", asyncProcessor.getTotalOps());
        stats.put("async_queue_size", asyncProcessor.queueSize());
        ChunkPipeline p = pipeline; // local volatile read
        stats.put("registered_worlds", p != null ? p.getWorldCount() : 0);

        long totalHotChunks = 0;
        long activeChunks = 0;
        if (p != null) {
            for (WorldChunkData data : p.getWorldDataValues()) {
                totalHotChunks += data.getTotalHotChunks();
                activeChunks += data.getActiveChunks();
            }
        }
        stats.put("hot_chunks", totalHotChunks);
        stats.put("active_chunks", activeChunks);
        // Mili end

        return stats;
    }
}
