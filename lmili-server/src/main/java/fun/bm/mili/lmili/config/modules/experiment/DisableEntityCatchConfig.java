package fun.bm.mili.lmili.config.modules.experiment;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "disable_entity_exception_catchers")
public class DisableEntityCatchConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            If this config enabled, the server will crash directly when entity ticking has some errors instead of removing the entity to keep server running.
            It could prevent entity disappearing but may cause more server crashes.
            DO NOT ENABLE UNLESS YOU KNOW WHAT YOU ARE DOING!!!""")
    public static boolean enabled = false;
}