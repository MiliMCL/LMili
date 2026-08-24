package fun.bm.mili.lmili.api.threading;

import org.jetbrains.annotations.NotNull;

/**
 * plugin-facing 区域任务 —— 让 plugin 提交"在某个 region 上跑"的任务到 LMili 调度器。
 *
 * <p>与 lmili-server 的 {@code RegionTask} 不同，{@link PluginRegionTask} 是
 * lmili-api 的稳定接口；server-side 在用户提交时把它包装成内部 {@code RegionTask}。
 *
 * <h3>使用</h3>
 * <pre>{@code
 * Threading.regionScheduler().submit(PluginRegionTask.builder()
 *     .regionId(42L)
 *     .name("my-task")
 *     .runnable(() -> doWork())
 *     .build());
 * }</pre>
 */
public interface PluginRegionTask {

    /** 目标 region id */
    long regionId();

    /** 任务显示名 */
    @NotNull String name();

    /** 任务体（运行在 region worker 上） */
    @NotNull Runnable runnable();

    /** 任务级超时（ms）；0 = 不超时 */
    long timeoutMillis();

    /** 是否为 blocking 类型（需要 BlockingWorker 接管）；默认 false */
    boolean isBlocking();

    /** Builder 工厂 */
    static @NotNull Builder builder() {
        return new Builder();
    }

    final class Builder {
        private long regionId = -1L;
        private String name = "plugin-task";
        private Runnable runnable;
        private long timeoutMillis = 0L;
        private boolean blocking = false;

        public Builder regionId(long id) { this.regionId = id; return this; }
        public Builder name(@NotNull String n) { this.name = n; return this; }
        public Builder runnable(@NotNull Runnable r) { this.runnable = r; return this; }
        public Builder timeoutMillis(long ms) { this.timeoutMillis = ms; return this; }
        public Builder blocking(boolean b) { this.blocking = b; return this; }

        public @NotNull PluginRegionTask build() {
            if (runnable == null) throw new IllegalStateException("runnable is required");
            if (regionId < 0) throw new IllegalStateException("regionId must be >= 0");
            final String n = name;
            final Runnable r = runnable;
            final long id = regionId;
            final long tm = timeoutMillis;
            final boolean b = blocking;
            return new PluginRegionTask() {
                @Override public long regionId() { return id; }
                @Override public @NotNull String name() { return n; }
                @Override public @NotNull Runnable runnable() { return r; }
                @Override public long timeoutMillis() { return tm; }
                @Override public boolean isBlocking() { return b; }
            };
        }
    }
}
