package fun.bm.mili.lmili.api;

import fun.bm.mili.lmili.api.identity.DefaultPluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginIdentity;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginRuntimeContext;
import fun.bm.mili.lmili.api.observability.LongTailEvent;
import fun.bm.mili.lmili.api.observability.PluginSchedulerCapture;
import fun.bm.mili.lmili.api.observability.SchedulerMetrics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Top-level entry point to the LMili runtime. Plugin authors access the
 * identity system via {@code LMili.getPluginIdentityManager()} and
 * {@code LMili.getRuntimeContext(id)}.
 *
 * <p>The manager is process-wide and lives for the lifetime of the JVM.
 * Tests should call {@link #resetForTests()} between scenarios.</p>
 *
 * <p>§15 / §11 / §12 P2 公开 API（观测面）：
 * <ul>
 *   <li>{@link #schedulerMetrics()} —— §15 baseline 全部指标（mspt p95/p99/max、worker 利用率、阻塞、deferred、chunk wait…）</li>
 *   <li>{@link #longTailEvents()} —— §12 长尾事件流（监听器 + 最近事件查询）</li>
 *   <li>{@link #jmxObjectName()} —— {@code fun.bm.mili:type=SchedulerMetrics} MXBean 名，Spark/VisualVM 可直接查</li>
 * </ul>
 */
public final class LMili {

    /** System owner id used for tasks that originate in LMili itself (bootstrap, etc.). */
    public static final PluginId SYSTEM_OWNER_ID = PluginId.parse("lmili.system");

    /** LMili JMX ObjectName 前缀（§11 P2-2 / Spark integration） */
    public static final String JMX_DOMAIN = "fun.bm.mili";

    private static final AtomicReference<PluginIdentityManager> MANAGER_REF =
            new AtomicReference<>(new DefaultPluginIdentityManager());

    /**
     * Per-thread "current task owner" used by the scheduler adapter to stamp
     * every submitted task with its owning PluginId (V2 §18).
     */
    private static final ThreadLocal<PluginId> CURRENT_OWNER = new ThreadLocal<>();

    /** §15 SchedulerMetrics 公开访问（server-side install；plugin 端只读） */
    private static final AtomicReference<SchedulerMetrics> METRICS_REF = new AtomicReference<>();

    /** §12 LongTailEvent 公开访问（server-side install；plugin 端只读） */
    private static final AtomicReference<LongTailEvent> LONGT_REF = new AtomicReference<>();

    /** PluginSchedulerCapture 公开访问（server-side install；plugin 端写入） */
    private static final AtomicReference<PluginSchedulerCapture> CAPTURE_REF = new AtomicReference<>();

    private LMili() {}

    @NotNull
    public static PluginIdentityManager getPluginIdentityManager() {
        return MANAGER_REF.get();
    }

    /**
     * Replace the manager. Server-side only — used to install a configured
     * implementation during bootstrap. Subsequent calls to
     * {@link #getPluginIdentityManager()} return the new instance.
     */
    public static void setPluginIdentityManager(@NotNull final PluginIdentityManager mgr) {
        MANAGER_REF.set(mgr);
    }

    /**
     * Look up a runtime context by id. Convenience wrapper around
     * {@code LMili.getPluginIdentityManager().find(id)}.
     */
    @NotNull
    public static Optional<PluginIdentity> getIdentity(@NotNull final PluginId id) {
        return MANAGER_REF.get().find(id);
    }

    /**
     * Look up the runtime context for a plugin id. The runtime context bundles
     * the identity with scheduler domain, permission, quota and observability.
     *
     * <p>The runtime builds one context per registered plugin during bootstrap.
     * This method first checks the {@link PluginRuntimeContext#forPluginId}
     * index (the authoritative live context), then falls back to building a
     * fresh context from the identity record.</p>
     *
     * @return the live registered context, or a fresh one from the identity,
     *         or {@code null} if the id is unknown.
     */
    @Nullable
    public static PluginRuntimeContext getRuntimeContext(@NotNull final PluginId id) {
        // 1. Live registered context (authoritative counters, domain, lifecycle).
        final PluginRuntimeContext live = PluginRuntimeContext.forPluginId(id);
        if (live != null) return live;

        // 2. Fallback: build a fresh context from the identity record.
        final PluginIdentity identity = MANAGER_REF.get().find(id).orElse(null);
        if (identity == null) return null;
        return PluginRuntimeContext.forIdentity(identity);
    }

    /**
     * Reset the runtime to a fresh manager. Intended for tests.
     */
    public static void resetForTests() {
        MANAGER_REF.set(new DefaultPluginIdentityManager());
        METRICS_REF.set(null);
        LONGT_REF.set(null);
        CAPTURE_REF.set(null);
        I18N_REF.set(null);
    }

    /**
     * Bind the current thread's task owner to {@code id}. Tasks submitted via
     * {@code Mili.scheduler()} inside this thread will carry this id as the
     * owner. Pair with {@link #clearCurrentOwner()} in a finally block.
     */
    public static void bindCurrentOwner(@NotNull final PluginId id) {
        CURRENT_OWNER.set(id);
    }

    public static void clearCurrentOwner() {
        CURRENT_OWNER.remove();
    }

    /**
     * @return the current thread's bound owner, or {@link #SYSTEM_OWNER_ID} if none.
     */
    @NotNull
    public static PluginId currentOwnerOrSystem() {
        final PluginId id = CURRENT_OWNER.get();
        return id != null ? id : SYSTEM_OWNER_ID;
    }

    /**
     * @return the current thread's bound owner, or {@code null} if none.
     */
    @Nullable
    public static PluginId currentOwner() {
        return CURRENT_OWNER.get();
    }

    // ================== §11 / §12 / §15 观测面 API ==================

    /**
     * 当前 SchedulerMetrics（server-side install；runtime 未装配返回 null）。
     *
     * <p>Spark profile UI、VisualVM、JMX 客户端通过 {@link #jmxObjectName()} 直接拿同一份数据。
     */
    @Nullable
    public static SchedulerMetrics schedulerMetrics() {
        return METRICS_REF.get();
    }

    /**
     * 当前 LongTailEvent 流（server-side install；runtime 未装配返回 null）。
     */
    @Nullable
    public static LongTailEvent longTailEvents() {
        return LONGT_REF.get();
    }

    /**
     * LMili JMX ObjectName（{@code fun.bm.mili:type=SchedulerMetrics}）。
     *
     * <p>外部 JMX 客户端（包括 Spark 通过 {@code jmx.connect} 查询时）可用此 ObjectName
     * 直接读取 §15 全部指标。
     */
    @NotNull
    public static String jmxObjectName() {
        return JMX_DOMAIN + ":type=SchedulerMetrics";
    }

    /** server-side：装配 SchedulerMetrics（幂等） */
    public static void installSchedulerMetrics(@NotNull final SchedulerMetrics m) {
        METRICS_REF.set(m);
    }

    /** server-side：装配 LongTailEvent 流（幂等） */
    public static void installLongTailEvents(@NotNull final LongTailEvent e) {
        LONGT_REF.set(e);
    }

    /** server-side：装配 PluginSchedulerCapture（幂等） */
    public static void installSchedulerCapture(@NotNull final PluginSchedulerCapture c) {
        CAPTURE_REF.set(c);
    }

    /**
     * plugin 端：拿到当前 PluginSchedulerCapture；用于 spark 等外部 plugin 主动
     * 报告自己的工作量。未安装时返回 null（plugin 可正常启动，仅失去 LMili 观测能力）。
     */
    @Nullable
    public static PluginSchedulerCapture schedulerCapture() {
        return CAPTURE_REF.get();
    }

    /**
     * plugin 端：便捷判断 LMili 观测能力是否就绪。
     */
    public static boolean isCaptureAvailable() {
        return CAPTURE_REF.get() != null;
    }

    // ================== §11 P2-3 Plugin i18n 适配 API ==================

    /**
     * 当前服务器 locale（如 {@code "zh_cn"}、{@code "en_us"}）。plugin 可以直接读
     * 来决定 UI 文案，无需 import Bukkit {@code Player.locale()}。未装配时返回默认
     * {@code "en_us"}。
     */
    @NotNull
    public static String currentLocale() {
        return getCurrentLocale();
    }

    /**
     * 取 LMili 全局翻译（来自 {@code resources/i18n/<locale>.properties}）。
     * 命中 locale → 命中默认 locale → 返回 key 字符串本身。
     *
     * <p>plugin 应优先用 {@link #t(PluginId, String)} 或
     * {@link #t(PluginId, String, Object...})} 以使用 plugin 命名空间的翻译表。
     *
     * @param key 翻译键
     * @param args 占位符（{0}、{1}…）
     */
    @NotNull
    public static String t(@NotNull String key, Object... args) {
        return tr(key, args);
    }

    /**
     * 按 PluginId + key 取 plugin 命名空间翻译（使用当前 locale）。
     * 查找顺序：命名空间+locale → 命名空间+默认 → 全局+locale → 全局+默认 → key 本身。
     *
     * <p>这是 plugin 接入 LMili i18n 的推荐入口 —— 让 plugin 可以携带自己的
     * {@code i18n/<pluginId>.<locale>.properties}，与 LMili 全局翻译共存。
     */
    @NotNull
    public static String t(@NotNull PluginId id, @NotNull String key, Object... args) {
        return tr(id, key, args);
    }

    /**
     * plugin 主动注册一份翻译（来自 {@link Properties}）。
     *
     * <p>等价于 {@code fun.bm.mili.lmili.i18n.PluginI18n.registerTranslations(...)}
     * 但放在 plugin-facing API 包里，避免 plugin 引入 server-only 类。
     */
    public static void registerTranslations(@NotNull PluginId id, @NotNull String locale,
                                            @NotNull Properties props) {
        registerTranslationsImpl(id, locale, props);
    }

    /**
     * plugin 主动注册一份翻译（来自 classpath {@code /i18n/<id>.<locale>.properties}）。
     * 文件不存在时静默忽略。
     */
    public static void registerTranslations(@NotNull PluginId id, @NotNull String locale,
                                            @NotNull ClassLoader loader) {
        registerTranslationsImpl(id, locale, loader);
    }

    /**
     * 列出当前 server 上所有已加载 locale（如 {@code ["zh_cn","en_us","ja_jp"]}）。
     * plugin 可用于 i18n 自检。
     */
    @NotNull
    public static java.util.Set<String> availableLocales() {
        return availableLocalesImpl();
    }

    // ---------------------------------------------------------------------
    // 内部委托方法（server 端通过 ServiceLoader / 静态初始化注入实现）。
    // 不暴露 SPI 防止 plugin 误实现。
    // ---------------------------------------------------------------------

    @FunctionalInterface
    public interface I18nFunctions {
        String getCurrentLocale();
        String tr(String key, Object[] args);
        String trNs(PluginId id, String key, Object[] args);
        void registerProps(PluginId id, String locale, Properties props);
        void registerClasspath(PluginId id, String locale, ClassLoader loader);
        java.util.Set<String> availableLocales();
    }

    private static final AtomicReference<I18nFunctions> I18N_REF = new AtomicReference<>();

    /** server-side：注入 i18n 实现（幂等）。 */
    public static void installI18n(@NotNull I18nFunctions fns) {
        I18N_REF.set(fns);
    }

    private static String getCurrentLocale() {
        final I18nFunctions f = I18N_REF.get();
        return f != null ? f.getCurrentLocale() : "en_us";
    }

    private static String tr(String key, Object[] args) {
        final I18nFunctions f = I18N_REF.get();
        if (f == null) return applyArgs(key, args);
        return f.tr(key, args); // f.tr 内部已处理 args
    }

    private static String tr(PluginId id, String key, Object[] args) {
        final I18nFunctions f = I18N_REF.get();
        if (f == null) return applyArgs(key, args);
        return f.trNs(id, key, args); // f.trNs 内部已处理 args
    }

    private static void registerTranslationsImpl(PluginId id, String locale, Properties props) {
        final I18nFunctions f = I18N_REF.get();
        if (f != null) f.registerProps(id, locale, props);
    }

    private static void registerTranslationsImpl(PluginId id, String locale, ClassLoader loader) {
        final I18nFunctions f = I18N_REF.get();
        if (f != null) f.registerClasspath(id, locale, loader);
    }

    private static java.util.Set<String> availableLocalesImpl() {
        final I18nFunctions f = I18N_REF.get();
        return f != null ? f.availableLocales() : java.util.Set.of("en_us");
    }

    private static String applyArgs(String template, Object[] args) {
        if (template == null || args == null || args.length == 0) return template;
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    }

    /** 规范化 locale（lowercase） */
    public static String normalizeLocale(String locale) {
        return locale == null ? "en_us" : locale.toLowerCase(Locale.ROOT);
    }
}