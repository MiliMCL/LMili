package fun.bm.mili.config.modules.misc;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.TransformedConfig;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "force_disable_packet_limiter_of_paper", comments =
        "Force and fully disable all packet limiters of Paper, which is used to prevent from kicking by using some quick crafting mods but \n" +
                "has negative impacts on security"
)
public class PaperPacketLimiterConfig implements IConfigModule {
    @TransformedConfig(name = "force_disable", directory = {"optimizations", "force_disable_packet_limiter_of_paper"})
    @ConfigInfo(name = "force_disable")
    public static boolean forceDisable = false;
}
