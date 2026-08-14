package fun.bm.mili.lmili.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import fun.bm.mili.lmili.data.BufferedLinearRegionFile;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

public class BufferedLinearRegionFileFlusher implements Runnable {
    private static final Logger logger = LogUtils.getLogger();

    private final Set<BufferedLinearRegionFile> inManagement = new ObjectArraySet<>();
    private final ScheduledFuture<?> flusherChecker;
    private final Executor ioWorkerPool;
    // Mili start - fix: Store scheduler executor as field for proper shutdown
    private final ScheduledExecutorService schedulerPool;
    // Mili end
    private final long flushOfWriteTimeoutMs;

    public BufferedLinearRegionFileFlusher(int nIoThreads, long checkIntervalMs, long flushOfWriteTimeoutMs) {
        Validate.isTrue(nIoThreads > 0, "Number of I/O threads must > 0!");
        Validate.isTrue(checkIntervalMs > 0, "Check interval must > 0");
        Validate.isTrue(flushOfWriteTimeoutMs > 0, "Flush of write timeout must > 0");

        this.ioWorkerPool = Executors.newFixedThreadPool(nIoThreads, new ThreadFactoryBuilder()
                .setNameFormat("BufferedLinearRegionFile I/O Worker %d")
                .setDaemon(true)
                .build()
        );
        // Mili start - fix: Store scheduler executor as field so it can be properly shut down
        this.schedulerPool = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                        .setNameFormat("BufferedLinearRegionFile Flusher Checker")
                        .setDaemon(true)
                        .build());
        this.flusherChecker = this.schedulerPool
                .scheduleWithFixedDelay(this, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        // Mili end
        this.flushOfWriteTimeoutMs = flushOfWriteTimeoutMs;
    }

    public void shutdown() {
        this.flusherChecker.cancel(false);

        // Mili start - fix: Shutdown the scheduler pool (previously leaked, causing thread accumulation on hot reload)
        this.schedulerPool.shutdown();
        // Mili end

        ((ExecutorService) this.ioWorkerPool).shutdown();
        for (; ; ) {
            try {
                if (((ExecutorService) this.ioWorkerPool).awaitTermination(100, TimeUnit.MILLISECONDS)
                        && this.schedulerPool.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        final long currentNanos = System.nanoTime();
        final BufferedLinearRegionFile[] copied;

        synchronized (this) {
            copied = this.inManagement.toArray(new BufferedLinearRegionFile[0]);
        }

        final List<BufferedLinearRegionFile> toRemove = new ObjectArrayList<>();
        for (BufferedLinearRegionFile file : copied) {
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

            try {
                closed = file.isClosedRaw();
                if (closed) {
                    needsSync = false;
                    markedSync = false;
                    lastWriteNanos = 0;
                } else {
                    needsSync = file.shouldSync();
                    lastWriteNanos = file.getLastWritten();
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

            // if deadline(timeout) reached
            if (timeElapsed >= this.flushOfWriteTimeoutMs) {
                // Mili start - fix: Sync under readLock to prevent channel close during sync.
                // Previously the sync was done in a separate thread without holding any lock,
                // allowing close() to close the channel while sync was reading from it.
                try {
                    file.syncIfNeeded();
                } catch (IOException e) {
                    logger.error("Failed to sync master file: ", e);
                }
                // Mili end
            }
        }
        // Mili end

        synchronized (this) {
            // clean closed files
            for (BufferedLinearRegionFile file : toRemove) {
                this.inManagement.remove(file);
            }
        }
    }

    public void removeFile(BufferedLinearRegionFile fileToRemove) {
        synchronized (this) {
            this.inManagement.remove(fileToRemove);
        }
    }

    public void addFile(BufferedLinearRegionFile fileToAdd) {
        synchronized (this) {
            this.inManagement.add(fileToAdd);
        }
    }
}
