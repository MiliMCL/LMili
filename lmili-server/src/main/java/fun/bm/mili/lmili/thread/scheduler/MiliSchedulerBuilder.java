package fun.bm.mili.lmili.thread.scheduler;

import fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler;
import org.jetbrains.annotations.NotNull;

/**
 * {@link MiliScheduler} 的构建器 —— 以 Builder 模式创建和配置新调度系统。
 *
 * <p>使用示例：
 * <pre>{@code
 * MiliScheduler scheduler = MiliSchedulerBuilder.create("region-scheduler")
 *     .threadNamePrefix("MiliRegion-")
 *     .carrierThreads(Runtime.getRuntime().availableProcessors())
 *     .maxBlockingTasks(8)
 *     .build();
 *
 * // 提交任务
 * TaskHandle handle = scheduler.submit(RegionTask.builder(regionId)
 *     .task(() -> tickChunk(chunk))
 *     .build());
 *
 * // 关闭
 * scheduler.shutdown(5, TimeUnit.SECONDS);
 * }</pre>
 *
 * <h3>默认配置</h3>
 * <ul>
 *   <li>poolName: "MiliScheduler"</li>
 *   <li>threadNamePrefix: "MiliVT-"</li>
 *   <li>carrierThreads: Runtime.getRuntime().availableProcessors()</li>
 *   <li>maxBlockingTasks: carrierThreads</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>Builder 不是线程安全的，应在单个线程上配置和构建。
 * 构建后的 {@link MiliSchedulerImpl} 是线程安全的。
 */
public final class MiliSchedulerBuilder {

    // ---- 配置字段 ----
    String poolName = "MiliScheduler";
    String threadNamePrefix = "MiliVT-";
    int carrierThreads = Runtime.getRuntime().availableProcessors();
    int maxBlockingTasks = Runtime.getRuntime().availableProcessors();

    /**
     * 私有构造器 —— 使用 {@link #create(String)} 工厂方法。
     */
    private MiliSchedulerBuilder() {
    }

    /**
     * 创建构建器。
     *
     * @return 新的构建器实例
     */
    @NotNull
    public static MiliSchedulerBuilder create() {
        return new MiliSchedulerBuilder();
    }

    /**
     * 创建构建器（指定池名称）。
     *
     * @param poolName 调度器池名称（用于日志和诊断）
     * @return 新的构建器实例
     */
    @NotNull
    public static MiliSchedulerBuilder create(@NotNull String poolName) {
        MiliSchedulerBuilder builder = new MiliSchedulerBuilder();
        builder.poolName = poolName;
        return builder;
    }

    /**
     * 设置线程名称前缀。
     *
     * @param prefix 线程名称前缀（如 "MiliRegion-"）
     * @return this（链式调用）
     */
    @NotNull
    public MiliSchedulerBuilder threadNamePrefix(@NotNull String prefix) {
        this.threadNamePrefix = prefix;
        return this;
    }

    /**
     * 设置 carrier 线程数（影响 work-stealing worker 数）。
     *
     * <p>建议设置为 CPU 核心数。过多的 carrier 线程会增加上下文切换开销，
     * 过少则无法充分利用 CPU。
     *
     * @param threads carrier 线程数（最小 1）
     * @return this（链式调用）
     */
    @NotNull
    public MiliSchedulerBuilder carrierThreads(int threads) {
        this.carrierThreads = Math.max(1, threads);
        return this;
    }

    /**
     * 设置最大并发阻塞任务数。
     *
     * <p>阻塞任务会被隔离到专用平台线程池执行，此参数控制池大小。
     * 过多的阻塞任务会消耗大量平台线程，过少则可能导致任务排队。
     *
     * @param maxBlocking 最大并发阻塞任务数（最小 1）
     * @return this（链式调用）
     */
    @NotNull
    public MiliSchedulerBuilder maxBlockingTasks(int maxBlocking) {
        this.maxBlockingTasks = Math.max(1, maxBlocking);
        return this;
    }

    /**
     * 构建 {@link MiliScheduler} 实例。
     *
     * <p>此方法会初始化所有内部组件（线程池、协调器等），
     * 但不会启动任何 worker 线程（worker 线程按需创建）。
     *
     * @return 新的 MiliScheduler 实例
     */
    @NotNull
    public MiliScheduler build() {
        return new MiliSchedulerImpl(this);
    }
}
