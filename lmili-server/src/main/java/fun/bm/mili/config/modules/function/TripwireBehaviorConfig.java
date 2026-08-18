package fun.bm.mili.config.modules.function;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.CommandSuggestions;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.TransformedConfig;
import fun.bm.mili.lmili.enums.EnumConfigCategory;
import fun.bm.mili.lmili.enums.EnumTripwireBehavior;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "tripwire_dupe")
public class TripwireBehaviorConfig implements IConfigModule {
    @TransformedConfig(name = "enabled", directory = {"misc", "tripwire_dupe"})
    @ConfigInfo(name = "enabled")
    public static boolean enabled = false;
    @TransformedConfig(name = "behavior_mode", directory = {"misc", "tripwire_dupe"})
    @TransformedConfig(name = "behavior-mode", directory = {"misc", "tripwire_dupe"})
    @CommandSuggestions(suggest = {"VANILLA20", "VANILLA21", "MIXED"})
    @ConfigInfo(name = "behavior_mode", comments =
            """
                    Available Value:
                    VANILLA20
                    VANILLA21
                    MIXED""")
    public static EnumTripwireBehavior behaviorMode = EnumTripwireBehavior.VANILLA21;
}