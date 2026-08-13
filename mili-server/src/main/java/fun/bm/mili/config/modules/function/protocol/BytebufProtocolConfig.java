package fun.bm.mili.config.modules.function.protocol;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "bytebuf", directory = {"protocol"})
public class BytebufProtocolConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            启用 bytebuf API 用于自定义数据包处理""")
    public static boolean enabled = true;
}
