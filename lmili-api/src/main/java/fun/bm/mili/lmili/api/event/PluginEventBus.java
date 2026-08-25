package fun.bm.mili.lmili.api.event;

import fun.bm.mili.lmili.api.identity.PluginId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 通用 plugin 间事件总线 —— 让 plugin 通过主题名（topic）发布 / 订阅事件，
 * 解耦 plugin 直接依赖。
 *
 * <h2>典型场景</h2>
 * <ul>
 *   <li>Economy plugin 发出 "player.coin-changed" 事件，shop / rank / quest 等 plugin 订阅</li>
 *   <li>权限 plugin 发出 "player.permission-changed" 事件</li>
 *   <li>任何 plugin 想 "通知全生态" 的自定义事件</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * PluginEventBus bus = LMili.eventBus();
 * bus.publish(myId, "player.coin-changed", new CoinChangeEvent(player, delta));
 *
 * bus.subscribe(myId, "player.coin-changed", CoinChangeEvent.class, evt -> {
 *     // evt 是 CoinChangeEvent
 * });
 * }</pre>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>topic 名 + PluginId 组合唯一（同一 plugin 不能订阅同一 topic 两次）。</li>
 *   <li>事件类型必须强类型（{@link Class} 参数），避免反序列化歧义。</li>
 *   <li>listener 异常被 §6.2 静默捕获，不影响其他 listener。</li>
 *   <li>事件传递是<b>同步</b>（publish 触发所有 listener 后返回）；如需异步，把它扔到 executor。</li>
 * </ul>
 */
public final class PluginEventBus {

    @FunctionalInterface
    public interface EventListener<T> {
        void onEvent(@NotNull T event);
    }

    /** 订阅记录 */
    private static final class Sub {
        final PluginId subscriber;
        final String topic;
        final Class<?> type;
        final EventListener<?> listener;
        Sub(PluginId s, String t, Class<?> ty, EventListener<?> l) {
            this.subscriber = s; this.topic = t; this.type = ty; this.listener = l;
        }
    }

    /** topic → 所有订阅者 */
    private final Map<String, List<Sub>> subscriptions = new ConcurrentHashMap<>();

    public PluginEventBus() {}

    /**
     * 订阅某 topic + 事件类型。
     *
     * @param subscriber 订阅者 PluginId（用于解订追踪）
     * @param topic      主题名（如 {@code "player.coin-changed"}）
     * @param type       事件 payload 的 {@link Class}
     * @param listener   事件回调
     */
    public <T> void subscribe(@NotNull PluginId subscriber, @NotNull String topic,
                              @NotNull Class<T> type, @NotNull EventListener<T> listener) {
        if (subscriber == null || topic == null || type == null || listener == null) {
            throw new IllegalArgumentException("null argument");
        }
        subscriptions.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
                     .add(new Sub(subscriber, topic, type, listener));
    }

    /**
     * 解订 subscriber 在某 topic 的所有 listener。返回解订数量。
     */
    public int unsubscribe(@NotNull PluginId subscriber, @NotNull String topic) {
        List<Sub> subs = subscriptions.get(topic);
        if (subs == null) return 0;
        int removed = 0;
        var it = subs.iterator();
        while (it.hasNext()) {
            if (it.next().subscriber.equals(subscriber)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** 解订 subscriber 在所有 topic 的 listener。plugin 卸载时调用。 */
    public int unsubscribeAll(@NotNull PluginId subscriber) {
        int total = 0;
        for (List<Sub> subs : subscriptions.values()) {
            var it = subs.iterator();
            while (it.hasNext()) {
                if (it.next().subscriber.equals(subscriber)) {
                    it.remove();
                    total++;
                }
            }
        }
        return total;
    }

    /**
     * 发布事件。同步触发所有匹配 topic + type 的 listener；listener 抛异常被 §6.2 静默。
     *
     * @return 被通知的 listener 数量
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int publish(@NotNull PluginId publisher, @NotNull String topic, @NotNull Object event) {
        if (publisher == null || topic == null || event == null) {
            throw new IllegalArgumentException("null argument");
        }
        List<Sub> subs = subscriptions.get(topic);
        if (subs == null) return 0;
        int notified = 0;
        for (Sub s : subs) {
            if (!s.type.isInstance(event)) continue;
            try {
                ((EventListener) s.listener).onEvent(event);
                notified++;
            } catch (Throwable t) {
                // §6.2 fail-safe
            }
        }
        return notified;
    }

    /** 当前活跃订阅总数（topic 数 × listener 数）。 */
    public int subscriptionCount() {
        int total = 0;
        for (List<Sub> subs : subscriptions.values()) total += subs.size();
        return total;
    }

    /** 当前活跃 topic 数。 */
    public int topicCount() {
        return subscriptions.size();
    }
}