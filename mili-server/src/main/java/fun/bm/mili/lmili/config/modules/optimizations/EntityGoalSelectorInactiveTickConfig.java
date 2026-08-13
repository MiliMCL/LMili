package fun.bm.mili.lmili.config.modules.optimizations;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "throttle_goal_selector_tick_in_inactive_tick", comments =
        "Throttles the AI goal selector in entity inactive ticks. \n" +
                "This can improve performance by a few percent, but has minor gameplay implications."
)
public class EntityGoalSelectorInactiveTickConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
}