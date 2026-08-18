package fun.bm.mili.config.modules.fixes;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.TransformedConfig;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "item_multitask")
public class ItemMultitaskConfig implements IConfigModule {
    @TransformedConfig(name = "enabled", directory = {"function", "item_multitask"})
    @ConfigInfo(name = "enabled", comments = """
            Prevent the server from interrupting the state of items
            during block interactions or hotbar slot changes.""")
    public static boolean enabled = false;
}
