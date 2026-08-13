package fun.bm.mili.config.modules.experiment;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "entity_damage_source_trace")
public class EntityDamageSourceTraceConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments =
            """
                    允许跨不同区域调度器追踪伤害来源。""")
    public static boolean enabled = false;
}