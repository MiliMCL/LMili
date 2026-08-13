package fun.bm.mili.lmili.config.modules.optimizations;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "end_dragon")
public class OptimizedDragonRespawnConfig implements IConfigModule {
    @ConfigInfo(name = "optimized_dragon_respawn")
    public static boolean optimizedRespawn = false;
}
