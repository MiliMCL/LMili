package fun.bm.mili.config.modules.function;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "technical_survival_mode", comments = """
        Technical survival mode configuration (stub)""")
public class TechnicalSurvivalModeConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            Whether technical survival mode is enabled""")
    public static boolean enabled = false;
}
