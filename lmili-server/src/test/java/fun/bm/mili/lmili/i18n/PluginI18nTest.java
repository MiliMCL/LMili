package fun.bm.mili.lmili.i18n;

import fun.bm.mili.lmili.api.identity.PluginId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单元测试：{@link PluginI18n} —— 按 PluginId 命名空间隔离 + 全局 I18nManager 兜底。
 *
 * <p>测试期间会反复切换全局 locale，避免污染既有 {@link I18nManager} 状态；
 * 每个 case 都会在 {@code @AfterEach} 里把 locale 还原回 {@code en_us}。
 */
class PluginI18nTest {

    private static final PluginId PLUGIN_ID = PluginId.parse("xucy.mili");
    private static final PluginId ANOTHER_PLUGIN_ID = PluginId.parse("other.plugin");

    private String previousLocale;

    @BeforeEach
    void saveLocale() {
        previousLocale = I18nManager.getCurrentLocale();
        // 清空上一个测试的命名空间注册，避免状态泄漏
        PluginI18n.clearForTesting();
    }

    @AfterEach
    void restoreLocale() {
        I18nManager.setLocale(previousLocale == null ? "en_us" : previousLocale);
    }

    // ---------------------------------------------------------------------
    // 注册 → 取值 → 命中
    // ---------------------------------------------------------------------

    @Test
    void registerAndLookupHitsNamespace() throws IOException {
        Properties zh = props("greeting.hello=你好 xucy", "welcome.user=欢迎 {0}");
        PluginI18n.registerTranslations(PLUGIN_ID, "zh_cn", zh);

        I18nManager.setLocale("zh_cn");
        assertEquals("你好 xucy", PluginI18n.get(PLUGIN_ID, "greeting.hello"));
    }

    @Test
    void registerAndLookupHitsNamespaceEnglishLocale() throws IOException {
        Properties en = props("greeting.hello=hello xucy");
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us", en);

        I18nManager.setLocale("en_us");
        assertEquals("hello xucy", PluginI18n.get(PLUGIN_ID, "greeting.hello"));
    }

    // ---------------------------------------------------------------------
    // 占位符
    // ---------------------------------------------------------------------

    @Test
    void placeholderReplacementUsesZeroIndexedTokens() throws IOException {
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us",
                props("welcome.user=Welcome, {0}! You have {1} messages."));

        I18nManager.setLocale("en_us");
        assertEquals("Welcome, Alice! You have 3 messages.",
                PluginI18n.get(PLUGIN_ID, "welcome.user", "Alice", 3));
    }

    @Test
    void placeholderWorksWithExplicitLocale() throws IOException {
        PluginI18n.registerTranslations(PLUGIN_ID, "zh_cn",
                props("welcome.user=欢迎 {0}"));

        assertEquals("欢迎 Bob", PluginI18n.get(PLUGIN_ID, "zh_cn", "welcome.user", "Bob"));
    }

    @Test
    void noArgsLeavesPlaceholdersUntouched() throws IOException {
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us",
                props("welcome.user=Welcome, {0}!"));

        I18nManager.setLocale("en_us");
        // 不传 args → 占位符原样保留（与现有 I18nManager 行为一致）
        assertEquals("Welcome, {0}!", PluginI18n.get(PLUGIN_ID, "welcome.user"));
    }

    // ---------------------------------------------------------------------
    // 回退：未命中 → 走全局 I18nManager 兜底
    // ---------------------------------------------------------------------

    @Test
    void missingKeyFallsBackToGlobalI18nManager() {
        // 全局 zh_cn.properties 含 tps.server.health.report=服务器健康报告
        I18nManager.setLocale("zh_cn");
        assertEquals("服务器健康报告", PluginI18n.get(PLUGIN_ID, "tps.server.health.report"));
    }

    @Test
    void missingKeyFallsBackToGlobalEnglishWhenLocaleIsOther() {
        // I18nManager.setLocale 只接受已加载的 locale；"zz_zz" 未加载 → 当前 locale 保持不变。
        // 即便如此：用 3-arg 显式查询一个非默认 locale，仍然应当回退到全局 en_us 兜底。
        assertEquals("Server Health Report", PluginI18n.get(PLUGIN_ID, "zz_zz", "tps.server.health.report"));
    }

    // ---------------------------------------------------------------------
    // 都没命中 → 返回 key 字符串
    // ---------------------------------------------------------------------

    @Test
    void completelyUnknownKeyReturnsKeyItself() {
        I18nManager.setLocale("en_us");
        assertEquals("this.key.does.not.exist",
                PluginI18n.get(PLUGIN_ID, "this.key.does.not.exist"));
    }

    // ---------------------------------------------------------------------
    // 不存在的 PluginId 调用 get 不会抛 NPE
    // ---------------------------------------------------------------------

    @Test
    void unknownPluginIdReturnsKeyWithoutNpe() {
        PluginId never = PluginId.parse("never.used.plugin");
        // 命名空间里没注册过；命中全局兜底；还没命中 → 返回 key
        assertEquals("absent.key", PluginI18n.get(never, "absent.key"));
        // has 同样安全
        assertFalse(PluginI18n.has(never, "absent.key"));
    }

    @Test
    void nullKeyReturnsNull() {
        // 现有 I18nManager 的语义：get("missing") → "missing"；get(null) 未定义。
        // PluginI18n 显式保护：null key → null（避免 NPE 给上游）。
        assertEquals(null, PluginI18n.get(PLUGIN_ID, (String) null));
        assertEquals(null, PluginI18n.get(PLUGIN_ID, "en_us", null));
        assertFalse(PluginI18n.has(PLUGIN_ID, null));
    }

    // ---------------------------------------------------------------------
    // 切换 locale 后翻译正确
    // ---------------------------------------------------------------------

    @Test
    void switchingLocaleResolvesToCorrectTranslation() throws IOException {
        PluginI18n.registerTranslations(PLUGIN_ID, "zh_cn",
                props("greeting=你好"));
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us",
                props("greeting=Hello"));

        I18nManager.setLocale("zh_cn");
        assertEquals("你好", PluginI18n.get(PLUGIN_ID, "greeting"));

        I18nManager.setLocale("en_us");
        assertEquals("Hello", PluginI18n.get(PLUGIN_ID, "greeting"));
    }

    @Test
    void namespaceTakesPrecedenceOverGlobalFallback() throws IOException {
        // 全局 en_us.properties 有 tps.server.health.report=Server Health Report
        // 但 PLUGIN_ID 在 en_us 下有自己的同名 key → 应该返回命名空间版本
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us",
                props("tps.server.health.report=Custom Plugin Health"));

        I18nManager.setLocale("en_us");
        assertEquals("Custom Plugin Health", PluginI18n.get(PLUGIN_ID, "tps.server.health.report"));
        // 全局 API 不受影响
        assertEquals("Server Health Report", I18nManager.get("tps.server.health.report"));
    }

    // ---------------------------------------------------------------------
    // 多 PluginId 隔离
    // ---------------------------------------------------------------------

    @Test
    void namespacesAreIsolatedBetweenPluginIds() throws IOException {
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us", props("greeting=Hello from xucy"));
        PluginI18n.registerTranslations(ANOTHER_PLUGIN_ID, "en_us", props("greeting=Hello from other"));

        I18nManager.setLocale("en_us");
        assertEquals("Hello from xucy", PluginI18n.get(PLUGIN_ID, "greeting"));
        assertEquals("Hello from other", PluginI18n.get(ANOTHER_PLUGIN_ID, "greeting"));
    }

    // ---------------------------------------------------------------------
    // 注册方式（4 个重载）
    // ---------------------------------------------------------------------

    @Test
    void registerFromInputStreamWorks() throws IOException {
        String data = "greeting=Hello from stream";
        try (InputStream in = new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8))) {
            PluginI18n.registerTranslations(PLUGIN_ID, "en_us", in);
        }
        I18nManager.setLocale("en_us");
        assertEquals("Hello from stream", PluginI18n.get(PLUGIN_ID, "greeting"));
    }

    @Test
    void registerFromReaderWorks() throws IOException {
        try (StringReader reader = new StringReader("greeting=Hello from reader")) {
            PluginI18n.registerTranslations(PLUGIN_ID, "en_us", reader);
        }
        I18nManager.setLocale("en_us");
        assertEquals("Hello from reader", PluginI18n.get(PLUGIN_ID, "greeting"));
    }

    @Test
    void registerMergesPropertiesUnderSameKey() throws IOException {
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us", props("a=1"));
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us", props("b=2"));
        I18nManager.setLocale("en_us");
        assertEquals("1", PluginI18n.get(PLUGIN_ID, "a"));
        assertEquals("2", PluginI18n.get(PLUGIN_ID, "b"));
    }

    @Test
    void registerOverwritesSameKey() throws IOException {
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us", props("greeting=first"));
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us", props("greeting=second"));
        I18nManager.setLocale("en_us");
        assertEquals("second", PluginI18n.get(PLUGIN_ID, "greeting"));
    }

    // ---------------------------------------------------------------------
    // 工具类 API
    // ---------------------------------------------------------------------

    @Test
    void registeredPluginIdsReflectsRegistrations() throws IOException {
        // 在用例内用一组临时 pluginId，避免依赖全局状态
        PluginId a = PluginId.parse("alpha.beta");
        PluginId b = PluginId.parse("gamma.delta");
        PluginI18n.registerTranslations(a, "en_us", props("k=v"));
        PluginI18n.registerTranslations(b, "en_us", props("k=v"));

        assertTrue(PluginI18n.registeredPluginIds().contains(a));
        assertTrue(PluginI18n.registeredPluginIds().contains(b));
    }

    @Test
    void hasReturnsTrueForKnownNamespaceKey() throws IOException {
        PluginI18n.registerTranslations(PLUGIN_ID, "en_us", props("known.key=value"));
        I18nManager.setLocale("en_us");
        assertTrue(PluginI18n.has(PLUGIN_ID, "known.key"));
    }

    @Test
    void hasReturnsTrueForGlobalFallbackKey() {
        // 全局 en_us.properties 含 tps.server.health.report
        I18nManager.setLocale("en_us");
        assertTrue(PluginI18n.has(PLUGIN_ID, "tps.server.health.report"));
    }

    @Test
    void hasReturnsFalseForUnknownKey() {
        I18nManager.setLocale("en_us");
        assertFalse(PluginI18n.has(PLUGIN_ID, "absent.key"));
    }

    @Test
    void localeNormalizationIsCaseInsensitive() throws IOException {
        PluginI18n.registerTranslations(PLUGIN_ID, "EN_US", props("greeting=Hello"));
        I18nManager.setLocale("en_us");
        // 用大写 locale 查询也应该命中
        assertEquals("Hello", PluginI18n.get(PLUGIN_ID, "EN_US", "greeting"));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (String entry : kv) {
            int eq = entry.indexOf('=');
            assertTrue(eq > 0, "bad fixture: " + entry);
            p.setProperty(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return p;
    }
}