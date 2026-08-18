package fun.bm.mili.config.modules.misc;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "folia_watchdog")
public class FoliaWatchdogConfig implements IConfigModule {
    @ConfigInfo(name = "tick_region_time_out_ms", comments = "Decides the interval of the watchdog prints the threads dumps of tickregions in stuck")
    public static int tickRegionTimeOutMs = 5000;
}