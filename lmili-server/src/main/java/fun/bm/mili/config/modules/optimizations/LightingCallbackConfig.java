package fun.bm.mili.config.modules.optimizations;

import fun.bm.mili.config.TomlConfigData;
import fun.bm.mili.utils.LightCallbackManager;
import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lighting-callback")
public class LightingCallbackConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            启用光照引擎回调""")
    public static boolean enabled = false;

    @ConfigInfo(name = "track-sky-light", comments = """
            追踪天空光照变化""")
    public static boolean trackSkyLight = true;

    @ConfigInfo(name = "track-block-light", comments = """
            追踪方块光照变化""")
    public static boolean trackBlockLight = true;

    @ConfigInfo(name = "callback-delay-ticks", comments = """
            回调延迟（tick）""")
    public static int callbackDelayTicks = 0;

    @Override
    public void onLoaded(TomlConfigData configInstance, @Nullable Set<Exception> exs) {
        LightCallbackManager.setEnabled(enabled);
    }

    @Override
    public void onUnloaded(TomlConfigData configInstance) {
        LightCallbackManager.setEnabled(false);
    }
}
