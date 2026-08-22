package fun.bm.mili.config.modules.function;

import fun.bm.mili.MiliLogger;
import fun.bm.mili.command.PluginsCommand;
import fun.bm.mili.config.TomlConfigData;
import fun.bm.mili.identity.PluginIdentityBootstrap;
import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.DoNotLoad;
import fun.bm.mili.lmili.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Wires the LMili Plugin ID System into the server bootstrap.
 *
 * <p>Discovered by {@code ConfigsInstance}, which scans
 * {@code fun.bm.mili.config.modules}. On {@link #onLoaded} we install the
 * bootstrap listener and register the {@code /plugins} command.</p>
 */
@ConfigClassInfo(category = EnumConfigCategory.FUNCTION,
                 name = "plugin-identity-system",
                 comments = "LMili Plugin ID System bootstrap.\n"
                         + "When enabled, every Bukkit plugin is registered into the\n"
                         + "LMili Identity Registry on PluginEnableEvent. Duplicate ids\n"
                         + "are detected and recorded as PluginIdentityConflict.")
public final class PluginIdentitySystemConfig implements IConfigModule {

    @ConfigInfo(name = "enabled",
                comments = "Master switch. Set to false to disable the entire identity system.")
    private boolean enabled = true;

    @DoNotLoad
    private static volatile PluginIdentityBootstrap BOOTSTRAP;

    @DoNotLoad
    private static volatile PluginsCommand COMMAND;

    public boolean isEnabled() { return enabled; }

    @Override
    public void onLoaded(final TomlConfigData configInstance,
                         @Nullable final Set<Exception> errors) {
        if (!enabled) {
            MiliLogger.LOGGER.info("[LMiliIdentity] Plugin Identity System disabled by config.");
            return;
        }
        if (BOOTSTRAP == null) {
            BOOTSTRAP = new PluginIdentityBootstrap();
            try {
                BOOTSTRAP.install();
                MiliLogger.LOGGER.info("[LMiliIdentity] Plugin Identity System enabled.");
            } catch (final Throwable t) {
                MiliLogger.LOGGER.error("[LMiliIdentity] Failed to install bootstrap", t);
            }
        }
        if (COMMAND == null) {
            try {
                COMMAND = new PluginsCommand();
                COMMAND.register();
            } catch (final Throwable t) {
                MiliLogger.LOGGER.error("[LMiliIdentity] Failed to register /plugins command", t);
            }
        }
    }

    @Override
    public void onUnloaded(final TomlConfigData configInstance) {
        if (COMMAND != null) {
            try { COMMAND.unregister(); } catch (final Throwable ignored) { }
            COMMAND = null;
        }
        BOOTSTRAP = null;
    }

    public static synchronized void resetForTests() {
        BOOTSTRAP = null;
        COMMAND = null;
    }
}