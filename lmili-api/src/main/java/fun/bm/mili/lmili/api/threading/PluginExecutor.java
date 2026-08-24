package fun.bm.mili.lmili.api.threading;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * plugin 隔离的执行器 —— plugin 用它提交"异步任务"而不是自己 {@code new Thread()}。
 *
 * <p>实现是 Java 21+ 的虚拟线程池（{@code Executors.newVirtualThreadPerTaskExecutor}），
 * 或按需的平台线程池。
 *
 * <p>线程名：{@code <spec.namePrefix>-Worker-<n>}，例如 {@code MyDB-Worker-3}。
 *
 * <h3>生命周期</h3>
 * <p>plugin 卸载时 LMili 自动关闭；plugin 也可以主动 {@link #shutdown()}。
 *
 * <h3>资源</h3>
 * <ul>
 *   <li>每个 executor 一组虚拟线程（按需创建；空闲时自动释放）。</li>
 *   <li>未捕获异常按 {@link PluginThreadSpec#uncaughtExceptionPolicy()} 处理。</li>
 * </ul>
 */
public interface PluginExecutor {

    /**
     * 提交一个 Runnable，立即返回 Future。
     */
    @NotNull
    Future<?> submit(@NotNull Runnable task);

    /**
     * 提交一个 Callable，立即返回 Future。
     */
    @NotNull <T> Future<T> submit(@NotNull Callable<T> task);

    /**
     * 批量提交。
     */
    @NotNull
    List<Future<?>> submitAll(@NotNull Collection<? extends Runnable> tasks);

    /**
     * 关闭 executor（幂等）。
     */
    void shutdown();

    /** 是否已关闭 */
    boolean isShutdown();

    /**
     * 阻塞直到所有任务完成（或超时）。
     */
    boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException;

    /**
     * 当前活跃线程数（虚拟线程池实现里约等于"正在跑的任务数"）。
     */
    int activeThreadCount();

    /** 当前 executor 是否是虚拟线程池。 */
    boolean isVirtual();

    /** no-op 实现（未装配 LMili 时返回） */
    final class Noop implements PluginExecutor {
        public static final Noop INSTANCE = new Noop();

        private Noop() {}

        @Override public @NotNull Future<?> submit(@NotNull Runnable task) {
            task.run();
            return new CompletableDone(null);
        }
        @Override public @NotNull <T> Future<T> submit(@NotNull Callable<T> task) {
            try { return new CompletableDone<>(task.call()); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public @NotNull List<Future<?>> submitAll(@NotNull Collection<? extends Runnable> tasks) {
            tasks.forEach(Runnable::run);
            return List.of();
        }
        @Override public void shutdown() {}
        @Override public boolean isShutdown() { return false; }
        @Override public boolean awaitTermination(long t, @NotNull TimeUnit u) { return true; }
        @Override public int activeThreadCount() { return 0; }
        @Override public boolean isVirtual() { return false; }

        private static final class CompletableDone<T> implements Future<T> {
            private final T value;
            CompletableDone(T v) { this.value = v; }
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return true; }
            @Override public T get() { return value; }
            @Override public T get(long timeout, @NotNull TimeUnit unit) { return value; }
        }
    }
}
