package fun.bm.mili.lmili.i18n;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.api.identity.PluginId;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PluginId 命名空间隔离的 i18n 工具 —— 允许每个插件注册自己专属的翻译资源，
 * 同时自动回退到 {@link I18nManager} 持有的全局翻译（如 {@code zh_cn.properties} /
 * {@code en_us.properties}）。
 *
 * <h2>资源命名约定</h2>
 * <p>默认按 {@code /i18n/<pluginId>.<locale>.properties} 从 classpath 加载，例如：
 * <pre>
 *   /i18n/xucy.mili.zh_cn.properties
 *   /i18n/xucy.mili.en_us.properties
 * </pre>
 * <p>插件也可直接传入 {@link InputStream} / {@link Reader} / {@link Path} /
 * {@link Properties} 来注册翻译，绕过 classpath 约定。
 *
 * <h2>查找顺序</h2>
 * <ol>
 *   <li>当前全局 locale 下，{@code pluginId} 命名空间的翻译表；</li>
 *   <li>当前全局 locale 下，全局 {@link I18nManager} 翻译表；</li>
 *   <li>默认 locale（如 {@code en_us}）下，{@code pluginId} 命名空间的翻译表；</li>
 *   <li>默认 locale 下，全局 {@link I18nManager} 翻译表；</li>
 *   <li>都未命中则返回 {@code key} 字符串本身（不会返回 {@code null}）。</li>
 * </ol>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * PluginId PLUGIN_ID = PluginId.parse("xucy.mili");
 *
 * // 1) 通过 classpath 约定加载（首次按 pluginId 取值时懒加载）
 * //    期望资源路径：/i18n/xucy.mili.<locale>.properties
 *
 * // 2) 通过显式 registerTranslations 加载任意位置的资源
 * try (InputStream in = Files.newInputStream(Path.of("plugins/xucy-mili/lang/zh_cn.properties"))) {
 *     PluginI18n.registerTranslations(PLUGIN_ID, "zh_cn", in);
 * }
 *
 * // 3) 取翻译（使用全局当前 locale）
 * String greeting = PluginI18n.get(PLUGIN_ID, "greeting.hello");
 * String welcome = PluginI18n.get(PLUGIN_ID, "welcome.user", playerName);
 *
 * // 4) 显式指定 locale（调试 / 系统消息）
 * String debug = PluginI18n.get(PLUGIN_ID, "zh_cn", "greeting.hello");
 * }</pre>
 *
 * <p>线程安全：所有静态方法都是线程安全的，命名空间表使用
 * {@link ConcurrentHashMap} 实现。
 */
public final class PluginI18n {

    private static final Logger LOGGER = LogUtils.getLogger();

    // 默认语言，与 I18nManager 保持一致
    private static final String DEFAULT_LOCALE = "en_us";

    // PluginId -> (locale -> (key -> value))
    private static final ConcurrentHashMap<PluginId, ConcurrentHashMap<String, Map<String, String>>> NAMESPACES =
            new ConcurrentHashMap<>();

    // PluginId -> 是否已尝试过 classpath 懒加载（避免反复 IO）
    private static final ConcurrentHashMap<PluginId, ConcurrentHashMap<String, Boolean>> LOAD_ATTEMPTED =
            new ConcurrentHashMap<>();

    private PluginI18n() {}

    // ---------------------------------------------------------------------
    // 注册 API
    // ---------------------------------------------------------------------

    /**
     * 给指定 {@link PluginId} + locale 注册一份翻译。
     *
     * <p>可多次调用，会与已有同名 key 合并；后注册的同 key 覆盖先注册的（不同 key 都保留）。
     *
     * @param pluginId 命名空间（不能为 {@code null}）
     * @param locale   语言代码（如 {@code zh_cn}、{@code en_us}），大小写不敏感
     * @param in       properties 输入流（由调用方负责关闭）
     * @throws IOException 解析失败时抛出
     */
    public static void registerTranslations(PluginId pluginId, String locale, InputStream in) throws IOException {
        if (pluginId == null) throw new IllegalArgumentException("pluginId must not be null");
        if (locale == null || locale.isEmpty()) throw new IllegalArgumentException("locale must not be empty");
        if (in == null) throw new IllegalArgumentException("input stream must not be null");
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            registerTranslations(pluginId, locale, reader);
        }
    }

    /**
     * 给指定 {@link PluginId} + locale 注册一份翻译（来自文件路径）。
     */
    public static void registerTranslations(PluginId pluginId, String locale, Path file) throws IOException {
        if (file == null) throw new IllegalArgumentException("file path must not be null");
        try (InputStream in = Files.newInputStream(file)) {
            registerTranslations(pluginId, locale, in);
        }
    }

    /**
     * 给指定 {@link PluginId} + locale 注册一份翻译（来自 {@link Reader}）。
     */
    public static void registerTranslations(PluginId pluginId, String locale, Reader reader) throws IOException {
        if (pluginId == null) throw new IllegalArgumentException("pluginId must not be null");
        if (locale == null || locale.isEmpty()) throw new IllegalArgumentException("locale must not be empty");
        if (reader == null) throw new IllegalArgumentException("reader must not be null");
        Properties props = new Properties();
        props.load(reader);
        registerTranslations(pluginId, locale, props);
    }

    /**
     * 给指定 {@link PluginId} + locale 注册一份翻译（来自 {@link Properties}）。
     *
     * <p>会与已有命名空间表合并；同 key 后注册覆盖先注册。
     */
    public static void registerTranslations(PluginId pluginId, String locale, Properties props) {
        if (pluginId == null) throw new IllegalArgumentException("pluginId must not be null");
        if (locale == null || locale.isEmpty()) throw new IllegalArgumentException("locale must not be empty");
        if (props == null) throw new IllegalArgumentException("properties must not be null");
        String normalized = normalizeLocale(locale);
        Map<String, String> existing = namespaceFor(pluginId).get(normalized);
        if (existing == null) {
            Map<String, String> map = new ConcurrentHashMap<>();
            for (String key : props.stringPropertyNames()) {
                map.put(key, props.getProperty(key));
            }
            namespaceFor(pluginId).put(normalized, map);
        } else {
            for (String key : props.stringPropertyNames()) {
                existing.put(key, props.getProperty(key));
            }
        }
        // 标记为已显式注册，避免 classpath 懒加载覆盖
        attempted(pluginId, normalized).put(normalized, Boolean.TRUE);
        LOGGER.info("[PluginI18n] Registered {} keys for {}@{}", props.size(), pluginId.value(), normalized);
    }

    // ---------------------------------------------------------------------
    // 查找 API
    // ---------------------------------------------------------------------

    /**
     * 按 PluginId + key 取翻译（使用 {@link I18nManager#getCurrentLocale()}）。
     * 命中命名空间 → 命中全局 → 走默认 locale → 仍无则返回 key。
     */
    public static String get(PluginId pluginId, String key) {
        String locale = I18nManager.getCurrentLocale();
        return get(pluginId, locale, key);
    }

    /**
     * 按 PluginId + key + 占位符参数取翻译（使用当前全局 locale）。
     *
     * @param args 替换 {@code {0}}、{@code {1}} 等占位符
     */
    public static String get(PluginId pluginId, String key, Object... args) {
        String template = get(pluginId, key);
        if (args == null || args.length == 0) {
            return template;
        }
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    }

    /**
     * 按 PluginId + 显式 locale + key 取翻译。
     * 不会回退到当前全局 locale，但会回退到 {@link I18nManager} 全局翻译表与默认 locale。
     */
    public static String get(PluginId pluginId, String locale, String key) {
        if (key == null) return null;
        String normalized = normalizeLocale(locale);

        // 1) 命名空间 + 指定 locale
        String value = lookup(pluginId, normalized, key);
        if (value != null) return value;

        // 2) 全局 I18nManager + 指定 locale
        value = I18nManagerFallback.lookup(normalized, key);
        if (value != null) return value;

        // 3) 命名空间 + 默认 locale
        if (!DEFAULT_LOCALE.equals(normalized)) {
            value = lookup(pluginId, DEFAULT_LOCALE, key);
            if (value != null) return value;

            // 4) 全局 I18nManager + 默认 locale
            value = I18nManagerFallback.lookup(DEFAULT_LOCALE, key);
            if (value != null) return value;
        }

        return key;
    }

    /**
     * 按 PluginId + 显式 locale + key + 占位符参数取翻译。
     */
    public static String get(PluginId pluginId, String locale, String key, Object... args) {
        String template = get(pluginId, locale, key);
        if (args == null || args.length == 0) {
            return template;
        }
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    }

    /**
     * 当前全局 locale 下，命名空间表（或全局兜底层）是否有该 key。
     */
    public static boolean has(PluginId pluginId, String key) {
        if (key == null) return false;
        String locale = I18nManager.getCurrentLocale();
        if (lookup(pluginId, locale, key) != null) return true;
        if (I18nManagerFallback.lookup(locale, key) != null) return true;
        if (!DEFAULT_LOCALE.equals(locale)) {
            if (lookup(pluginId, DEFAULT_LOCALE, key) != null) return true;
            if (I18nManagerFallback.lookup(DEFAULT_LOCALE, key) != null) return true;
        }
        return false;
    }

    /**
     * 列出已注册（至少有 1 个 locale）的 PluginId。
     */
    public static Set<PluginId> registeredPluginIds() {
        return Collections.unmodifiableSet(NAMESPACES.keySet());
    }

    /**
     * 清空所有命名空间与懒加载标记。
     *
     * <p>仅供单元测试使用 —— 调用方负责确保在无其他线程并发访问时调用。
     * 生产代码不应调用此方法：插件注册表是按生命周期持续持有的状态，
     * 随意清理会让已经渲染过的翻译重新走回退路径。
     */
    static void clearForTesting() {
        NAMESPACES.clear();
        LOAD_ATTEMPTED.clear();
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    private static ConcurrentHashMap<String, Map<String, String>> namespaceFor(PluginId pluginId) {
        return NAMESPACES.computeIfAbsent(pluginId, k -> new ConcurrentHashMap<>());
    }

    private static ConcurrentHashMap<String, Boolean> attempted(PluginId pluginId, String locale) {
        return LOAD_ATTEMPTED.computeIfAbsent(pluginId, k -> new ConcurrentHashMap<>());
    }

    private static String lookup(PluginId pluginId, String locale, String key) {
        if (pluginId == null) return null;
        ConcurrentHashMap<String, Map<String, String>> ns = NAMESPACES.get(pluginId);
        if (ns == null) {
            // 尝试 classpath 懒加载
            tryLazyLoad(pluginId, locale);
            ns = NAMESPACES.get(pluginId);
            if (ns == null) return null;
        }
        Map<String, String> map = ns.get(locale);
        if (map == null) {
            tryLazyLoad(pluginId, locale);
            map = ns.get(locale);
            if (map == null) return null;
        }
        return map.get(key);
    }

    /**
     * 懒加载 classpath 上 {@code /i18n/<pluginId>.<locale>.properties}。
     * 只在首次按 pluginId 取该 locale 时触发；找不到也只尝试一次。
     */
    private static void tryLazyLoad(PluginId pluginId, String locale) {
        ConcurrentHashMap<String, Boolean> flag = attempted(pluginId, locale);
        if (flag.putIfAbsent(locale, Boolean.TRUE) != null) {
            return; // 已经尝试过
        }
        String path = "/i18n/" + pluginId.value() + "." + locale + ".properties";
        try (InputStream is = PluginI18n.class.getResourceAsStream(path)) {
            if (is == null) {
                return; // 文件不存在，静默忽略（命名空间为空是合法状态）
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            registerTranslations(pluginId, locale, props);
        } catch (IOException e) {
            LOGGER.warn("[PluginI18n] Failed to lazy-load {} for {}: {}", path, pluginId.value(), e.toString());
        }
    }

    private static String normalizeLocale(String locale) {
        return locale == null ? DEFAULT_LOCALE : locale.toLowerCase(Locale.ROOT);
    }

    /**
     * 内部桥接：访问 {@link I18nManager} 持有的全局翻译表（同名包内可见）。
     *
     * <p>这是一个嵌套的 helper，把对 {@code I18nManager.translations} 的访问
     * 收敛到一个文件，避免直接扩大 {@code I18nManager} 的对外 API 表面。
     * 如果未来需要更细粒度的耦合，可以把这段逻辑直接合并到 {@link I18nManager} 中。
     */
    static final class I18nManagerFallback {
        private I18nManagerFallback() {}

        static String lookup(String locale, String key) {
            Map<String, String> map = I18nManager.getTranslations(locale);
            return map == null ? null : map.get(key);
        }
    }
}