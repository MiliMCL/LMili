package fun.bm.mili.config.modules.optimizations;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "variable_entity_waking_up")
public class GaleVariableEntityWakeupConfig implements IConfigModule {
    @ConfigInfo(name = "entity_wakeup_duration_ratio_standard_deviation", comments = """
            If this value is set to any value > 0, waking up inactive entities happens spread over time, instead of many entities at once. This makes entities feel and behave more natural.
            This setting is the coefficient of variation, or σ / μ (the ratio of the standard deviation to the mean) of the inactivity duration.
            
            In other words, this setting is the value σ, so that the regular inactivity duration will be multiplied by a factor normal_distribution(μ = 1, σ).
            If a value ≤ 0 is given, variable entity wake-up is disabled.""")
    public static double entityWakeUpDurationRatioStandardDeviation = 0.2;
}