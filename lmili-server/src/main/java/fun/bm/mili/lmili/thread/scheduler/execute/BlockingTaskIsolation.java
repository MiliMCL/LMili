package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 阻塞操作隔离 —— 将可能长时间阻塞的操作隔离到专用平台线程池。
 *
 * <h3>设计问题（旧版）</h3>
 * <p>旧版使用 {@code CallerRunsPolicy}，当阻塞池满时由调用线程（可能是 virtual thread）
 * 执行阻塞操作，导致 carrier pinning。
 *
 * <h3>新方案</h3>
 * <ul>
 *   <li><b>专用平台线程池</b>：固定大小，不参与 virtual thread 调度</li>
 *   <li><b>信号量限流</b>：控制同时执行的阻塞任务数，防止资源耗尽</li>
 *   <li><b>Caller 不执行</b>：使用 AbortPolicy，拒绝新任务而非让 virtual thread 执行</li>
 *   <li><b>CompletableFuture 返回</b>：非阻塞等待，virtual thread 可以 unmount</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * BlockingTaskIsolation isolation = BlockingTaskIsolation.builder()
 *     .maxBlockingTasks(8)
 *     .build();
 *
 * // 从 virtual thread 中提交阻塞操作
 * CompletableFuture<String> future = isolation.submitBlocking(() -> {
 *     // 可能阻塞的操作（IO、数据库等）
 *     return readFromFile(path);
 * });
 *
 * // 非阻塞等待结果（virtual thread 可以 unmount）
 * String result = future.get();
 *
 * // 或者使用回调
 * future.thenAccept(result -> processResult(result));
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>本类是线程安全的。多个线程可以同时提交阻塞任务。
 */
public final class BlockingTaskIsolation {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 配置 ----
    private final int maxBlockingTasks;

    // ---- 执行器 ----
    private final ExecutorService platformExecutor;
    private final Semaphore blockingPermits;

    // ---- 状态 ----
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    // ---- 统计 ----
    private final LongAdder totalSubmitted = new LongAdder();
    private final LongAdder totalCompleted = new LongAdder();
    private final LongAdder totalFailed = new LongAdder();
    private final LongAdder totalRejected = new LongAdder();
    private final AtomicInteger activeBlockingTasks = new AtomicInteger(0);

    /**
     * 私有构造器 —— 通过 {@link Builder} 创建。
     */
    private BlockingTaskIsolation(@NotNull Builder builder) {
        this.maxBlockingTasks = builder.maxBlockingTasks;

        // 信号量控制并发阻塞任务数
        this.blockingPermits = new Semaphore(maxBlockingTasks, true); // fair = true

        // 专用平台线程池
        this.platformExecutor = new ThreadPoolExecutor(
                maxBlockingTasks,           // core = max（固定大小）
                maxBlockingTasks,           // max = core（无弹性）
                0L, TimeUnit.MILLISECONDS,   // 不需要 keep-alive（固定大小）
                new LinkedBlockingQueue<>(maxBlockingTasks * 2), // 有界队列
                r -> {
                    Thread t = new Thread(r, "Mili-Blocking-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy() // 拒绝而非 caller-runs
        );

        LOGGER.info("[BlockingTaskIsolation] Initialized (max blocking tasks: {})", maxBlockingTasks);
    }

    // ---- 工厂方法 ----

    /**
     * 创建构建器。
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    // ---- 任务提交 ----

    /**
     * 提交一个阻塞操作。
     *
     * <p>如果当前线程是 virtual thread，操作会被提交到专用平台线程池执行，
     * virtual thread 可以 unmount 等待结果。
     *
     * <p>如果当前线程已经是平台线程，直接执行（避免不必要的调度开销）。
     *
     * @param task 阻塞操作
     * @return CompletableFuture 用于获取结果
     * @throws RejectedExecutionException 如果调度器已关闭或阻塞池满
     */
    public @NotNull <T> CompletableFuture<T> submitBlocking(@NotNull Callable<T> task) {
        if (shutdown.get()) {
            totalRejected.increment();
            throw new RejectedExecutionException("BlockingTaskIsolation is shutdown");
        }

        totalSubmitted.increment();

        // 如果已在平台线程上，直接执行
        if (!Thread.currentThread().isVirtual()) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    T result = task.call();
                    totalCompleted.increment();
                    return result;
                } catch (Exception e) {
                    totalFailed.increment();
                    throw new CompletionException(e);
                }
            });
        }

        // Virtual thread 路径：提交到专用平台线程池
        return CompletableFuture.supplyAsync(() -> {
            activeBlockingTasks.incrementAndGet();
            try {
                blockingPermits.acquire();
                try {
                    T result = task.call();
                    totalCompleted.increment();
                    return result;
                } finally {
                    blockingPermits.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                totalFailed.increment();
                throw new CompletionException(e);
            } catch (Exception e) {
                totalFailed.increment();
                throw new CompletionException(e);
            } finally {
                activeBlockingTasks.decrementAndGet();
            }
        }, platformExecutor);
    }

    /**
     * 提交一个阻塞 Runnable。
     *
     * @param task 阻塞操作
     * @return CompletableFuture 用于追踪完成
     */
    public @NotNull CompletableFuture<Void> submitBlocking(@NotNull Runnable task) {
        return submitBlocking(() -> {
            task.run();
            return null;
        });
    }

    /**
     * 提交一个带超时的阻塞操作。
     *
     * <p>如果操作在指定时间内未完成，会抛出 TimeoutException。
     *
     * @param task    阻塞操作
     * @param timeout 超时时间
     * @param unit    时间单位
     * @return CompletableFuture
     */
    public @NotNull <T> CompletableFuture<T> submitBlockingWithTimeout(@NotNull Callable<T> task,
                                                                         long timeout,
                                                                         @NotNull TimeUnit unit) {
        CompletableFuture<T> future = submitBlocking(task);
        return future.orTimeout(timeout, unit);
    }

    /**
     * 尝试提交一个阻塞操作（非阻塞检查）。
     *
     * <p>如果阻塞池已满，立即返回失败的 CompletableFuture 而非抛出异常。
     *
     * @param task 阻塞操作
     * @return CompletableFuture，如果池已满则返回已失败的 future
     */
    public @NotNull <T> CompletableFuture<T> trySubmitBlocking(@NotNull Callable<T> task) {
        if (shutdown.get()) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RejectedExecutionException("Shutdown"));
            return failed;
        }

        if (!blockingPermits.tryAcquire()) {
            totalRejected.increment();
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RejectedExecutionException(
                    "Blocking pool full (max: " + maxBlockingTasks + ")"));
            return failed;
        }

        totalSubmitted.increment();
        return CompletableFuture.supplyAsync(() -> {
            activeBlockingTasks.incrementAndGet();
            try {
                T result = task.call();
                totalCompleted.increment();
                return result;
            } catch (Exception e) {
                totalFailed.increment();
                throw new CompletionException(e);
            } finally {
                activeBlockingTasks.decrementAndGet();
                blockingPermits.release();
            }
        }, platformExecutor);
    }

    // ---- 状态查询 ----

    public int maxBlockingTasks() {
        return maxBlockingTasks;
    }

    public int activeBlockingTasks() {
        return activeBlockingTasks.get();
    }

    public int availablePermits() {
        return blockingPermits.availablePermits();
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    public long totalSubmitted() {
        return totalSubmitted.sum();
    }

    public long totalCompleted() {
        return totalCompleted.sum();
    }

    public long totalFailed() {
        return totalFailed.sum();
    }

    public long totalRejected() {
        return totalRejected.sum();
    }

    // ---- 关闭 ----

    /**
     * 优雅关闭阻塞任务隔离器。
     *
     * @param timeout 等待超时
     * @param unit    时间单位
     * @return true 如果正常关闭
     */
    public boolean shutdown(long timeout, @NotNull TimeUnit unit) {
        if (!shutdown.compareAndSet(false, true)) return false;

        LOGGER.info("[BlockingTaskIsolation] Shutting down... (active: {})",
                activeBlockingTasks.get());

        platformExecutor.shutdown();
        try {
            if (!platformExecutor.awaitTermination(timeout, unit)) {
                platformExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            platformExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("[BlockingTaskIsolation] Shutdown complete");
        return true;
    }

    // ---- 构建器 ----

    /**
     * {@link BlockingTaskIsolation} 构建器。
     */
    public static final class Builder {
        private int maxBlockingTasks = Math.max(2, Runtime.getRuntime().availableProcessors());

        Builder() {}

        /**
         * 设置最大并发阻塞任务数。
         *
         * @param max 最大并发数（默认 CPU 核心数）
         */
        @NotNull
        public Builder maxBlockingTasks(int max) {
            this.maxBlockingTasks = Math.max(1, max);
            return this;
        }

        /**
         * 构建 BlockingTaskIsolation 实例。
         */
        @NotNull
        public BlockingTaskIsolation build() {
            return new BlockingTaskIsolation(this);
        }
    }
}
