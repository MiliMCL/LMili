package fun.bm.mili.identity;

import fun.bm.mili.MiliLogger;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.LmiliJsonLoader;
import fun.bm.mili.lmili.api.identity.PluginIdentity;
import fun.bm.mili.lmili.api.identity.PluginIdentityFallback;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import fun.bm.mili.lmili.api.identity.PluginStatus;
import fun.bm.mili.lmili.api.identity.PluginType;
import fun.bm.mili.lmili.api.identity.SchedulerDomain;
import fun.bm.mili.lmili.api.identity.conflict.PluginIdentityConflict;
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
 * <p>Behavior:</p>
 * <ul>
 *   <li>{@link #install()} registers listeners and replays already-enabled plugins.</li>
 *   <li>On {@link PluginEnableEvent}, read {@code lmili.json} (or fall back to
 *       {@code plugin.yml}) and call {@link PluginIdentityManager#register}.</li>
 *   <li>On a duplicate id, the existing entry keeps its state and the conflict
 *       is recorded. The incoming plugin is marked CONFLICT.</li>
 *   <li>On {@link PluginDisableEvent} the context is removed and the registry
 *       entry is unregistered.</li>
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
        final PluginIdentityManager manager = LMili.getPluginIdentityManager();
        final PluginIdentity registered = manager.register(incoming);

        if (!registered.id().equals(incoming.id())
                || !registered.version().equals(incoming.version())) {
            logConflict(name, incoming, manager);
            return;
        }

        final PluginRuntimeContext ctx = buildContext(registered);
        PluginRuntimeContext.registerForPlugin(name, ctx);
        bukkitById.put(name, bukkitPlugin);
        logRegistered(name, ctx);
    }

    private void handlePluginDisable(final Plugin bukkitPlugin) {
        if (bukkitPlugin == null) return;
        final String name = bukkitPlugin.getName();
        if (name == null) return;
        if (!seenPluginNames.remove(name)) return;

        final PluginIdentityManager mgr = LMili.getPluginIdentityManager();
        mgr.findByBukkitName(name).ifPresent(id -> mgr.unregister(id.id()));
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
                    name);
        }
        final PluginIdentity fallback = PluginIdentityFallback.fromBukkitPlugin(bukkitPlugin);
        if (fallback != null) return fallback;
        return PluginIdentityFallback.forBukkit(name, "0.0.0");
    }
}