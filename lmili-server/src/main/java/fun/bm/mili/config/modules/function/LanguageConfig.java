package fun.bm.mili.config.modules.function;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "language")
public class LanguageConfig implements IConfigModule {
    @ConfigInfo(name = "lang", comments = """
            请使用 https://minecraft.wiki/w/Language 中的语言键
            格式示例：en_us zh_cn zh_hk zh_tw""")
    public static String lang = "en_us";

    @ConfigInfo(name = "full_blocking_load", comments = """
            是否允许在加载本地化语言时阻塞服务器加载。
            如果你希望终端中只显示本地化语言，
            则需要启用此选项。
            
            警告：这可能会降低启动速度！""")
    public static boolean full_blocking_load = false;
}