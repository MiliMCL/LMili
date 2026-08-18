package fun.bm.mili.config.modules.fixes;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.TransformedConfig;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "fix_high_velocity_issue")
public class FoliaEntityMovingFixConfig implements IConfigModule {
    @TransformedConfig(name = "enabled", directory = {"fixes", "fix_high_velocity_issue", "folia"})
    @ConfigInfo(name = "enabled", comments =
            """
                    A simple fix of an issue on folia\s
                    (Sometimes the entity would\s
                    have a large moment that cross the\s
                    different tick regions, and it would\s
                    make the server crashed) but sometimes it might doesn't work""")
    public static boolean enabled = false;

    @TransformedConfig(name = "warn_on_detected", directory = {"fixes", "fix_high_velocity_issue", "folia"})
    @ConfigInfo(name = "warn_on_detected")
    public static boolean warnOnDetected = false;
}