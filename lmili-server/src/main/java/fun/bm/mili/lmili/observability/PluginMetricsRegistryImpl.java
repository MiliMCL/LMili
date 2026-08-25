package fun.bm.mili.lmili.observability;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.observability.MetricsRegistry;
import fun.bm.mili.lmili.api.observability.MetricsRegistry.Counter;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default {@link MetricsRegistry} implementation —— 单一进程级注册表。
 *
 * <h2>采样模型</h2>
 * <p>当前实现不主动采样（让 JMX 客户端按需拉取）；counter.currentValue() 时才调
 * provider。这避免了无意义的 5s 周期线程；当 plugin 知道自己的指标真的被外部读取
 * 时才付出代价。后续如需主动推送，加一个 scheduled executor 即可（不破坏 API）。
 */
public final class PluginMetricsRegistryImpl implements MetricsRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final class CounterImpl implements Counter {
        private final PluginId owner;
        private final String name;
        private final String fullName;
        private final Counter.Provider provider;
        private final List<Counter.Listener> listeners = new CopyOnWriteArrayList<>();
        private volatile double lastValue = Double.NaN;

        CounterImpl(PluginId owner, String name, Counter.Provider provider) {
            this.owner = owner;
            this.name = name;
            this.fullName = owner.value() + "." + name;
            this.provider = provider;
        }

        @Override public double currentValue() {
            double v;
            try {
                v = provider.get();
            } catch (Throwable t) {
                LOGGER.warn("[MetricsRegistry] provider {} threw: {}", fullName, t.toString());
                v = Double.NaN;
            }
            if (v != lastValue) {
                lastValue = v;
                for (Counter.Listener l : listeners) {
                    try { l.onValue(v); } catch (Throwable ignored) {}
                }
            }
            return v;
        }
        @Override public @org.jetbrains.annotations.NotNull String fullName() { return fullName; }
        @Override public void addListener(@org.jetbrains.annotations.NotNull Counter.Listener listener) {
            listeners.add(listener);
        }
        @Override public boolean removeListener(@org.jetbrains.annotations.NotNull Counter.Listener listener) {
            return listeners.remove(listener);
        }
    }

    /** PluginId → (counterName → CounterImpl) */
    private final Map<PluginId, Map<String, CounterImpl>> byPlugin = new ConcurrentHashMap<>();

    public PluginMetricsRegistryImpl() {}

    @Override
    public synchronized @org.jetbrains.annotations.NotNull Counter register(
            @org.jetbrains.annotations.NotNull PluginId pluginId,
            @org.jetbrains.annotations.NotNull String name,
            @org.jetbrains.annotations.NotNull Counter.Provider provider) {
        Map<String, CounterImpl> ns = byPlugin.computeIfAbsent(pluginId, k -> new ConcurrentHashMap<>());
        CounterImpl existing = ns.get(name);
        if (existing != null) {
            throw new IllegalStateException(
                "Counter '" + name + "' already registered for plugin '" + pluginId.value() + "'");
        }
        CounterImpl c = new CounterImpl(pluginId, name, provider);
        ns.put(name, c);
        return c;
    }

    @Override
    public boolean unregister(@org.jetbrains.annotations.NotNull PluginId pluginId,
                              @org.jetbrains.annotations.NotNull String name) {
        Map<String, CounterImpl> ns = byPlugin.get(pluginId);
        return ns != null && ns.remove(name) != null;
    }

    @Override
    public int unregisterAll(@org.jetbrains.annotations.NotNull PluginId pluginId) {
        Map<String, CounterImpl> ns = byPlugin.remove(pluginId);
        return ns == null ? 0 : ns.size();
    }

    @Override
    public int size() {
        int total = 0;
        for (Map<String, CounterImpl> ns : byPlugin.values()) total += ns.size();
        return total;
    }

    /** 给 JMX 注册用 —— 列出所有 counter 全名 + 当前值。 */
    public Map<String, Double> snapshot() {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (Map<String, CounterImpl> ns : byPlugin.values()) {
            for (CounterImpl c : ns.values()) {
                out.put(c.fullName, c.currentValue());
            }
        }
        return out;
    }
}