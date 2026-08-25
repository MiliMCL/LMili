package fun.bm.mili.lmili.api.observability;

import fun.bm.mili.lmili.api.identity.PluginId;
import org.jetbrains.annotations.NotNull;

/**
 * 外部 plugin（特别是 spark 这种不直接走 LMili 调度的）报告自己工作量的入口。
 *
 * <p><b>解决什么问题</b>（§11 P2-2 / 用户痛点）：
 * <ul>
 *   <li>spark 通过原生 {@code BukkitScheduler} 跑 profiler 任务，<b>不</b>走
 *       {@code Mili.scheduler()} —— LMili 看不到。</li>
 *   <li>用户执行 {@code /pluginid status spark} 时，{@code ResourceQuota.tasksCompleted()}
 *       永远是 0，{@code ObservabilityContext.schedulerRequests} 永远是 0。</li>
 * </ul>
 *
 * <p><b>本接口的解法</b>：
 * <ol>
 *   <li>spark（或任何外部 plugin）在启动时调用 {@link LMili#installSchedulerCapture}，
 *       注册一个 {@link PluginSchedulerCapture}，并 hook 自己关心的"调度点"。</li>
 *   <li>spark 的 scheduler wrapper 每次 submit 时调用 {@link #recordSubmit(PluginId)}；
 *       每次 task 完成时调用 {@link #recordComplete(PluginId, long, boolean)}。</li>
 *   <li>LMili 把这些数字回填到该 plugin 的 {@code ResourceQuota} 和
 *       {@code ObservabilityContext}，{@code /pluginid status spark} 就能正确显示。</li>
 * </ol>
 *
 * <p><b>不做</b>：不自动 hook BukkitScheduler（§18.4 / §18.9）。本接口是<b>声明式</b>的，
 * 由外部 plugin 自己决定要不要上报。
 */
public interface PluginSchedulerCapture {

    /**
     * 注册一个外部 plugin 的 scheduler capture 源。
     *
     * <p>LMili 启动后由该 plugin 自己调用一次：
     * <pre>{@code
     * LMili.schedulerCapture().registerCapture(sparkPluginId, new BukkitSchedulerHook(spark));
     * }</pre>
     */
    void registerCapture(@NotNull PluginId pluginId, @NotNull CaptureHook hook);

    /**
     * 反注册（plugin 卸载时）。
     */
    boolean unregisterCapture(@NotNull PluginId pluginId);

    /**
     * 手动记录一次 submit（plugin 不愿挂 hook，可以主动调）。
     */
    void recordSubmit(@NotNull PluginId pluginId);

    /**
     * 手动记录一次 task 完成。
     *
     * @param pluginId       plugin
     * @param executionNanos 实际耗时（ns）
     * @param success        true 正常完成 / false 抛异常
     */
    void recordComplete(@NotNull PluginId pluginId, long executionNanos, boolean success);

    /**
     * 手动记录一次 submit，返回 token 用于 {@link #recordCompleteToken} 配对。
     *
     * <p>这是更安全的 API：避免 plugin 误调 {@code recordSubmit} 但忘记
     * 调 {@code recordComplete}（造成 submitted 计数漂移）。token 是简单
     * long 自增，plugin 在 try-finally 里配对 recordCompleteToken 即可。
     */
    default long recordSubmitToken(@NotNull PluginId pluginId) {
        recordSubmit(pluginId);
        return System.nanoTime(); // token 是 submit 时间戳
    }

    /**
     * 配对 {@link #recordSubmitToken} 的 complete。token 必须等于 plugin 上一次
     * recordSubmitToken 的返回值。
     */
    default void recordCompleteToken(@NotNull PluginId pluginId, long token, boolean success) {
        long executionNanos = System.nanoTime() - token;
        if (executionNanos < 0) executionNanos = 0;
        recordComplete(pluginId, executionNanos, success);
    }

    /**
     * 单个 plugin 当前的 capture 统计（用于调试 / 自查）。
     */
    @NotNull CaptureStats statsOf(@NotNull PluginId pluginId);

    /**
     * plugin 注入的 capture hook。
     */
    interface CaptureHook {
        /** 每秒约被 LMili 调用一次（采样），用于 hook 自检或刷新内部状态。 */
        default void tick() {}

        /** 卸载时清理。 */
        default void close() {}
    }

    /** Capture 统计 record */
    record CaptureStats(
            long submitsReported,
            long completesReported,
            long totalExecutionNanos,
            long failuresReported
    ) {}
}
