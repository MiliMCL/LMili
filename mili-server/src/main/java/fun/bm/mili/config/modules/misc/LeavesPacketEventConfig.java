package fun.bm.mili.config.modules.misc;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "leaves_packet_event", comments = """
        Leaves packet event configuration (stub)""")
public class LeavesPacketEventConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            Whether leaves packet events are enabled""")
    public static boolean enabled = false;
}
