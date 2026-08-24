package fun.bm.mili.lmili.api.threading;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Threading 后端 SPI —— server-side 实现，plugin 端只通过 {@link Threading} 静态访问。
 *
 * <p>实现类在 server-side（{@code lmili-server/.../observability} 或类似位置）。
 */
public interface ThreadingBackend {

    @NotNull PluginExecutor executor(@NotNull PluginThreadSpec spec);

    @NotNull PluginScheduler scheduler(@NotNull PluginThreadSpec spec);

    @NotNull StructuredConcurrency scope();

    /**
     * 当前 server 的 {@code MiliScheduler}（plugin 受限代理），null 表示不可用。
     */
    @Nullable Object miliScheduler();

    /**
     * plugin-facing 区域任务调度器 facade。
     */
    @NotNull PluginRegionScheduler regionScheduler();

    /**
     * 当前 LMili 版本号。
     */
    @NotNull String version();

    /**
     * SchedulerMetrics（plugin-facing；null = 不可用）。
     */
    @Nullable Object snapshotMetrics();

    /**
     * LongTailEvent 流（plugin-facing；null = 不可用）。
     */
    @Nullable Object longTailEvents();

    /**
     * 关闭后端（卸载所有 plugin executor）。仅 shutdown 时调用。
     */
    void shutdown();
}
