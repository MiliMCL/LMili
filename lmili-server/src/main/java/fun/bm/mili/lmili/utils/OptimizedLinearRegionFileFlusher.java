package fun.bm.mili.lmili.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import fun.bm.mili.lmili.data.OptimizedLinearRegionFile;
import fun.bm.mili.lmili.runtime.io.FlusherObserver;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
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

    // Mili start - AdaptiveRuntime (ARCHITECTURE_AdaptiveRuntime.md §5.4 / D-08):
    // 可选观察者钩子。null 路径行为与现状完全一致（所有回调点均判空）；observer 异常一律
    // 捕获吞掉并记日志，永不反向破坏 flusher。
    private volatile FlusherObserver observer;

    public OptimizedLinearRegionFileFlusher(int nIoThreads, long checkIntervalMs, long flushOfWriteTimeoutMs, long maxSyncAgeMs) {
        this(nIoThreads, checkIntervalMs, flushOfWriteTimeoutMs, maxSyncAgeMs, null);
    }

    /**
     * 5 参构造器（D-08）：新增可选 {@link FlusherObserver}。
     *
     * <p>旧 4 参构造器委托传 null —— null 路径零行为变化。
     *
     * @param observer 观察者钩子（可为 null；晚绑定可经 {@link #setObserver}）
     */
    public OptimizedLinearRegionFileFlusher(int nIoThreads, long checkIntervalMs, long flushOfWriteTimeoutMs, long maxSyncAgeMs,
                                            @Nullable FlusherObserver observer) {
        Validate.isTrue(nIoThreads > 0, "Number of I/O threads must > 0!");
        Validate.isTrue(checkIntervalMs > 0, "Check interval must > 0");
        Validate.isTrue(flushOfWriteTimeoutMs > 0, "Flush of write timeout must > 0");
        Validate.isTrue(maxSyncAgeMs >= flushOfWriteTimeoutMs, "Max sync age must be >= flush timeout");

        // Mili start - AdaptiveRuntime §5.4 step 3 (D-09)：动态 worker 池。
        // ThreadPoolExecutor(core==max, LinkedBlockingQueue) 与 Executors.newFixedThreadPool 语义等价；
        // 额外支持 resize(int)（Phase 4 启用；对 shutdownInitiated 二次检查）。
        this.ioWorkerPool = new ThreadPoolExecutor(nIoThreads, nIoThreads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder()
                        .setNameFormat("OptimizedLinearRegionFile I/O Worker %d")
                        .setDaemon(true)
                        .build()
        );
        // Mili end
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
        this.observer = observer;
    }

    /** 晚绑定观察者（幂等；运行时装配期调用，D-08）。null 路径零行为变化。 */
    public void setObserver(@Nullable FlusherObserver observer) {
        this.observer = observer;
    }

    @Nullable
    public FlusherObserver observer() {
        return observer;
    }

    /**
     * 动态调整 IO worker 数（Phase 4，§5.4 / D-09）。
     *
     * <p>core==max 池 resize；对 {@link #shutdownInitiated} 做二次检查，杜绝 shutdown 排水期间
     * resize 把已关闭池重新"撑大"的竞态（§6.3 R2）。shutdown 已启动时静默 no-op。
     *
     * @param n 目标 worker 数（<1 忽略；内部钳制 [1, 256] 防恶意放大）
     */
    public void resize(int n) {
        if (n < 1) {
            return;
        }
        if (this.shutdownInitiated.get()) {
            return; // 关闭已启动：拒绝 resize（§6.3 R2：shutdown 路径不变）
        }
        final int capped = Math.max(1, Math.min(256, n));
        if (this.ioWorkerPool instanceof ThreadPoolExecutor tpe) {
            if (tpe.getMaximumPoolSize() != capped) {
                tpe.setCorePoolSize(capped);
                tpe.setMaximumPoolSize(capped);
                logger.debug("OptimizedLinearRegionFile I/O pool resized to {} workers", capped);
            }
        }
    }

    /** 当前 IO worker 池大小（动态调整后；未知返回 -1） */
    public int ioPoolSize() {
        if (this.ioWorkerPool instanceof ThreadPoolExecutor tpe) {
            return tpe.getMaximumPoolSize();
        }
        return -1;
    }

    /** 当前 IO worker 数（RuntimeBootstrap 装配日志用；= ioPoolSize()） */
    public int getIoThreadCount() {
        return ioPoolSize();
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
                // Mili start - AdaptiveRuntime §5.4 (D-08)：派发前观察者闸门。
                // onDispatch 返回 false = 本周期跳过派发（延后）；flusher 负责 clearBeingSynced，
                // 保证下个 checker 周期可重估（数据不丢失）。observer 异常 fail-open（放行）。
                final FlusherObserver obs = this.observer;
                if (obs != null) {
                    boolean allow;
                    try {
                        allow = obs.onDispatch(file);
                    } catch (Throwable t) {
                        logger.warn("FlusherObserver.onDispatch failed (fail-open)", t);
                        allow = true;
                    }
                    if (!allow) {
                        file.clearBeingSynced();
                        continue;
                    }
                }
                // Mili start - perf: dispatch the actual master-file sync onto the I/O worker pool.
                // Previously this ran inline on the checker thread, serializing every region's I/O
                // behind a single thread and delaying the checks of other regions.
                try {
                    this.ioWorkerPool.execute(() -> {
                        final long syncStartNanos = System.nanoTime();
                        boolean success = false;
                        long bytesApprox = 0;
                        final FlusherObserver taskObs = obs;
                        try {
                            if (taskObs != null) {
                                try {
                                    taskObs.onSyncDispatched();
                                } catch (Throwable t) {
                                    logger.warn("FlusherObserver.onSyncDispatched failed (ignored)", t);
                                }
                            }
                            // 近似脏字节：同步前未落盘的数据量（只读原子读，不触碰锁语义）
                            bytesApprox = Math.max(0, file.getLastWritten() - file.getLastSynced());
                            file.syncIfNeeded();
                            success = true;
                        } catch (IOException e) {
                            logger.error("Failed to sync master file: ", e);
                        } finally {
                            if (taskObs != null) {
                                try {
                                    taskObs.onSyncCompleted(file, System.nanoTime() - syncStartNanos, bytesApprox, success);
                                } catch (Throwable t) {
                                    logger.warn("FlusherObserver.onSyncCompleted failed (ignored)", t);
                                }
                            }
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

        // Mili start - AdaptiveRuntime §5.4 (D-08)：checker 周期末尾回调（finally 风格，防异常中断）。
        final FlusherObserver cycleObs = this.observer;
        if (cycleObs != null) {
            try {
                cycleObs.onCycleFinished(System.nanoTime() - currentNanos);
            } catch (Throwable t) {
                logger.warn("FlusherObserver.onCycleFinished failed (ignored)", t);
            }
        }
        // Mili end
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
