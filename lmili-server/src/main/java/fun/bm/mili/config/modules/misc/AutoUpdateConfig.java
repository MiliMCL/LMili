package fun.bm.mili.config.modules.misc;

import fun.bm.mili.config.TomlConfigData;
import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.DoNotLoad;
import fun.bm.mili.lmili.enums.EnumConfigCategory;
import fun.bm.mili.lmili.utils.AutoUpdateHelper;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

@ConfigClassInfo(
        category = EnumConfigCategory.MISC,
        name = "auto_update",
        comments = """
                Checks GitHub Releases for newer Mili jars on a schedule.
                Downloads are staged under auto_update/mili and written to auto_update/core.path,
                which Hyacinthusclip can consume on the next restart.
                If target_jar_path is set, Mili will also try to replace that launcher jar directly."""
)
public class AutoUpdateConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = "Whether Mili should check for updates automatically.")
    public static boolean enabled = false;

    @ConfigInfo(name = "check_times", comments = "List of daily check times in HH:mm, based on the server's local time zone.")
    public static List<String> checkTimes = List.of("06:00");

    @ConfigInfo(name = "allow_prerelease", comments = "Whether prerelease GitHub releases are allowed when selecting an update.")
    public static boolean allowPrerelease = false;

    @ConfigInfo(name = "target_jar_path", comments = """
            Optional launcher jar path to replace after a successful download.
            Leave this blank to keep the downloaded jar staged in auto_update/mili
            and let Hyacinthusclip switch to it through auto_update/core.path on restart.""")
    public static String targetJarPath = "";

    @DoNotLoad
    public AutoUpdateHelper instance = null;

    @Override
    public void onLoaded(TomlConfigData configInstance, @Nullable Set<Exception> exs) {
        if (enabled) {
            if (instance == null) {
                instance = new AutoUpdateHelper();
            }
            instance.load(false);
        }
    }

    @Override
    public void onUnloaded(TomlConfigData configInstance) {
        if (instance != null) instance.shutdown();
    }
}
