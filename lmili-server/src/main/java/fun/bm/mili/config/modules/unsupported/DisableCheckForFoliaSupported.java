package fun.bm.mili.config.modules.unsupported;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.UNSUPPORTED, name = "disable_check_for_folia_supported")
public class DisableCheckForFoliaSupported implements IConfigModule {
    @ConfigInfo(name = "disable_for_paper", comments = """
            Disable check for folia-supported for spigot/bukkit/paper plugin.
            ATTENTION: No support will be provided if you enabled this.""")
    public static boolean disableForPaper = false;

    @ConfigInfo(name = "disable_for_leaves", comments = """
            Disable check for folia-supported for leaves plugin.
            ATTENTION: No support will be provided if you enabled this.""")
    public static boolean disableForLeaves = false;
}
