package fun.bm.mili.config.modules.fixes;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.TransformedConfig;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "force_cleanup_drop_non_owned_entity_memory_module", comments = "This config is a temporary fix for those incorrect owned data in the memory of each mob, for more you can see https://github.com/PaperMC/Folia/issues/203")
public class ForceCleanupEntityBrainMemoryConfig implements IConfigModule {
    @TransformedConfig(name = "enabled_for_entity", directory = {"optimizations", "enabled_for_entity"})
    @TransformedConfig(name = "enabled_for_entity", directory = {"experiment", "enabled_for_entity"})
    @ConfigInfo(name = "enabled_for_entity", comments = "When enabled, the entity's brain will clean the memory which is typed of entity and not belong to current tickregion")
    public static boolean enabledForEntity = false;

    @TransformedConfig(name = "enabled_for_block_pos", directory = {"optimizations", "enabled_for_block_pos"})
    @TransformedConfig(name = "enabled_for_block_pos", directory = {"experiment", "enabled_for_block_pos"})
    @ConfigInfo(name = "enabled_for_block_pos", comments = "When enabled, the entity's brain will clean the memory which is typed of block_pos and not belong to current tickregion")
    public static boolean enabledForBlockPos = false;

    @ConfigInfo(name = "enabled_for_position_tracker", comments = "When enabled, the entity's brain will clean the memory which is typed of position_tracker and not belong to current tickregion")
    public static boolean enabledForPositionTracker = false;
}