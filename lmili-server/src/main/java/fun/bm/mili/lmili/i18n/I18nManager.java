package fun.bm.mili.lmili.i18n;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 国际化管理器 —— 支持多语言翻译。
 *
 * <p>使用方式：
 * <pre>{@code
 * // 获取翻译
 * String text = I18nManager.get("tps.server.health.report");
 * String text = I18nManager.get("tps.server.online.players", 10);
 * }</pre>
 *
 * <p>翻译文件位于 {@code resources/i18n/} 目录下，格式为 properties 文件。
 * 文件名对应语言代码，如 {@code zh_cn.properties}、{@code en_us.properties}。
 */
public final class I18nManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    // 默认语言
    private static final String DEFAULT_LOCALE = "en_us";

    // 已加载的翻译缓存
    private static final ConcurrentHashMap<String, Map<String, String>> translations = new ConcurrentHashMap<>();

    // 当前语言
    private static volatile String currentLocale = DEFAULT_LOCALE;

    // Mili start - eager initialization so translations work even without explicit init()
    // 支持的语言列表
    private static final String[] SUPPORTED_LOCALES = {"en_us", "zh_cn"};
    static {
        // 加载所有支持的语言，这样无需调用 init() 也能切换语言
        for (String locale : SUPPORTED_LOCALES) {
            loadTranslations(locale);
        }
    }
    // Mili end

    private I18nManager() {}

    /**
     * 初始化 i18n 系统。
     *
     * @param locale 语言代码（如 "zh_cn"、"en_us"）
     */
    public static void init(String locale) {
        currentLocale = locale.toLowerCase(Locale.ROOT);
        loadTranslations(currentLocale);
        // 始终加载默认语言作为回退
        if (!DEFAULT_LOCALE.equals(currentLocale)) {
            loadTranslations(DEFAULT_LOCALE);
        }
        LOGGER.info("[I18n] Initialized with locale: {}", currentLocale);
    }

    /**
     * 加载翻译文件。
     */
    private static void loadTranslations(String locale) {
        if (translations.containsKey(locale)) {
            return;
        }

        String path = "/i18n/" + locale + ".properties";
        Map<String, String> map = new ConcurrentHashMap<>();

        try (InputStream is = I18nManager.class.getResourceAsStream(path)) {
            if (is != null) {
                Properties props = new Properties();
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                for (String key : props.stringPropertyNames()) {
                    map.put(key, props.getProperty(key));
                }
                LOGGER.info("[I18n] Loaded {} translations for locale: {}", map.size(), locale);
            } else {
                LOGGER.warn("[I18n] Translation file not found: {}", path);
            }
        } catch (IOException e) {
            LOGGER.error("[I18n] Failed to load translations for locale: {}", locale, e);
        }

        translations.put(locale, map);
    }

    /**
     * 获取翻译文本。
     *
     * @param key 翻译键
     * @return 翻译文本，如果未找到则返回键名
     */
    public static String get(String key) {
        // 先尝试当前语言
        Map<String, String> currentMap = translations.get(currentLocale);
        if (currentMap != null) {
            String value = currentMap.get(key);
            if (value != null) {
                return value;
            }
        }

        // 回退到默认语言
        if (!DEFAULT_LOCALE.equals(currentLocale)) {
            Map<String, String> defaultMap = translations.get(DEFAULT_LOCALE);
            if (defaultMap != null) {
                String value = defaultMap.get(key);
                if (value != null) {
                    return value;
                }
            }
        }

        // 未找到翻译，返回键名
        return key;
    }

    /**
     * 设置当前语言（无需重新加载翻译文件，前提是已通过 init() 或静态块加载过该语言）。
     *
     * @param locale 语言代码（如 "zh_cn"、"en_us"）
     */
    public static void setLocale(String locale) {
        if (locale == null || locale.isEmpty()) return;
        String normalized = locale.toLowerCase(java.util.Locale.ROOT);
        if (translations.containsKey(normalized)) {
            currentLocale = normalized;
        }
    }

    /**
     * 获取翻译文本（带参数替换）。
     *
     * @param key  翻译键
     * @param args 参数（替换 {0}、{1} 等占位符）
     * @return 翻译文本
     */
    public static String get(String key, Object... args) {
        String template = get(key);
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    }

    /**
     * 获取当前语言。
     */
    public static String getCurrentLocale() {
        return currentLocale;
    }

    /**
     * 检查是否有指定键的翻译。
     */
    public static boolean has(String key) {
        Map<String, String> currentMap = translations.get(currentLocale);
        if (currentMap != null && currentMap.containsKey(key)) {
            return true;
        }
        Map<String, String> defaultMap = translations.get(DEFAULT_LOCALE);
        return defaultMap != null && defaultMap.containsKey(key);
    }
}
