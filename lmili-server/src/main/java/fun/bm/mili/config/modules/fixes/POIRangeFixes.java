package fun.bm.mili.config.modules.fixes;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(name = "poi_range_fixes", category = EnumConfigCategory.FIXES)
public class POIRangeFixes implements IConfigModule {
    @ConfigInfo(name = "do_not_compete_poi_if_unloaded", comments = """
            Do not compete POI if it's unloaded
            Related with https://github.com/PaperMC/Folia/issues/292 
            """)
    public static boolean doNotCompetePOIIfUnloaded = false;
}
