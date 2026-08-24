package fun.bm.mili.lmili.api.threading;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 线程管理顶层入口 —— 让 plugin 通过 LMili 统一管理自己的线程（不再 {@code new Thread}）。
 *
 * <h3>为什么需要这个 API（用户痛点）</h3>
 * <ul>
 *   <li>plugin 自己 {@code new Thread()} / {@code Executors.newCachedThreadPool()}
 *       会<b>绕过</b> LMili 的调度器、配额、监控、Region 线程安全模型。</li>
 *   <li>spark 等性能分析插件自己起线程池，导致 LMili 看不到它的实际工作。</li>
 *   <li>当 plugin 线程抛未捕获异常时，LMili 无法集中记录或关闭。</li>
 *   <li>plugin 想跑"每 tick 一次"或"延迟 X 秒"的任务，只能用 BukkitScheduler，
 *       而那又不走 LMili 调度。</li>
 * </ul>
 *
 * <h3>通过本 API，plugin 可以</h3>
 * <ul>
 *   <li>{@link #executor(PluginThreadSpec)} —— 拿一个按 plugin 隔离的虚拟线程执行器</li>
 *   <li>{@link #scheduler(PluginThreadSpec)} —— 拿一个按 plugin 隔离的调度执行器（延迟/周期）</li>
 *   <li>{@link #scope()} —— 拿到结构化并发 scope（{@link StructuredConcurrency}）</li>
 *   <li>{@link #miliScheduler()} —— 拿到当前 server 的 {@code MiliScheduler}（如果开放）</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>所有线程都是 <b>daemon</b> —— 不会阻止 JVM 关闭。</li>
 *   <li>线程名按 {@code PluginThreadSpec.namePrefix()} 规范化 —— spark/profile 工具可识别。</li>
 *   <li>未捕获异常自动记录到 LMili（§11 / §15 metrics）。</li>
 *   <li>plugin 卸载时，LMili 自动关闭该 plugin 拥有的所有 executor。</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 1. 异步任务
 * Threading.executor(spec("mydb")).submit(() -> {
 *     // 虚拟线程，不会阻塞 server tick
 *     database.query("SELECT ...");
 * });
 *
 * // 2. 周期任务
 * var handle = Threading.scheduler(spec("mybot")).scheduleAtFixedRate(
 *     () -> bot.tick(), 50, 50, TimeUnit.MILLISECONDS);
 *
 * // 3. 结构化并发
 * try (var scope = Threading.scope()) {
 *     var a = scope.fork(() -> loadConfig());
 *     var b = scope.fork(() -> loadLocale());
 *     scope.join(); // 等待两者都完成
 * }
 *
 * // 4. 区域任务（如果 plugin 有 region 上下文）
 * Threading.miliScheduler().submit(regionTask);
 * }</pre>
 *
 * <p><b>未初始化时</b>：返回 {@code null} 或 no-op 实现。plugin 应使用
 * {@link #isAvailable()} 检查并降级到传统方式。
 *
 * @see PluginThreadSpec
 * @see PluginExecutor
 * @see PluginScheduler
 */
public final class Threading {

    private static volatile ThreadingBackend BACKEND;

    private Threading() {}

    /**
     * 拿到 plugin 隔离的执行器（虚拟线程池，Java 21+）。
     *
     * <p>plugin 卸载时自动关闭。
     *
     * @param spec 线程规范（plugin id + 命名 + 配额）
     * @return 永远不会 null；返回 noop 占位当 LMili 未初始化
     */
    @NotNull
    public static PluginExecutor executor(@NotNull PluginThreadSpec spec) {
        ThreadingBackend b = BACKEND;
        if (b == null) return PluginExecutor.Noop.INSTANCE;
        return b.executor(spec);
    }

    /**
     * 拿到 plugin 隔离的调度执行器（延迟 + 周期任务）。
     *
     * @param spec 线程规范
     * @return 永远不会 null
     */
    @NotNull
    public static PluginScheduler scheduler(@NotNull PluginThreadSpec spec) {
        ThreadingBackend b = BACKEND;
        if (b == null) return PluginScheduler.Noop.INSTANCE;
        return b.scheduler(spec);
    }

    /**
     * 拿到结构化并发 scope 工厂。
     *
     * <p>虚拟线程结构化并发（JDK 21 StructuredTaskScope）—— plugin fork 多个子任务后
     * 用 try-with-resources 自动等所有完成或第一个失败。
     */
    @NotNull
    public static StructuredConcurrency scope() {
        ThreadingBackend b = BACKEND;
        if (b == null) return StructuredConcurrency.Noop.INSTANCE;
        return b.scope();
    }

    /**
     * 拿到当前 server 的 {@code MiliScheduler}（如果有）。
     *
     * <p>这是 LMili 的内部 scheduler（与 BukkitScheduler 不同）。plugin 拿到的对象是
     * 一个受限的代理，只暴露"对 plugin 安全"的子集（submit/scheduleDelayed/forEntity）。
     * 内部 API（如 shutdown）不暴露。
     *
     * @return server 端 scheduler；未初始化返回 null
     */
    public static Object miliScheduler() {
        ThreadingBackend b = BACKEND;
        return b == null ? null : b.miliScheduler();
    }

    /**
     * plugin-facing 区域任务调度器（稳定 API；与 {@link #miliScheduler()} 不同，
     * 这个是 plugin-facing 专用 facade）。
     *
     * @return plugin 区域任务调度器；未初始化返回 noop
     */
    @NotNull
    public static PluginRegionScheduler regionScheduler() {
        ThreadingBackend b = BACKEND;
        if (b == null) return PluginRegionScheduler.Noop.INSTANCE;
        return b.regionScheduler();
    }

    /**
     * LMili version（来自 server-side backend）。
     */
    @NotNull
    public static String version() {
        ThreadingBackend b = BACKEND;
        return b == null ? "noop" : b.version();
    }

    /**
     * plugin 端便捷访问 {@link fun.bm.mili.lmili.api.observability.SchedulerMetrics}。
     *
     * <p>反映 LMili §15 全部指标（P50/P95/P99/max MSPT、worker 利用率、阻塞、deferred、chunk wait…）。
     * Spark profiler / VisualVM 通过 JMX {@code fun.bm.mili:type=SchedulerMetrics} 也能读到同一份。
     *
     * @return SchedulerMetrics；未安装返回 null
     */
    @Nullable
    public static <T> T snapshotMetrics() {
        ThreadingBackend b = BACKEND;
        if (b == null) return null;
        @SuppressWarnings("unchecked")
        T t = (T) b.snapshotMetrics();
        return t;
    }

    /**
     * plugin 端便捷访问 {@link fun.bm.mili.lmili.api.observability.LongTailEvent}。
     *
     * <p>长尾事件流：plugin 可以注册 listener 实时监听，也可以查最近 N 条。
     */
    @Nullable
    public static <T> T longTailEvents() {
        ThreadingBackend b = BACKEND;
        if (b == null) return null;
        @SuppressWarnings("unchecked")
        T t = (T) b.longTailEvents();
        return t;
    }

    /** LMili 是否已就绪 */
    public static boolean isAvailable() {
        return BACKEND != null;
    }

    /** server-side 装配（幂等） */
    public static void installBackend(@NotNull ThreadingBackend backend) {
        BACKEND = backend;
    }

    /** 测试/关闭时清空 */
    public static void resetBackend() {
        ThreadingBackend b = BACKEND;
        BACKEND = null;
        if (b != null) {
            try { b.shutdown(); } catch (Throwable ignored) {}
        }
    }

    // ---- spec factory ----

    /**
     * 构造 plugin 线程规范的便捷工厂。
     */
    @NotNull
    public static PluginThreadSpec spec(@NotNull String namePrefix) {
        return PluginThreadSpec.of(namePrefix);
    }

    /**
     * 构造带 plugin 身份 + 命名的规范。
     */
    @NotNull
    public static PluginThreadSpec spec(@NotNull Object pluginId, @NotNull String namePrefix) {
        return PluginThreadSpec.of(pluginId, namePrefix);
    }
}
