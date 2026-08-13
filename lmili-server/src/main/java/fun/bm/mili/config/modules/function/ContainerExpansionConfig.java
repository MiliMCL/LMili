package fun.bm.mili.config.modules.function;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.CommandSuggestions;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.TransformedConfig;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "container_expansion")
public class ContainerExpansionConfig implements IConfigModule {
    @TransformedConfig(name = "barrel_rows", directory = {"misc", "container_expansion"})
    @CommandSuggestions(suggest = {"1", "2", "3", "4", "5", "6"})
    @ConfigInfo(name = "barrel_rows", comments =
            """
                    范围：1~6""")
    public static int barrelRows = 3;

    @TransformedConfig(name = "enderchest_rows", directory = {"misc", "container_expansion"})
    @CommandSuggestions(suggest = {"1", "2", "3", "4", "5", "6"})
    @ConfigInfo(name = "enderchest_rows", comments =
            """
                    范围：1~6""")
    public static int enderchestRows = 3;

    @TransformedConfig(name = "shulker_stackable_count", directory = {"function", "container_expansion"})
    @TransformedConfig(name = "shulker_stackable_count", directory = {"misc", "container_expansion"})
    @CommandSuggestions(suggest = {"1", "2", "32", "64"})
    @ConfigInfo(name = "shulker_stackable_count", directory = {"shulker_box"}, comments =
            """
                    范围：1~64""")
    public static int shulkerCount = 1;

    @TransformedConfig(name = "same_nbt_shulker_stackable", directory = {"function", "container_expansion"})
    @TransformedConfig(name = "same_nbt_shulker_stackable", directory = {"misc", "container_expansion"})
    @ConfigInfo(name = "same_nbt_shulker_stackable", directory = {"shulker_box"})
    public static boolean nbtShulkerStackable = false;
}