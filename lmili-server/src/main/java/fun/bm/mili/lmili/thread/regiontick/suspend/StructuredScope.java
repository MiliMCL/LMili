package fun.bm.mili.lmili.thread.regiontick.suspend;

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

/**
 * 结构化并发作用域 —— 管理一组虚拟线程任务的完整生命周期。
 *
 * <p>核心原则（源自结构化并发）：
 * <ul>
 *   <li>所有子任务必须在作用域关闭前完成或取消</li>
 *   <li>任何子任务失败可触发其余任务取消（fail-fast 策略）</li>
 *   <li>作用域关闭时自动等待所有任务完成</li>
 * </ul>
 *
 * <p>典型用法：
 * <pre>{@code
 * try (StructuredScope scope = new StructuredScope("entity-tick", false)) {
 *     for (Entity entity : entities) {
 *         scope.fork(() -> tickEntity(entity));
 *     }
 * } // 此处自动等待所有 tick 完成
 * }</pre>
 *
 * <p>与实体生命周期绑定的用法：
 * <pre>{@code
 * try (StructuredScope scope = new StructuredScope("region-tick", true)) {
 *     for (ChunkSlice slice : slices) {
 *         scope.forkWithTimeout(() -> tickSlice(slice), 50, TimeUnit.MILLISECONDS);
 *     }
 * }</pre>
 */
public final class StructuredScope implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String name;
    private final boolean failFast;
    private final ExecutorService executor;
    private final List<Future<?>> futures = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger forked = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private volatile Throwable firstFailure;

    /**
     * @param name     作用域名称（用于日志和统计）
     * @param failFast 是否在任一任务失败时立即取消其他任务
     */
    public StructuredScope(final @NotNull String name, final boolean failFast) {
        this(name, failFast, VirtualThreadScheduler.getInstance().virtualThreadExecutor);
    }

    /**
     * @param name     作用域名称
     * @param failFast 是否 fail-fast
     * @param executor 使用的 ExecutorService
     */
    public StructuredScope(final @NotNull String name, final boolean failFast,
                            final @NotNull ExecutorService executor) {
        this.name = name;
        this.failFast = failFast;
        this.executor = executor;
    }

    /**
     * 提交一个Callable任务到该作用域。
     *
     * @param task 要执行的任务
     * @return Future 可用于获取结果或取消任务
     * @throws IllegalStateException 如果作用域已关闭
     */
    public @NotNull Future<?> fork(@NotNull final Callable<?> task) {
        ensureOpen();
        forked.incrementAndGet();
        Future<?> future = executor.submit(() -> {
            try {
                task.call();
            } catch (Throwable t) {
                handleFailure(t);
                // 包装受检异常 —— Runnable 不允许抛出 checked exception
                throw new RuntimeException("[StructuredScope:" + name + "] task failed", t);
            }
        });
        futures.add(future);
        return future;
    }

    /**
     * 提交一个Runnable任务到该作用域。
     *
     * @param task 要执行的任务
     * @return Future 可用于取消任务
     */
    public @NotNull Future<?> fork(@NotNull final Runnable task) {
        ensureOpen();
        forked.incrementAndGet();
        Future<?> future = executor.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                handleFailure(t);
                throw t;
            }
        });
        futures.add(future);
        return future;
    }

    /**
     * 提交一个带超时的任务。超时后自动取消。
     *
     * @param task     要执行的任务
     * @param timeout  超时时长
     * @param unit     时间单位
     * @return Future
     */
    public @NotNull Future<?> forkWithTimeout(@NotNull final Callable<?> task,
                                               final long timeout,
                                               @NotNull final TimeUnit unit) {
        ensureOpen();
        forked.incrementAndGet();
        Future<?> future = executor.submit(() -> {
            Future<?> inner = executor.submit(task);
            try {
                return inner.get(timeout, unit);
            } catch (TimeoutException e) {
                inner.cancel(true);
                throw new RuntimeException("[StructuredScope:" + name + "] Task timed out after " + timeout + " " + unit, e);
            } catch (ExecutionException e) {
                handleFailure(e.getCause());
                throw new RuntimeException("[StructuredScope:" + name + "] task failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("[StructuredScope:" + name + "] task interrupted", e);
            }
        });
        futures.add(future);
        return future;
    }

    /**
     * 等待所有子任务完成（阻塞当前线程）。
     *
     * @param timeout  最大等待时间
     * @param unit     时间单位
     * @return true 如果所有任务在超时前完成
     */
    public boolean await(final long timeout, @NotNull final TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (futures) {
            for (Future<?> future : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    future.get(remaining, TimeUnit.NANOSECONDS);
                } catch (ExecutionException e) {
                    // 失败由 handleFailure 处理，继续等待其他任务
                } catch (TimeoutException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 关闭作用域，等待所有任务完成。如果在 failFast 模式下有任务失败，取消其他任务。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        // 如果有失败且启用了 fail-fast，取消剩余任务
        if (failFast && firstFailure != null) {
            cancelAll();
        }

        // 等待所有任务完成（活跃任务有较短的超时，避免无限阻塞）
        synchronized (futures) {
            for (Future<?> future : futures) {
                if (!future.isDone()) {
                    try {
                        future.get(100, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        future.cancel(true);
                    }
                }
            }
        }
    }

    /**
     * 取消作用域内所有任务。
     */
    public void cancelAll() {
        synchronized (futures) {
            for (Future<?> future : futures) {
                future.cancel(true);
            }
        }
    }

    /**
     * 获取已提交的任务数量。
     */
    public int getForkedCount() {
        return forked.get();
    }

    /**
     * 获取失败的任务数量。
     */
    public int getFailedCount() {
        return failed.get();
    }

    /**
     * 获取第一个失败异常的。
     */
    public @Nullable Throwable getFirstFailure() {
        return firstFailure;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("StructuredScope '" + name + "' is already closed");
        }
    }

    private void handleFailure(@NotNull final Throwable t) {
        failed.incrementAndGet();
        if (firstFailure == null) {
            firstFailure = t;
        }
        LOGGER.error("[StructuredScope:{}] Task failed", name, t);
        if (failFast) {
            cancelAll();
        }
    }
}
