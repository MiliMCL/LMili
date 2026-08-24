package fun.bm.mili.lmili.api.threading;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * plugin 隔离的调度执行器 —— 延迟 + 周期任务。
 *
 * <p>底层是 LMili 的 {@code VirtualThreadPool}：scheduler 是单线程，
 * 周期/延迟任务在那里排队，实际执行在虚拟线程上。
 *
 * <p>对应 plugin 现有的 {@code BukkitScheduler.runTaskLater/runTaskTimer}，
 * 但走 LMili 调度路径。
 */
public interface PluginScheduler {

    /** 延迟一次 */
    @NotNull
    ScheduledFuture<?> schedule(@NotNull Runnable task, long delay, @NotNull TimeUnit unit);

    /** 延迟一次带返回值 */
    @NotNull <V> ScheduledFuture<V> schedule(@NotNull Callable<V> task, long delay, @NotNull TimeUnit unit);

    /** 固定延迟周期（上次完成后等 period） */
    @NotNull
    PluginCancellable scheduleWithFixedDelay(@NotNull Runnable task, long period, @NotNull TimeUnit unit);

    /** 固定频率周期（防止重叠；上次未完成则跳过本次） */
    @NotNull
    PluginCancellable scheduleAtFixedRate(@NotNull Runnable task, long period, @NotNull TimeUnit unit);

    /** 关闭 */
    void shutdown();

    /** 标识：是否虚拟线程后端 */
    boolean isVirtual();

    /** 当前活跃 task 数（pending + running） */
    int activeTaskCount();

    /** no-op fallback */
    final class Noop implements PluginScheduler {
        public static final Noop INSTANCE = new Noop();
        private Noop() {}

        @Override public @NotNull ScheduledFuture<?> schedule(@NotNull Runnable task, long delay, @NotNull TimeUnit unit) {
            try { Thread.sleep(unit.toMillis(delay)); task.run(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return new Done();
        }
        @Override public @NotNull <V> ScheduledFuture<V> schedule(@NotNull Callable<V> task, long delay, @NotNull TimeUnit unit) {
            try { Thread.sleep(unit.toMillis(delay)); V r = task.call(); return new Done<>(r); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public @NotNull PluginCancellable scheduleWithFixedDelay(@NotNull Runnable task, long period, @NotNull TimeUnit unit) {
            return PluginCancellable.Noop.INSTANCE;
        }
        @Override public @NotNull PluginCancellable scheduleAtFixedRate(@NotNull Runnable task, long period, @NotNull TimeUnit unit) {
            return PluginCancellable.Noop.INSTANCE;
        }
        @Override public void shutdown() {}
        @Override public boolean isVirtual() { return false; }
        @Override public int activeTaskCount() { return 0; }

        private static class Done<T> implements ScheduledFuture<T> {
            @Override public long getDelay(TimeUnit u) { return 0; }
            @Override public int compareTo(java.util.concurrent.Delayed o) { return 0; }
            @Override public boolean cancel(boolean a) { return false; }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return true; }
            @Override public T get() { return null; }
            @Override public T get(long t, TimeUnit u) { return null; }
        }
    }
}
