package fun.bm.mili.config.modules.fixes;

import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "long_command_support")
public class LongCommandSupportConfig {
    @ConfigInfo(name = "enabled", comments = """
            Some long commands can be run through the dialog command,
            but paper has prohibited it.
            Enable this to fix this problem.""")
    public static boolean enabled = true;
}
