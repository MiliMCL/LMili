package fun.bm.mili.lmili.api.observability;

import org.jetbrains.annotations.NotNull;

/**
 * plugin 自定义指标注册表 —— 让 plugin 把自己的数字发布到 LMili 的 JMX MXBean
 * ({@code fun.bm.mili:type=PluginMetrics})，外部监控工具（Spark/VisualVM/JMX 客户端）
 * 可统一读取 LMili + 所有 plugin 的指标。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * MetricsRegistry registry = LMili.metricsRegistry();
 * Counter submitted = registry.register(pluginId, "db.queries.submitted",
 *                  new Counter.Provider(() -> dbQueue.size()));
 * registry.addListener(pluginId, "db.queries.submitted",
 *                  new Counter.Listener() { public void onValue(double v) { ... } });
 * }</pre>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>counter name 在同一 pluginId 下<b>唯一</b>；重复 register 抛 {@link IllegalStateException}。</li>
 *   <li>counter value 是 {@code double}（避免 long overflow；外部 JMX 标准类型）。</li>
 *   <li>provider 抛出异常时，LMili 用 {@link Double#NaN} 兜底（不阻塞其他 counter）。</li>
 * </ul>
 */
public interface MetricsRegistry {

    /**
     * 注册一个 counter。Plugin 卸载时应 {@link #unregister}。
     *
     * @param pluginId  谁注册（用于 namespace + 卸载清理）
     * @param name      counter 名（如 {@code "db.queries.submitted"}，会被前缀化为 {@code <id>.db.queries.submitted}）
     * @param provider  值的实时提供函数（每次采样调用一次）
     * @return 注册后的 counter 句柄
     */
    @NotNull
    Counter register(@NotNull fun.bm.mili.lmili.api.identity.PluginId pluginId,
                     @NotNull String name,
                     @NotNull Counter.Provider provider);

    /** 卸载某 counter。返回是否真的删了一个。 */
    boolean unregister(@NotNull fun.bm.mili.lmili.api.identity.PluginId pluginId,
                       @NotNull String name);

    /**
     * 卸载某 plugin 注册的所有 counter。
     *
     * <p>LMili 会在 plugin unregister 时自动调用（hook 进 PluginLifecycleBus），
     * 但允许 plugin 主动调一次作为兜底。
     */
    int unregisterAll(@NotNull fun.bm.mili.lmili.api.identity.PluginId pluginId);

    /** 当前已注册 counter 数量。 */
    int size();

    /** counter 句柄 */
    interface Counter {
        /** counter 值的实时提供函数（每次采样调用）。 */
        @FunctionalInterface
        interface Provider {
            double get();
        }

        /** counter 变更监听器（可选）。 */
        @FunctionalInterface
        interface Listener {
            void onValue(double value);
        }

        /** 当前瞬时值（不会触发 provider，只返回上次采样结果）。 */
        double currentValue();

        /** counter 全名（含 pluginId 前缀）。 */
        @NotNull String fullName();

        /** 加 listener：每次采样后调用（采样间隔 ≈ 5s）。 */
        void addListener(@NotNull Listener listener);

        /** 移除 listener。 */
        boolean removeListener(@NotNull Listener listener);
    }
}