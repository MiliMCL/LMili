package fun.bm.mili.config.modules.misc;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "username_checks")
public class UsernameCheckConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = "Decide whether the username checks are enabled, \n" +
            " you could disable it if your players are using Chinese username but also notification any security impacts caused by disabling it")
    public static boolean enabled = true;
}