package fun.bm.mili.lmili.api.threading;

import org.jetbrains.annotations.NotNull;

/**
 * plugin-facing 区域任务调度器 —— 让 plugin 通过 LMili 把任务发到 region worker。
 *
 * <p>lmili-server 内部实现复用 {@link fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler}。
 *
 * <p>plugin 端用法：
 * <pre>{@code
 * PluginRegionScheduler s = Threading.regionScheduler();
 * s.submit(PluginRegionTask.builder()
 *     .regionId(chunkX * 16 + chunkZ)
 *     .name("my-task")
 *     .runnable(() -> doWork())
 *     .build());
 * s.scheduleDelayed(task, 100, TimeUnit.MILLISECONDS);
 * }</pre>
 */
public interface PluginRegionScheduler {

    /** 立即提交到 region worker */
    @NotNull
    Object submit(@NotNull PluginRegionTask task);

    /** 延迟提交 */
    @NotNull
    Object scheduleDelayed(@NotNull PluginRegionTask task, long delay, @NotNull java.util.concurrent.TimeUnit unit);

    /** 取消一个已提交的 task handle（plugin 端拿到的 Object 是 server-side 返回的） */
    boolean cancel(@NotNull Object handle);

    /** 关闭（plugin 卸载时由 LMili 调用） */
    void shutdown();

    /** no-op fallback */
    final class Noop implements PluginRegionScheduler {
        public static final Noop INSTANCE = new Noop();
        private Noop() {}

        @Override public @NotNull Object submit(@NotNull PluginRegionTask task) {
            task.runnable().run();
            return new Object();
        }
        @Override public @NotNull Object scheduleDelayed(@NotNull PluginRegionTask task, long delay, java.util.concurrent.TimeUnit unit) {
            try { Thread.sleep(unit.toMillis(delay)); task.runnable().run(); } catch (InterruptedException ignored) {}
            return new Object();
        }
        @Override public boolean cancel(@NotNull Object handle) { return false; }
        @Override public void shutdown() {}
    }
}
