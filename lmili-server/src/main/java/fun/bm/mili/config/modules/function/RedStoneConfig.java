package fun.bm.mili.config.modules.function;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "redstone", comments = """
        Redstone configuration (stub)""")
public class RedStoneConfig implements IConfigModule {
    @ConfigInfo(name = "shears", comments = """
            Whether shears can act as a wrench for redstone components""")
    public static boolean shears = false;
}
