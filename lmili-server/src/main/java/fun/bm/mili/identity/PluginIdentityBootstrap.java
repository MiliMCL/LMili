package fun.bm.mili.identity;

import fun.bm.mili.MiliLogger;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.LifecycleState;
import fun.bm.mili.lmili.api.identity.LmiliJsonLoader;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginIdentity;
import fun.bm.mili.lmili.api.identity.PluginIdentityFallback;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import fun.bm.mili.lmili.api.identity.PluginStatus;
import fun.bm.mili.lmili.api.identity.PluginType;
import fun.bm.mili.lmili.api.identity.SchedulerDomain;
import fun.bm.mili.lmili.api.identity.conflict.PluginIdentityConflict;
import fun.bm.mili.lmili.thread.scheduler.PluginSchedulerBridge;
import fun.bm.mili.lmili.utils.NullPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Server-side bootstrap wiring the LMili identity system into the Bukkit
 * plugin lifecycle. Implements V2 §12 &quot;Discover → Read → Parse → Validate
 * → Register Identity → Create Context → Create Domain → Activate&quot;.
 *
 * <p><b>强约束策略（§C LMili Required）</b>：
 * <ul>
 *   <li>每个 plugin 必须在 {@code lmili.json} 声明 scheduler delegation。</li>
 *   <li>{@code "schedulerDelegation": "LMILI"}（默认）→ plugin 被允许加载并注册 runtime context。</li>
 *   <li>{@code "schedulerDelegation": "BUKKIT"} → plugin 被允许加载但被标记为 LEGACY，
 *       log 警告（管理员应该让其迁移到 LMILI）。</li>
 *   <li>没有 {@code lmili.json} → plugin 被<b>强制禁用</b>并报告原因。</li>
 *   <li>声明了 {@code "schedulerDelegation": "LMILI_REQUIRED"} → plugin 被允许加载，
 *       同时强制其所有调度走 {@code Threading / LMili.scheduler()}；检测到直接
 *       {@code BukkitScheduler.runTask} 调用会被报告（但不禁用 plugin —— §18.4 不粗暴全局锁）。</li>
 * </ul>
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>{@link #install()} registers listeners and replays already-enabled plugins.</li>
 *   <li>On {@link PluginEnableEvent}, read {@code lmili.json} (or fall back to
 *       {@code plugin.yml}) and call {@link PluginIdentityManager#register}.</li>
 *   <li>On a duplicate id, the existing entry keeps its state and the conflict
 *       is recorded. The incoming plugin is marked CONFLICT.</li>
 *   <li>On {@link PluginDisableEvent} the context is removed and the registry
 *       entry is unregistered.</li>
 *   <li>Plugin 没有声明 → {@link #enforceLmiliRequired(Plugin)} 立即 disable。</li>
 * </ul>
 */
public final class PluginIdentityBootstrap implements Listener {

    private static final String LOG_PREFIX = "[LMili][PluginID] ";

    private final Set<String> seenPluginNames = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Plugin> bukkitById = new ConcurrentHashMap<>();

    public PluginIdentityBootstrap() {}

    /**
     * Register listeners and catch up on already-enabled plugins. Idempotent.
     */
    public void install() {
        final Plugin host = resolveHostPlugin();
        Bukkit.getPluginManager().registerEvents(this, host);
        for (final Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p.isEnabled()) {
                handlePluginEnable(p);
            }
        }
    }

    private Plugin resolveHostPlugin() {
        for (final String candidate : new String[]{"LMili", "Mili", "mili"}) {
            try {
                final Plugin p = Bukkit.getPluginManager().getPlugin(candidate);
                if (p != null) return p;
            } catch (final Throwable ignored) { }
        }
        return NullPlugin.INSTANCE;
    }

    /**
     * §C 严格模式：强制禁用 plugin + 取消 seenPluginNames 标记让下次 enable 时
     *     能被重新识别。后续 plugin 仍可被手动加载（修 lmili.json 后 reload）。
     */
    private void enforceDisable(@NotNull final Plugin bukkitPlugin, @NotNull final String reason) {
        try {
            Bukkit.getPluginManager().disablePlugin(bukkitPlugin);
        } catch (final Throwable t) {
            MiliLogger.LOGGER.warn("{}disablePlugin({}) failed: {}", LOG_PREFIX, bukkitPlugin.getName(), t.toString());
        }
        // seenPluginNames 已 add；下次 reload 时才会再次走 handlePluginEnable。
        // 这里让 disablePlugin 触发 PluginDisableEvent → handlePluginDisable 会被调，
        // 但我们的 handlePluginDisable 依赖 seenPluginNames.remove 早返回。我们
        // 直接 remove 让它能正确清理（其实 disabled 的 plugin 不需要清理 runtime ctx，
        // 因为 buildContext() 没被调过 —— ctx 仍未注册）。
        seenPluginNames.remove(bukkitPlugin.getName());
        MiliLogger.LOGGER.warn("{}plugin {} forced disabled: {}", LOG_PREFIX,
                bukkitPlugin.getName(), reason);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPluginEnableMonitor(final PluginEnableEvent event) {
        handlePluginEnable(event.getPlugin());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisableMonitor(final PluginDisableEvent event) {
        handlePluginDisable(event.getPlugin());
    }

    private void handlePluginEnable(final Plugin bukkitPlugin) {
        if (bukkitPlugin == null) return;
        final String name = bukkitPlugin.getName();
        if (name == null || name.isEmpty()) return;
        if (!seenPluginNames.add(name)) return;

        final PluginIdentity incoming = resolveIdentity(bukkitPlugin, name);

        // §C LMili Required 严格策略（26.2+）：plugin 没有 lmili.json 或 delegation
        // 不是 LMILI_REQUIRED → 立即禁用 plugin 并拒绝加载。
        //
        // 历史上（§18.9）曾放过没有 lmili.json 的 plugin（BUKKIT_ONLY fallback），
        // 但这导致观测失效 / 线程碎片 / quota 失效。本次收紧。
        if (!"lmili.json".equals(incoming.source())) {
            MiliLogger.LOGGER.error("{}plugin {} has NO lmili.json. Per §C LMili Required, "
                    + "this plugin is being DISABLED. Add lmili.json with schedulerDelegation "
                    + "field (must equal \"LMILI_REQUIRED\") and reload.",
                    LOG_PREFIX, name);
            enforceDisable(bukkitPlugin, "missing lmili.json");
            return;
        }
        if (incoming.delegation() != fun.bm.mili.lmili.api.identity.SchedulerDelegation.LMILI_REQUIRED) {
            MiliLogger.LOGGER.error("{}plugin {} declared schedulerDelegation={}; "
                    + "only LMILI_REQUIRED is accepted in LMili 26.2+.",
                    LOG_PREFIX, name, incoming.delegation());
            enforceDisable(bukkitPlugin, "non-LMILI_REQUIRED delegation");
            return;
        }

        MiliLogger.LOGGER.info("{}plugin {} accepted (delegation={}, source={})",
                LOG_PREFIX, name, incoming.delegation(), incoming.source());
        final PluginIdentityManager manager = LMili.getPluginIdentityManager();
        final PluginIdentity registered = manager.register(incoming);

        if (!registered.id().equals(incoming.id())
                || !registered.version().equals(incoming.version())) {
            logConflict(name, incoming, manager);
            return;
        }

        // Hot reload: if a runtime context already exists for this PluginId,
        // clean up the old runtime (cancel tasks, clear indices) before
        // building a new one. This ensures Identity, RuntimeContext,
        // SchedulerDomain, Metrics and Bukkit Plugin stay in sync.
        final PluginId registeredId = registered.id();
        final PluginRuntimeContext existing = PluginRuntimeContext.forPluginId(registeredId);
        if (existing != null) {
            MiliLogger.LOGGER.info("{}hot reload detected for {} — cleaning previous runtime",
                    LOG_PREFIX, registeredId.value());
            PluginSchedulerBridge.unloadPlugin(registeredId);
        }

        final PluginRuntimeContext ctx = buildContext(registered);
        // If a runtime context already existed for this PluginId (hot reload),
        // the old one was cleaned up before building the new context.
        PluginRuntimeContext.registerForPlugin(name, ctx);
        // V2 §18: also index by PluginId so the runtime can locate the live
        // context (quota/observability/domain) without a Bukkit name.
        PluginRuntimeContext.registerForPluginId(registered.id(), ctx);
        bukkitById.put(name, bukkitPlugin);
        logRegistered(name, ctx);

        // Flush pending capture stats (spark 早于 LMili bootstrap 注册 → ctx 之前为 null，
        // 报告被暂存在 LocalStats；现在 ctx 就绪，把数字写回 quota / observability）
        try {
            fun.bm.mili.lmili.api.observability.PluginSchedulerCapture cap =
                    fun.bm.mili.lmili.api.LMili.schedulerCapture();
            if (cap != null) {
                fun.bm.mili.lmili.api.observability.PluginSchedulerCapture.CaptureStats stats =
                        cap.statsOf(registered.id());
                if (stats.submitsReported() > 0 || stats.completesReported() > 0
                        || stats.failuresReported() > 0) {
                    if (stats.submitsReported() > 0) {
                        for (long i = 0; i < stats.submitsReported(); i++) {
                            ctx.resourceQuota().onTaskSubmit();
                        }
                    }
                    if (stats.totalExecutionNanos() > 0) {
                        ctx.resourceQuota().onTaskFinish(stats.totalExecutionNanos(), true);
                    }
                    if (stats.failuresReported() > 0) {
                        for (long i = 0; i < stats.failuresReported(); i++) {
                            ctx.observability().recordTaskFailed();
                        }
                    }
                    MiliLogger.LOGGER.info("{}captured pending stats for {}: submits={}, completes={}, failures={}",
                            LOG_PREFIX, registered.id().value(),
                            stats.submitsReported(), stats.completesReported(),
                            stats.failuresReported());
                }
            }
        } catch (Throwable t) {
            // §6.2 fail-safe
        }
    }

    private void handlePluginDisable(final Plugin bukkitPlugin) {
        if (bukkitPlugin == null) return;
        final String name = bukkitPlugin.getName();
        if (name == null) return;
        if (!seenPluginNames.remove(name)) return;

        final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
        final PluginIdentity current = mgr.findByBukkitName(name).orElse(null);

        if (current != null) {
            // V2 §5 full unload sequence:
            // 1. lifecycle → DISABLING
            final PluginRuntimeContext ctx = PluginRuntimeContext.forPluginId(current.id());
            if (ctx != null) {
                ctx.setLifecycleState(LifecycleState.DISABLING);
            }

            // 2. cancel pending scheduler tasks + clear scheduler tracking
            PluginSchedulerBridge.unloadPlugin(current.id());

            // 3. registry → UNLOADED then unregister
            mgr.setStatus(current.id(), PluginStatus.UNLOADED);
            mgr.unregister(current.id());
        }

        // 4. remove Bukkit-name context index
        PluginRuntimeContext.unregisterForPlugin(name);
        bukkitById.remove(name);

        MiliLogger.LOGGER.info("{}unregistered {}", LOG_PREFIX, name);
    }

    private PluginRuntimeContext buildContext(@NotNull final PluginIdentity registered) {
        SchedulerDomain inherited = null;
        if (registered.type() == PluginType.ADDON) {
            inherited = registered.parentId()
                    .flatMap(LMili.getPluginIdentityManager()::find)
                    .map(PluginIdentity::id)
                    .map(parentId -> SchedulerDomain.forAddon(registered.id(), parentId))
                    .orElse(null);
        }
        final PluginRuntimeContext ctx = PluginRuntimeContext.forIdentity(registered, inherited);
        ctx.setLifecycleState(fun.bm.mili.lmili.api.identity.LifecycleState.ACTIVE);
        LMili.getPluginIdentityManager().setStatus(registered.id(), PluginStatus.ACTIVE);
        return ctx;
    }

    private void logRegistered(@NotNull final String name,
                               @NotNull final PluginRuntimeContext ctx) {
        final PluginIdentity id = ctx.identity();
        MiliLogger.LOGGER.info("{}Registered {} ({}) id={} version={} status={} type={}",
                LOG_PREFIX,
                name,
                id.source(),
                id.id().value(),
                id.version(),
                ctx.lifecycleState(),
                id.type());
    }

    private void logConflict(@NotNull final String name,
                             @NotNull final PluginIdentity incoming,
                             @NotNull final PluginIdentityManager manager) {
        final PluginIdentityConflict conflict = manager.getConflicts(incoming.id())
                .stream()
                .filter(c -> c.incoming().id().equals(incoming.id())
                        && c.incoming().version().equals(incoming.version()))
                .reduce((a, b) -> b) // latest
                .orElse(null);
        if (conflict == null) {
            MiliLogger.LOGGER.warn("{}Conflict detected: {} id={} version={} reason=UNKNOWN",
                    LOG_PREFIX, name, incoming.id().value(), incoming.version());
            return;
        }
        MiliLogger.LOGGER.warn("{}Conflict detected: {} id={} version={} reason={} existing={}@{} incoming={}@{}",
                LOG_PREFIX,
                name,
                conflict.id().value(),
                incoming.version(),
                conflict.reason(),
                conflict.existing().name(), conflict.existing().version(),
                conflict.incoming().name(), conflict.incoming().version());
    }

    /**
     * Resolve the identity for a Bukkit plugin. Prefers {@code lmili.json};
     * falls back to {@link PluginIdentityFallback#fromBukkitPlugin}.
     */
    @NotNull
    private PluginIdentity resolveIdentity(@NotNull final Plugin bukkitPlugin,
                                           @NotNull final String name) {
        LmiliJsonLoader.Loaded loaded = null;
        try {
            loaded = LmiliJsonLoader.load(bukkitPlugin.getClass().getClassLoader());
        } catch (final Throwable t) {
            MiliLogger.LOGGER.warn("{}failed reading lmili.json from {}: {}",
                    LOG_PREFIX, name, t.toString());
        }
        if (loaded != null) {
            return PluginIdentity.of(
                    loaded.id(),
                    loaded.name(),
                    loaded.version(),
                    loaded.publisher(),
                    loaded.type(),
                    loaded.parentId(),
                    "lmili.json",
                    name,
                    loaded.delegation());
        }
        // §C 26.2+: 没有 lmili.json → 不再走 PluginIdentityFallback。
        // handlePluginEnable 通过 resolveIdentity 返回的 PluginIdentity.source() 判断
        // 是 "lmili.json" 还是 "auto-discovery (Bukkit)"，分别处理（前者正常，
        // 后者 enforceDisable）。
        //
        // 兜底：返回 source = "auto-discovery (Bukkit)" 的 LEGACY 身份，让
        // handlePluginEnable 在下一行 enforceDisable。
        return PluginIdentityFallback.forBukkit(name, "0.0.0");
    }
}