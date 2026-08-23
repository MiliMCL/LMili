package fun.bm.mili.lmili.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import fun.bm.mili.lmili.data.OptimizedLinearRegionFile;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Background flusher for {@link OptimizedLinearRegionFile} ({@code o_linear}).
 *
 * <p>A single scheduled checker thread scans managed region files and, once a file has not been
 * written to for {@code flushOfWriteTimeoutMs}, dispatches its master-file sync onto a fixed pool
 * of I/O worker threads. This replaces the previous behaviour of performing every sync inline on
 * the checker thread, which serialized all region I/O behind one thread and caused deadline blowup
 * under load.
 */
public class OptimizedLinearRegionFileFlusher implements Runnable {
    private static final Logger logger = LogUtils.getLogger();

    private final Set<OptimizedLinearRegionFile> inManagement = new ObjectArraySet<>();
    private final ScheduledFuture<?> flusherChecker;
    private final ExecutorService ioWorkerPool;
    // Mili start - fix: Store scheduler executor as field for proper shutdown
    private final ScheduledExecutorService schedulerPool;
    // Mili end
    private final long flushOfWriteTimeoutMs;
    // Mili - forces a master-file sync after this many milliseconds even when a region is written
    // to continuously, bounding the amount of data a power failure can lose.
    private final long maxSyncAgeMs;

    // Mili start - fix: idempotent shutdown guard. shutdown() may be invoked both from the server
    // stop path (MinecraftServer.stopPart2) and from the JVM shutdown hook; the second call must be
    // a no-op rather than re-awaiting already-terminated pools.
    private final java.util.concurrent.atomic.AtomicBoolean shutdownInitiated = new java.util.concurrent.atomic.AtomicBoolean(false);
    // Mili end

    public OptimizedLinearRegionFileFlusher(int nIoThreads, long checkIntervalMs, long flushOfWriteTimeoutMs, long maxSyncAgeMs) {
        Validate.isTrue(nIoThreads > 0, "Number of I/O threads must > 0!");
        Validate.isTrue(checkIntervalMs > 0, "Check interval must > 0");
        Validate.isTrue(flushOfWriteTimeoutMs > 0, "Flush of write timeout must > 0");
        Validate.isTrue(maxSyncAgeMs >= flushOfWriteTimeoutMs, "Max sync age must be >= flush timeout");

        this.ioWorkerPool = Executors.newFixedThreadPool(nIoThreads, new ThreadFactoryBuilder()
                .setNameFormat("OptimizedLinearRegionFile I/O Worker %d")
                .setDaemon(true)
                .build()
        );
        // Mili start - fix: Store scheduler executor as field so it can be properly shut down
        this.schedulerPool = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                        .setNameFormat("OptimizedLinearRegionFile Flusher Checker")
                        .setDaemon(true)
                        .build());
        this.flusherChecker = this.schedulerPool
                .scheduleWithFixedDelay(this, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        // Mili end
        this.flushOfWriteTimeoutMs = flushOfWriteTimeoutMs;
        this.maxSyncAgeMs = maxSyncAgeMs;
    }

    public void shutdown() {
        // Mili start - fix: idempotent + bounded await.
        if (!this.shutdownInitiated.compareAndSet(false, true)) {
            return; // already shut down (or shutting down)
        }

        this.flusherChecker.cancel(false);

        // Stop the checker first and wait for its current run to finish, so no further sync tasks
        // can be enqueued onto the worker pool (avoids RejectedExecutionException).
        this.schedulerPool.shutdown();
        try {
            this.schedulerPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Drain in-flight syncs so no buffered (swap-file) chunk data is lost on server stop.
        this.ioWorkerPool.shutdown();
        try {
            this.ioWorkerPool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Force-interrupt any stragglers (should be none after the awaits above).
        this.schedulerPool.shutdownNow();
        this.ioWorkerPool.shutdownNow();
    }

    @Override
    public void run() {
        final long currentNanos = System.nanoTime();
        final OptimizedLinearRegionFile[] copied;

        synchronized (this) {
            copied = this.inManagement.toArray(new OptimizedLinearRegionFile[0]);
        }

        final List<OptimizedLinearRegionFile> toRemove = new ObjectArrayList<>();
        for (OptimizedLinearRegionFile file : copied) {
            // Mili start - fix: Race condition — isClosed/shouldSync/markAsBeingSynced must be
            // atomic w.r.t. close. Now close() holds writeLock and flusher checks under readLock,
            // so all reads happen within the readLock to prevent TOCTOU with close.
            if (!file.softReadLock()) {
                continue;
            }

            boolean closed;
            boolean needsSync;
            boolean markedSync;
            long lastWriteNanos;
            long lastSyncedNanos;

            try {
                closed = file.isClosedRaw();
                if (closed) {
                    needsSync = false;
                    markedSync = false;
                    lastWriteNanos = 0;
                    lastSyncedNanos = 0;
                } else {
                    needsSync = file.shouldSync();
                    lastWriteNanos = file.getLastWritten();
                    lastSyncedNanos = file.getLastSynced();
                    // Try mark as syncing while we still hold the readLock
                    markedSync = needsSync && file.markAsBeingSynced();
                }
            } finally {
                file.releaseReadLock();
            }

            if (closed) {
                toRemove.add(file);
                continue;
            }

            if (!markedSync) {
                continue;
            }

            final long timeElapsed = (currentNanos - lastWriteNanos) / 1_000_000;
            // Mili - force a sync when either the idle timeout has elapsed since the last write,
            // OR the maximum sync age has elapsed since the last sync (bounds power-loss data loss
            // even under continuous writes).
            final long timeSinceSync = (currentNanos - lastSyncedNanos) / 1_000_000;

            // if deadline(timeout) reached
            if (timeElapsed >= this.flushOfWriteTimeoutMs || timeSinceSync >= this.maxSyncAgeMs) {
                // Mili start - perf: dispatch the actual master-file sync onto the I/O worker pool.
                // Previously this ran inline on the checker thread, serializing every region's I/O
                // behind a single thread and delaying the checks of other regions.
                try {
                    this.ioWorkerPool.execute(() -> {
                        try {
                            file.syncIfNeeded();
                        } catch (IOException e) {
                            logger.error("Failed to sync master file: ", e);
                        }
                    });
                } catch (RejectedExecutionException rejected) {
                    // Mili - shutdown is draining the checker before the worker pool; this can only
                    // happen if the pool was independently shut down. Release the claim so the file
                    // is not permanently stuck "being synced", then re-attempt inline so data still
                    // reaches disk.
                    file.clearBeingSynced();
                    try {
                        file.syncIfNeeded();
                    } catch (IOException e) {
                        logger.error("Failed to sync master file: ", e);
                    }
                }
                // Mili end
            } else {
                // deadline not yet reached: release the "being synced" claim so the next check
                // cycle can re-evaluate this file without a permanent claim.
                file.clearBeingSynced();
            }
        }
        // Mili end

        synchronized (this) {
            // clean closed files
            for (OptimizedLinearRegionFile file : toRemove) {
                this.inManagement.remove(file);
            }
        }
    }

    public void removeFile(OptimizedLinearRegionFile fileToRemove) {
        synchronized (this) {
            this.inManagement.remove(fileToRemove);
        }
    }

    public void addFile(OptimizedLinearRegionFile fileToAdd) {
        synchronized (this) {
            this.inManagement.add(fileToAdd);
        }
    }
}
