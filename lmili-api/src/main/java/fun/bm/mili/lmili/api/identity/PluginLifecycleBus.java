package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * plugin 生命周期事件总线 —— 让 plugin 监听其他 plugin 的 register / unregister /
 * 状态切换 / 冲突。是 LMili 多插件生态的核心基础设施。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * PluginLifecycleBus bus = LMili.pluginLifecycleBus();
 * if (bus != null) {
 *     bus.register(myPluginId, new PluginLifecycleBus.Listener() {
 *         @Override public void onRegistered(PluginIdentity id) { ... }
 *         @Override public void onUnregistered(PluginId id) { ... }
 *         @Override public void onStatusChange(PluginIdentity id, PluginStatus prev) { ... }
 *         @Override public void onConflict(PluginIdentityConflict c) { ... }
 *     });
 * }
 * }</pre>
 *
 * <p>监听是<b>全局</b>的（同一总线所有 plugin 都收到同一份事件），但 listener 用
 * {@code subscriberId} 标记来源 plugin，便于 plugin 在卸载时只解订自己的 listener。
 *
 * <p>事件按订阅顺序串行触发；任意一个 listener 抛异常不会影响其他 listener
 * （异常被 LMili 静默记录，§6.2 fail-safe）。
 *
 * <h2>线程模型</h2>
 * <p>事件在 LMili 内部线程（identity manager 调用栈）触发；listener 必须<b>轻量</b>
 * 且<b>不阻塞</b>（不要在 listener 内做 IO / sleep / join；§18.4）。如需重活，
 * 把它扔到自己的 executor：
 *
 * <pre>{@code
 * public void onRegistered(PluginIdentity id) {
 *     Threading.executor(mySpec).submit(() -> doHeavyWork(id));
 * }
 * }</pre>
 */
public final class PluginLifecycleBus {

    /** 单个 listener 关心的事件类型。 */
    public interface Listener {
        /** plugin 第一次注册（之前的 register 重放不触发；hot reload 不触发）。 */
        default void onRegistered(@NotNull PluginIdentity identity) {}

        /** plugin 被 unregister（典型于卸载 / 关闭）。 */
        default void onUnregistered(@NotNull PluginId id) {}

        /**
         * plugin 状态变更。
         *
         * <p>{@code previousStatus} 是变更前的状态（{@code null} 表示首次注册）；
         * 通过比对可以让 listener 跳过 "no-op transition"（如 OBSERVE → OBSERVE）。
         */
        default void onStatusChange(@NotNull PluginIdentity identity,
                                    PluginStatus previousStatus) {}

        /**
         * 检测到 plugin 冲突（典型场景：两个不同版本的 plugin 试图用同一个 PluginId）。
         */
        default void onConflict(@NotNull fun.bm.mili.lmili.api.identity.conflict.PluginIdentityConflict c) {}
    }

    /** 内部订阅记录：subscriberId + listener。 */
    private static final class Subscription {
        final PluginId subscriberId;
        final Listener listener;
        Subscription(PluginId s, Listener l) { this.subscriberId = s; this.listener = l; }
    }

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public PluginLifecycleBus() {}

    /**
     * 订阅生命周期事件。
     *
     * @param subscriberId 订阅者自己的 PluginId（用于跟踪 / 卸载时批量解订）
     * @param listener     事件回调
     */
    public void register(@NotNull PluginId subscriberId, @NotNull Listener listener) {
        if (subscriberId == null) throw new IllegalArgumentException("subscriberId must not be null");
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        subscriptions.add(new Subscription(subscriberId, listener));
    }

    /**
     * 解订 subscriberId 注册的所有 listener。
     */
    public int unregister(@NotNull PluginId subscriberId) {
        if (subscriberId == null) return 0;
        int removed = 0;
        // CopyOnWriteArrayList 不支持条件删除，要走 iterator
        var it = subscriptions.iterator();
        while (it.hasNext()) {
            if (it.next().subscriberId.equals(subscriberId)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** 当前活跃订阅数（测试 / 调试用）。 */
    public int subscriptionCount() {
        return subscriptions.size();
    }

    // ---- 内部触发（server-side LMili 内部调用） ----

    /** 内部 API：trigger onRegistered。listener 异常被 §6.2 静默捕获。 */
    public void fireRegistered(@NotNull PluginIdentity identity) {
        for (Subscription s : subscriptions) {
            try { s.listener.onRegistered(identity); }
            catch (Throwable t) { /* §6.2 fail-safe */ }
        }
    }

    /** 内部 API：trigger onUnregistered。 */
    public void fireUnregistered(@NotNull PluginId id) {
        for (Subscription s : subscriptions) {
            try { s.listener.onUnregistered(id); }
            catch (Throwable t) { /* §6.2 fail-safe */ }
        }
    }

    /** 内部 API：trigger onStatusChange。 */
    public void fireStatusChange(@NotNull PluginIdentity identity, PluginStatus previousStatus) {
        for (Subscription s : subscriptions) {
            try { s.listener.onStatusChange(identity, previousStatus); }
            catch (Throwable t) { /* §6.2 fail-safe */ }
        }
    }

    /** 内部 API：trigger onConflict。 */
    public void fireConflict(@NotNull fun.bm.mili.lmili.api.identity.conflict.PluginIdentityConflict c) {
        for (Subscription s : subscriptions) {
            try { s.listener.onConflict(c); }
            catch (Throwable t) { /* §6.2 fail-safe */ }
        }
    }
}