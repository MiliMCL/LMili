package fun.bm.mili.utils.chunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

public class ChunkDeltaCompressor {
    private static volatile boolean enabled = false;
    private static final ConcurrentHashMap<Long, byte[]> snapshots = new ConcurrentHashMap<>();
    private static final int MAX_SNAPSHOTS = 4096;
    private static final AtomicLong totalCompressions = new AtomicLong();
    private static final AtomicLong bytesSaved = new AtomicLong();
    private static final AtomicLong totalBytes = new AtomicLong();

    // Mili start - fix: use Deflater pool instead of ThreadLocal to prevent native memory leak.
    // ThreadLocal Deflaters never call end(), leaking native memory (16-64KB per thread).
    // A bounded pool releases Deflaters on shutdown.
    private static final java.util.concurrent.ConcurrentLinkedQueue<Deflater> DEFLATER_POOL =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int MAX_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;

    private static Deflater acquireDeflater() {
        Deflater d = DEFLATER_POOL.poll();
        if (d == null) {
            d = new Deflater(fun.bm.mili.config.modules.optimizations.ChunkDeltaCompressionConfig.compressionLevel);
        } else {
            d.reset();
        }
        return d;
    }

    private static void releaseDeflater(Deflater d) {
        if (DEFLATER_POOL.size() < MAX_POOL_SIZE) {
            DEFLATER_POOL.offer(d);
        } else {
            d.end(); // pool full, release native memory
        }
    }

    /** Call on server shutdown to release all native memory */
    public static void shutdown() {
        Deflater d;
        while ((d = DEFLATER_POOL.poll()) != null) {
            d.end();
        }
        snapshots.clear();
    }
    // Mili end

    public static void setEnabled(boolean v) { enabled = v; }
    public static boolean isEnabled() { return enabled; }

    public static byte[] computeDelta(long chunkKey, byte[] currentState) {
        if (!enabled) return currentState;

        byte[] previous = snapshots.get(chunkKey);
        if (previous == null) {
            evictIfFull();
            // Mili start - fix: clone before storing to prevent mutation corruption.
            // The caller may reuse the byte array, which would corrupt the snapshot.
            snapshots.put(chunkKey, currentState.clone());
            totalBytes.addAndGet(currentState.length);
            return currentState;
        }

        int minLength = Math.min(previous.length, currentState.length);
        int diffCount = 0;

        // Mili start - fix: compare as longs (8 bytes at a time) for better performance
        int longLength = minLength / 8;
        for (int i = 0; i < longLength; i++) {
            if (getLong(previous, i) != getLong(currentState, i)) diffCount += 8;
        }
        for (int i = longLength * 8; i < minLength; i++) {
            if (previous[i] != currentState[i]) diffCount++;
        }
        // Mili end
        diffCount += Math.abs(previous.length - currentState.length);

        totalBytes.addAndGet(currentState.length);

        if (diffCount < currentState.length / 4) {
            // Mili start - fix: use pooled Deflater instead of ThreadLocal to prevent native memory leak
            Deflater deflater = acquireDeflater();
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                deflater.setInput(currentState);
                deflater.finish();
                byte[] buffer = new byte[1024];
                while (!deflater.finished()) {
                    int count = deflater.deflate(buffer);
                    baos.write(buffer, 0, count);
                }

                byte[] compressed = baos.toByteArray();
                if (compressed.length < currentState.length) {
                    totalCompressions.incrementAndGet();
                    bytesSaved.addAndGet(currentState.length - compressed.length);
                    evictIfFull();
                    snapshots.put(chunkKey, currentState.clone());
                    return compressed;
                }
            } catch (Throwable ignored) {
                // Compression failed, fall through to store uncompressed
            } finally {
                releaseDeflater(deflater);
            }
            // Mili end
        }

        evictIfFull();
        snapshots.put(chunkKey, currentState.clone());
        return currentState;
    }

    // Mili start - fix: helper to read long from byte array at given index (8 bytes)
    private static long getLong(byte[] arr, int index) {
        int i = index * 8;
        return ((long) arr[i] & 0xff) |
               (((long) arr[i + 1] & 0xff) << 8) |
               (((long) arr[i + 2] & 0xff) << 16) |
               (((long) arr[i + 3] & 0xff) << 24) |
               (((long) arr[i + 4] & 0xff) << 32) |
               (((long) arr[i + 5] & 0xff) << 40) |
               (((long) arr[i + 6] & 0xff) << 48) |
               (((long) arr[i + 7] & 0xff) << 56);
    }
    // Mili end

    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("Enabled", enabled);
        stats.put("Tracked Chunks", snapshots.size());
        stats.put("Total Compressions", totalCompressions.get());
        stats.put("Bytes Saved", formatSize(bytesSaved.get()));
        stats.put("Total Processed", formatSize(totalBytes.get()));
        return stats;
    }

    // Mili start - fix: snapshots Map 无上限，超过 MAX_SNAPSHOTS 时清理最旧条目
    private static void evictIfFull() {
        if (snapshots.size() >= MAX_SNAPSHOTS) {
            // Remove oldest entries (first quarter) to amortize eviction cost
            int toRemove = MAX_SNAPSHOTS / 4;
            java.util.Iterator<Long> it = snapshots.keySet().iterator();
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }
    // Mili end

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
