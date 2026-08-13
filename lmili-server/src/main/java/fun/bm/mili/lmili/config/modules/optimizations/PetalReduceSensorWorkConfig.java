package fun.bm.mili.lmili.config.modules.optimizations;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "reduce_sensor_work", comments = "When it is enabled, it will delete the line of sight cache less often and use a faster nearby comparison.")
public class PetalReduceSensorWorkConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;
    @ConfigInfo(name = "delay_ticks", comments = "The interval of each entity to drop the cache(in ticks)")
    public static int delayTicks = 10;
}