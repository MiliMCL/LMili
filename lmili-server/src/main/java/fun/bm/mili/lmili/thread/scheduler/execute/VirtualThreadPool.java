package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 虚拟线程池管理 —— 替代旧版单例模式，支持多实例隔离。
 *
 * <h3>设计改进（对比旧版 {@code VirtualThreadScheduler}）</h3>
 * <ul>
 *   <li><b>非单例</b>：通过构建器创建，支持多实例测试和隔离</li>
 *   <li><b>Carrier 感知</b>：自动检测 carrier pool 大小，提供动态调整</li>
 *   <li><b>命名规范</b>：线程名称包含调度器标识，便于诊断</li>
 *   <li><b>优雅关闭</b>：分阶段关闭：拒绝新任务 → 等待进行中任务 → 强制中断</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <pre>
 * Virtual Threads (lightweight)
 *     ↓ mount/unmount
 * Carrier Threads (platform threads, managed by ForkJoinPool)
 *     ↓ bind to
 * CPU Cores
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * VirtualThreadPool pool = VirtualThreadPool.builder("region-scheduler")
 *     .threadNamePrefix("MiliRegion-")
 *     .build();
 *
 * try (StructuredScope scope = pool.createScope("tick", false)) {
 *     scope.fork(() -> tickRegion(region1));
 *     scope.fork(() -> tickRegion(region2));
 * } // 自动等待完成
 *
 * pool.shutdown(5, TimeUnit.SECONDS);
 * }</pre>
 */
public final class VirtualThreadPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 配置 ----
    private final String poolName;
    private final String threadNamePrefix;

    // ---- 执行器 ----
    private final ThreadFactory threadFactory;
    private final ExecutorService executor;
    private final ScheduledExecutorService delayedExecutor;

    // ---- 状态 ----
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final LongAdder totalSubmitted = new LongAdder();
    private final LongAdder totalCompleted = new LongAdder();
    private final LongAdder totalFailed = new LongAdder();
    private final LongAdder totalRejected = new LongAdder();

    // ---- Carrier 信息 ----
    private volatile int carrierParallelism;

    /**
     * 私有构造器 —— 通过 {@link Builder} 创建实例。
     */
    private VirtualThreadPool(@NotNull Builder builder) {
        this.poolName = builder.poolName;
        this.threadNamePrefix = builder.threadNamePrefix;

        // 创建线程工厂
        this.threadFactory = new PrefixedVirtualThreadFactory(threadNamePrefix);
        this.executor = Executors.newThreadPerTaskExecutor(threadFactory);

        // 延迟调度器 —— 单个 scheduled thread 驱动延迟任务
        this.delayedExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, poolName + "-DelayedScheduler");
            t.setDaemon(true);
            return t;
        });

        this.carrierParallelism = ForkJoinPool.commonPool().getParallelism();

        LOGGER.info("[VirtualThreadPool:'{}'] Initialized (carrier parallelism: {})",
                poolName, carrierParallelism);
    }

    // ---- 工厂方法 ----

    /**
     * 创建构建器。
     *
     * @param poolName 池名称（用于日志和诊断）
     */
    public static @NotNull Builder builder(@NotNull String poolName) {
        return new Builder(poolName);
    }

    /**
     * 获取执行器（直接提交 virtual thread 任务）。
     *
     * <p>注意：此方法绕过统计追踪，用于特殊场景。
     * 正常提交请使用 {@link #submit(Runnable)}。
     */
    @NotNull
    public ExecutorService executor() {
        return executor;
    }

    // ---- 任务提交 ----

    /**
     * 提交一个任务执行。
     *
     * <p>任务会被包装以追踪完成状态和异常处理。
     * 此方法立即返回，不阻塞调用线程。
     *
     * @param task 要执行的任务
     * @throws RejectedExecutionException 如果调度器已关闭
     */
    public void submit(@NotNull Runnable task) {
        if (shutdown.get()) {
            totalRejected.increment();
            throw new RejectedExecutionException(
                    "VirtualThreadPool '" + poolName + "' is shutdown");
        }

        totalSubmitted.increment();
        activeTaskCount.incrementAndGet();

        executor.submit(() -> {
            long startNanos = System.nanoTime();
            try {
                task.run();
                totalCompleted.increment();
            } catch (Throwable t) {
                totalFailed.increment();
                handleTaskFailure(t);
            } finally {
                activeTaskCount.decrementAndGet();
            }
        });
    }

    /**
     * 提交带统计追踪的任务（含执行时间记录）。
     *
     * @param task     任务
     * @param metrics  性能指标收集器（可为 null）
     */
    public void submit(@NotNull Runnable task, @Nullable PerformanceRecorder metrics) {
        if (shutdown.get()) {
            totalRejected.increment();
            throw new RejectedExecutionException(
                    "VirtualThreadPool '" + poolName + "' is shutdown");
        }

        totalSubmitted.increment();
        activeTaskCount.incrementAndGet();

        executor.submit(() -> {
            long startNanos = System.nanoTime();
            try {
                task.run();
                totalCompleted.increment();
                if (metrics != null) {
                    metrics.recordCompletion(System.nanoTime() - startNanos);
                }
            } catch (Throwable t) {
                totalFailed.increment();
                handleTaskFailure(t);
            } finally {
                activeTaskCount.decrementAndGet();
            }
        });
    }

    /**
     * 提交延迟执行的任务。
     *
     * @param task  要执行的任务
     * @param delay 延迟时间
     * @param unit  时间单位
     * @return ScheduledFuture 可用于取消任务
     */
    @NotNull
    public ScheduledFuture<?> scheduleDelayed(@NotNull Runnable task, long delay, @NotNull TimeUnit unit) {
        if (delay <= 0) {
            submit(task);
            return new CompletedFuture<>();
        }
        return delayedExecutor.schedule(() -> submit(task), delay, unit);
    }

    /**
     * 提交周期性任务。
     *
     * @param task         任务
     * @param initialDelay 初始延迟
     * @param period       周期
     * @param unit         时间单位
     * @return ScheduledFuture
     */
    @NotNull
    public ScheduledFuture<?> scheduleAtFixedRate(@NotNull Runnable task,
                                                   long initialDelay,
                                                   long period,
                                                   @NotNull TimeUnit unit) {
        return delayedExecutor.scheduleAtFixedRate(() -> submit(task), initialDelay, period, unit);
    }

    // ---- 作用域创建 ----

    /**
     * 创建结构化并发作用域。
     *
     * @param scopeName 作用域名称
     * @param failFast  是否 fail-fast
     */
    @NotNull
    public StructuredScope createScope(@NotNull String scopeName, boolean failFast) {
        return new StructuredScope(scopeName, failFast, this);
    }

    /**
     * 创建结构化并发作用域（带默认超时）。
     *
     * @param scopeName    作用域名称
     * @param failFast     是否 fail-fast
     * @param defaultTimeoutMillis 默认超时（毫秒）
     */
    @NotNull
    public StructuredScope createScope(@NotNull String scopeName, boolean failFast, long defaultTimeoutMillis) {
        return new StructuredScope(scopeName, failFast, defaultTimeoutMillis, this);
    }

    // ---- 状态查询 ----

    public @NotNull String poolName() {
        return poolName;
    }

    public int activeTaskCount() {
        return activeTaskCount.get();
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

    public int carrierParallelism() {
        return carrierParallelism;
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    // ---- 关闭 ----

    /**
     * 优雅关闭线程池。
     *
     * <p>关闭顺序：
     * <ol>
     *   <li>停止接受新任务</li>
     *   <li>等待进行中任务完成（最多 timeout）</li>
     *   <li>强制关闭延迟调度器</li>
     *   <li>如超时则强制中断</li>
     * </ol>
     *
     * @param timeout 等待超时
     * @param unit    时间单位
     * @return true 如果正常关闭
     */
    public boolean shutdown(long timeout, @NotNull TimeUnit unit) {
        if (!shutdown.compareAndSet(false, true)) {
            return false;
        }

        LOGGER.info("[VirtualThreadPool:'{}'] Shutting down... (active tasks: {})",
                poolName, activeTaskCount.get());

        // 不再接受新的延迟任务
        delayedExecutor.shutdown();

        // 关闭 virtual thread executor
        executor.shutdown();

        try {
            if (!executor.awaitTermination(timeout, unit)) {
                LOGGER.warn("[VirtualThreadPool:'{}'] Force shutdown after timeout", poolName);
                executor.shutdownNow();
            }
            if (!delayedExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                delayedExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            delayedExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("[VirtualThreadPool:'{}'] Shutdown complete (completed: {}, failed: {})",
                poolName, totalCompleted.sum(), totalFailed.sum());
        return true;
    }

    // ---- 内部方法 ----

    void handleTaskFailure(@NotNull Throwable t) {
        if (!(t instanceof EntityOrphanedSignal)) {
            LOGGER.error("[VirtualThreadPool:'{}'] Task failed", poolName, t);
        }
    }

    void onTaskCompleted() {
        activeTaskCount.decrementAndGet();
    }

    // ---- 构建器 ----

    /**
     * {@link VirtualThreadPool} 构建器。
     */
    public static final class Builder {
        private final String poolName;
        private String threadNamePrefix = "MiliVT-";

        Builder(@NotNull String poolName) {
            this.poolName = poolName;
        }

        /**
         * 设置线程名称前缀。
         */
        @NotNull
        public Builder threadNamePrefix(@NotNull String prefix) {
            this.threadNamePrefix = prefix;
            return this;
        }

        /**
         * 构建 VirtualThreadPool 实例。
         */
        @NotNull
        public VirtualThreadPool build() {
            return new VirtualThreadPool(this);
        }
    }

    // ---- 内部组件 ----

    /**
     * 带前缀的虚拟线程工厂。
     */
    private static final class PrefixedVirtualThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(0);

        PrefixedVirtualThreadFactory(@NotNull String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread t = Thread.ofVirtual().name(prefix + counter.getAndIncrement()).unstarted(r);
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * 已完成的 Future（用于延迟为 0 的情况）。
     */
    private static final class CompletedFuture<V> implements ScheduledFuture<V> {
        @Override public long getDelay(@NotNull TimeUnit unit) { return 0; }
        @Override public int compareTo(@NotNull java.util.concurrent.Delayed o) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public V get() { return null; }
        @Override public V get(long timeout, @NotNull TimeUnit unit) { return null; }
    }

    /**
     * 实体孤儿信号 —— 用于区分正常任务失败和实体被销毁的正常流程。
     */
    static final class EntityOrphanedSignal extends RuntimeException {
        EntityOrphanedSignal() {
            super("Entity orphaned", null, false, false);
        }
    }

    /**
     * 性能记录器接口 —— 用于外部组件接收执行时间数据。
     */
    @FunctionalInterface
    public interface PerformanceRecorder {
        void recordCompletion(long durationNanos);
    }
}
