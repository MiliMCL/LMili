package fun.bm.mili.lmili.config.modules.optimizations;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "use_async_protocol_switching")
public class AsyncProtocolChangeConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            Uses async protocol preparation for mc.
            Warn: Due to the packet sequence was changed by this optimization, it might be\s
             uncompatible with some plugins(ViaVersion etc.)""")
    public static boolean enabled = false;
}
