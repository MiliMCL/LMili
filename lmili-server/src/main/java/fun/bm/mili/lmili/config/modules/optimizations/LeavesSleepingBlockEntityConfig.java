package fun.bm.mili.lmili.config.modules.optimizations;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.HotReloadUnsupported;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "lithium_sleeping_block_entity")
public class LeavesSleepingBlockEntityConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            Use sleeping blocking optimizations from lithium,\s
             on luminol the hopper optimizations of paper were totally removed and replaced by those of lithium\s
            and it's turned on by default""")
    @HotReloadUnsupported
    public static boolean enabled = true;
}
