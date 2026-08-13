package fun.bm.mili.lmili.config.modules.fixes;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.CommandSuggestions;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.TransformedConfig;
import fun.bm.mili.lmili.enums.EnumCollisionBehaviorMode;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "collision_behavior")
public class CollisionBehaviorConfig implements IConfigModule {
    @TransformedConfig(name = "mode", directory = {"misc", "collision_behavior"})
    @CommandSuggestions(suggest = {"VANILLA", "BLOCK_SHAPE_VANILLA", "PAPER"})
    @ConfigInfo(name = "mode", comments =
            """
                    Decides which collision logics will be used(Moonrise and Paper modified this for optimization but would also break some vanilla behaviours at the same time).
                    Would be useful for fixing improper behaviours of some huge redstone machines
                    Available Value:
                    VANILLA
                    BLOCK_SHAPE_VANILLA
                    PAPER""")
    public static EnumCollisionBehaviorMode behaviorMode = EnumCollisionBehaviorMode.BLOCK_SHAPE_VANILLA;
}