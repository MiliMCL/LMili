package fun.bm.mili.chunk;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.optimizations.ChunkSystemConfig;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

final class AsyncChunkProcessor {

    private final ConcurrentLinkedQueue<AsyncChunkOperation> queue = new ConcurrentLinkedQueue<>();
    // Mili start - fix: AtomicInteger counter to avoid O(n) ConcurrentLinkedQueue.size() calls
    private final AtomicLong queueSize = new AtomicLong(0);
    // Mili end
    private final AtomicLong totalOps = new AtomicLong(0);

    AsyncChunkProcessor() {}

    void processQueue() {
        int processed = 0;
        long deadline = System.nanoTime() + ChunkSystemConfig.asyncTimeBudgetNs;

        while (processed < ChunkSystemConfig.maxAsyncOpsPerCycle && System.nanoTime() < deadline) {
            AsyncChunkOperation op = queue.poll();
            // Mili start - fix: decrement counter on dequeue, but guard against going negative
            // (can happen if clear() resets counter to 0 between poll() and decrementAndGet())
            if (op == null) break;
            queueSize.updateAndGet(current -> Math.max(0, current - 1));
            // Mili end

            try {
                op.execute();
                totalOps.incrementAndGet();
                processed++;
            // Mili start - fix: catch Throwable to prevent silent thread death on Error
            } catch (Throwable e) {
            // Mili end
                LogUtils.getLogger().warn("[Mili] Async chunk operation failed", e);
            }
        }
    }

    boolean enqueue(AsyncChunkOperation operation) {
        // Mili start - fix: use CAS loop to atomically reserve a slot, preventing TOCTOU race
        // where two threads both pass the size check and both add, exceeding the limit.
        long current;
        do {
            current = queueSize.get();
            if (current >= ChunkSystemConfig.maxAsyncQueueSize) {
                return false;
            }
        } while (!queueSize.compareAndSet(current, current + 1));
        // Slot reserved atomically — safe to add
        queue.add(operation);
        return true;
        // Mili end
    }

    int queueSize() {
        // Mili start - fix: return AtomicInteger counter instead of O(n) queue.size()
        return (int) queueSize.get();
        // Mili end
    }

    long getTotalOps() {
        return totalOps.get();
    }

    void clear() {
        // Mili start - fix: reset counter BEFORE clearing queue to prevent race where
        // enqueue() increments counter after clear sets it to 0, causing desync.
        // Setting to 0 first means any concurrent enqueue() will see 0 < max and proceed,
        // but the CAS in enqueue() will correctly increment from 0.
        queueSize.set(0);
        queue.clear();
        // Mili end
    }

    CompletableFuture<Void> preloadArea(World world, int centerX, int centerZ, int radius) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        boolean accepted = enqueue(new AsyncChunkOperation() {
            @Override
            public void execute() {
                try {
                    int chunksLoaded = 0;
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            Chunk chunk = world.getChunkAt(centerX + dx, centerZ + dz);
                            if (chunk != null && chunk.isLoaded()) {
                                chunksLoaded++;
                            }
                        }
                    }
                    future.complete(null);
                    LogUtils.getLogger().debug(
                            "[Mili] Preloaded {} chunks around ({}, {})",
                            chunksLoaded, centerX, centerZ
                    );
                // Mili start - fix: catch Throwable to prevent silent thread death on Error
                } catch (Throwable e) {
                // Mili end
                    future.completeExceptionally(e);
                }
            }

            @Override
            public void onRejected() {
                future.completeExceptionally(new RuntimeException("Async queue full"));
            }
        });

        if (!accepted) {
            future.completeExceptionally(new RuntimeException("Async queue full"));
        }

        return future;
    }

    interface AsyncChunkOperation {
        void execute();
        default void onRejected() {}
    }
}