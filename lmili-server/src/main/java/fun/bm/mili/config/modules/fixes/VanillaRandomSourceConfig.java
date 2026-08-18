package fun.bm.mili.config.modules.fixes;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "use_vanilla_random_source")
public class VanillaRandomSourceConfig implements IConfigModule {
    @ConfigInfo(name = "enable_for_player_entity", comments = "Related with RNG cracks")
    public static boolean useLegacyRandomSourceForPlayers = false;
}