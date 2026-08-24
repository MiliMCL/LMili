package fun.bm.mili.config.modules.function;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "language")
public class LanguageConfig implements IConfigModule {

    @Override
    public void onLoaded(@org.jetbrains.annotations.Nullable fun.bm.mili.config.TomlConfigData configInstance,
                          @org.jetbrains.annotations.Nullable java.util.Set<Exception> e) {
        // 每次配置加载（包括 hot reload）都重新初始化 i18n locale，
        // 避免用户改了 function.language.lang 但 /pluginid 等命令仍输出旧语言。
        // 注意：I18nManager.init 内部会 loadTranslations（如果没加载过），且会
        // 重置 currentLocale；不调用则永远停在静态块默认的 "en_us" 上。
        try {
            fun.bm.mili.lmili.i18n.I18nManager.init(lang);
        } catch (Throwable ignored) {
            // 装配失败时静默（用默认 locale en_us）
        }
    }
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