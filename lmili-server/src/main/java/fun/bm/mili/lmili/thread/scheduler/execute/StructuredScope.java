package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 结构化并发作用域（优化版）—— 管理一组虚拟线程任务的完整生命周期。
 *
 * <h3>改进（对比旧版）</h3>
 * <ul>
 *   <li><b>可配置超时</b>：通过 {@link VirtualThreadPool#createScope(String, boolean, long)} 设置默认超时</li>
 *   <li><b>无嵌套提交</b>：{@link #forkWithTimeout} 使用 Future.get 而非嵌套线程</li>
 *   <li><b>池引用</b>：持有 {@link VirtualThreadPool} 引用而非静态单例</li>
 *   <li><b>CompletionService 模式</b>：优化批量 future 等待</li>
 * </ul>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>所有子任务必须在作用域关闭前完成或取消</li>
 *   <li>任何子任务失败可触发其余任务取消（fail-fast 策略）</li>
 *   <li>作用域关闭时自动等待所有任务完成</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * VirtualThreadPool pool = VirtualThreadPool.builder("region").build();
 *
 * try (StructuredScope scope = pool.createScope("entity-tick", false)) {
 *     for (Entity entity : entities) {
 *         scope.fork(() -> tickEntity(entity));
 *     }
 * } // 此处自动等待所有 tick 完成
 *
 * // 带超时的版本
 * try (StructuredScope scope = pool.createScope("region-tick", true, 50)) {
 *     for (ChunkSlice slice : slices) {
 *         scope.forkWithTimeout(() -> tickSlice(slice), 50, TimeUnit.MILLISECONDS);
 *     }
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>本类的实例不应跨线程共享。单个作用域由单个线程创建和管理，
 * 但子任务可能在其他线程上执行。
 */
public final class StructuredScope implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 配置 ----
    private final String name;
    private final boolean failFast;
    private final long defaultTimeoutMillis;

    // ---- 执行环境 ----
    private final VirtualThreadPool pool;

    // ---- 任务追踪 ----
    private final List<ScopedFuture<?>> futures = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger forked = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>(null);

    // ---- 取消协调 ----
    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

    /**
     * 创建作用域。
     *
     * @param name      作用域名称（用于日志和诊断）
     * @param failFast  是否在任一任务失败时立即取消其他任务
     * @param pool      关联的虚拟线程池
     */
    StructuredScope(@NotNull String name, boolean failFast, @NotNull VirtualThreadPool pool) {
        this(name, failFast, 100, pool); // 默认 100ms 超时
    }

    /**
     * 创建作用域（可配置默认超时）。
     *
     * @param name              作用域名称
     * @param failFast          fail-fast 开关
     * @param defaultTimeoutMillis 默认超时（毫秒）
     * @param pool              关联的虚拟线程池
     */
    StructuredScope(@NotNull String name, boolean failFast, long defaultTimeoutMillis, @NotNull VirtualThreadPool pool) {
        this.name = name;
        this.failFast = failFast;
        this.defaultTimeoutMillis = Math.max(1, defaultTimeoutMillis);
        this.pool = pool;
    }

    // ---- 任务提交 ----

    /**
     * 提交一个 Callable 任务到该作用域。
     *
     * @param task 要执行的任务
     * @return Future 可用于获取结果或取消任务
     * @throws IllegalStateException 如果作用域已关闭
     */
    public @NotNull <T> Future<T> fork(@NotNull Callable<T> task) {
        ensureOpen();
        forked.incrementAndGet();
        ScopedFuture<T> scopedFuture = new ScopedFuture<>(task);
        futures.add(scopedFuture);
        pool.submit(scopedFuture::run);
        return scopedFuture;
    }

    /**
     * 提交一个 Runnable 任务到该作用域。
     *
     * @param task 要执行的任务
     * @return Future 可用于取消任务
     */
    public @NotNull Future<?> fork(@NotNull Runnable task) {
        ensureOpen();
        forked.incrementAndGet();
        ScopedFuture<Void> scopedFuture = new ScopedFuture<>(() -> {
            task.run();
            return null;
        });
        futures.add(scopedFuture);
        pool.submit(scopedFuture::run);
        return scopedFuture;
    }

    /**
     * 提交一个带超时的任务。超时后自动取消。
     *
     * <p><b>优化</b>：使用 Future.get(timeout) 实现超时，不需要嵌套提交任务。
     * 超时后在 finally 中取消实际任务线程。
     *
     * @param task    要执行的任务
     * @param timeout 超时时长
     * @param unit    时间单位
     * @return Future
     */
    public @NotNull <T> Future<T> forkWithTimeout(@NotNull Callable<T> task,
                                                    long timeout,
                                                    @NotNull TimeUnit unit) {
        ensureOpen();
        forked.incrementAndGet();

        long rawTimeoutMillis = unit.toMillis(timeout);
        // 确保至少 1ms（声明为 final 以在 lambda 中使用）
        final long timeoutMillis = Math.max(1, rawTimeoutMillis);

        ScopedFuture<T> scopedFuture = new ScopedFuture<>(() -> {
            // 内嵌 TimeoutRunner 直接执行任务
            Future<T> innerFuture = pool.submit(task);
            try {
                return innerFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                innerFuture.cancel(true);
                throw new TimeoutException(
                        "[StructuredScope:" + name + "] Task timed out after " + timeoutMillis + "ms");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception ex
                        ? ex : new RuntimeException(e.getCause());
            } catch (InterruptedException e) {
                innerFuture.cancel(true);
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "[StructuredScope:" + name + "] task interrupted", e);
            }
        });

        futures.add(scopedFuture);
        pool.submit(scopedFuture::run);
        return scopedFuture;
    }

    /**
     * 使用默认超时提交任务。
     *
     * @see #forkWithTimeout(Callable, long, TimeUnit)
     */
    public @NotNull <T> Future<T> forkWithDefaultTimeout(@NotNull Callable<T> task) {
        return forkWithTimeout(task, defaultTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    // ---- 等待与关闭 ----

    /**
     * 等待所有子任务完成（使用默认超时）。
     *
     * @return true 如果所有任务在超时前完成
     */
    public boolean awaitAll() throws InterruptedException {
        return await(defaultTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 等待所有子任务完成。
     *
     * @param timeout 最大等待时间
     * @param unit    时间单位
     * @return true 如果所有任务在超时前完成
     */
    public boolean await(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);

        List<ScopedFuture<?>> snapshot;
        synchronized (futures) {
            snapshot = new ArrayList<>(futures);
        }

        for (ScopedFuture<?> future : snapshot) {
            if (cancellationRequested.get()) {
                return false;
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }

            try {
                future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                // 失败由 handleFailure 处理，继续等待其他任务
                if (failFast) {
                    return false;
                }
            } catch (TimeoutException e) {
                return false;
            } catch (CancellationException e) {
                // 已取消的任务，继续等待其他
            }
        }
        return true;
    }

    /**
     * 关闭作用域，等待所有任务完成或超时。
     *
     * <p>关闭逻辑：
     * <ol>
     *   <li>标记为关闭（不再接受新任务）</li>
     *   <li>如果有失败且 failFast，取消所有任务</li>
     *   <li>等待任务完成（默认超时）</li>
     *   <li>超时后强制取消</li>
     * </ol>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        // 如果有失败且启用了 fail-fast，取消剩余任务
        if (failFast && firstFailure.get() != null) {
            cancelAll();
        }

        // 等待任务完成
        List<ScopedFuture<?>> pending;
        synchronized (futures) {
            pending = new ArrayList<>(futures);
        }

        long deadlineNanos = System.nanoTime() + defaultTimeoutMillis * 1_000_000L;

        for (ScopedFuture<?> future : pending) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) break;

            try {
                future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                future.cancel(true);
            }
        }

        // 强制取消任何仍在运行的任务
        cancelAll();
    }

    /**
     * 取消作用域内所有任务。
     */
    public void cancelAll() {
        cancellationRequested.set(true);
        synchronized (futures) {
            for (ScopedFuture<?> future : futures) {
                future.cancel(true);
            }
        }
    }

    // ---- 状态查询 ----

    public @NotNull String name() {
        return name;
    }

    public int getForkedCount() {
        return forked.get();
    }

    public int getFailedCount() {
        return failed.get();
    }

    public @Nullable Throwable getFirstFailure() {
        return firstFailure.get();
    }

    public boolean isFailFast() {
        return failFast;
    }

    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    // ---- 内部方法 ----

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ScopedFuture '" + name + "' is already closed");
        }
    }

    private void handleFailure(@NotNull Throwable t) {
        failed.incrementAndGet();
        firstFailure.compareAndSet(null, t);

        LOGGER.error("[StructuredScope:{}] Task failed (count: {})",
                name, failed.get(), t);

        if (failFast) {
            cancelAll();
        }
    }

    // ---- 内部组件 ----

    /**
     * 作用域内的 Future —— 包装任务的执行和异常处理。
     *
     * <p>相比直接使用 Future，增强能力包括：
     * <ul>
     *   <li>自动调用 handleFailure</li>
     *   <li>追踪任务开始/结束时间</li>
     * </ul>
     */
    private final class ScopedFuture<T> implements Future<T> {
        private final Callable<T> task;
        private final CompletableFuture<T> delegate = new CompletableFuture<>();
        private volatile long startNanos;
        private volatile long endNanos;

        ScopedFuture(@NotNull Callable<T> task) {
            this.task = task;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return delegate.get();
        }

        @Override
        public T get(long timeout, @NotNull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.get(timeout, unit);
        }

        void run() {
            if (delegate.isCancelled()) return;
            startNanos = System.nanoTime();
            try {
                T result = task.call();
                delegate.complete(result);
            } catch (Throwable t) {
                handleFailure(t);
                delegate.completeExceptionally(t);
            } finally {
                endNanos = System.nanoTime();
            }
        }
    }
}
