package fun.bm.mili.lmili.runtime;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.function.LanguageConfig;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.data.OptimizedLinearRegionFile;
import fun.bm.mili.lmili.i18n.I18nManager;
import fun.bm.mili.lmili.i18n.PluginI18n;
import fun.bm.mili.lmili.runtime.io.FlusherObserver;
import fun.bm.mili.lmili.runtime.io.OLinearFlusherBridge;
import fun.bm.mili.lmili.thread.scheduler.MiliSchedulerHolder;
import fun.bm.mili.lmili.utils.OptimizedLinearRegionFileFlusher;
import org.slf4j.Logger;

/**
 * 装配期引导（RegionFormatConfig 回调入口，§5.6 / D-08）。
 *
 * <p><strong>延迟装配语义</strong>：RegionFormatConfig.onLoaded 构造 flusher 时
 * （可能早于调度器就绪）只做两件事：
 * <ol>
 *   <li>{@link #createFlusherObserver()} —— 返回延迟转发观察者（runtime 未就绪时 no-op）；</li>
 *   <li>{@link #onFlusherCreated(OptimizedLinearRegionFileFlusher)} —— flusher 创建后
 *       晚绑定（attachFlusher + scheduler.bind + Bukkit 权限检查器）。</li>
 * </ol>
 * 不在构造期直接 MiliRuntimeHolder.getOrCreate()（避免装配顺序竞态）。
 */
public final class RuntimeBootstrap {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean bukkitPermissionCheckerInstalled = false;

    private RuntimeBootstrap() {
    }

    /** flusher 构造期回调（RegionFormatConfig.onLoaded 调用；幂等） */
    public static void onFlusherCreated(OptimizedLinearRegionFileFlusher flusher) {
        if (flusher == null) {
            return;
        }
        try {
            final MiliRuntime rt = MiliRuntimeHolder.getOrCreate();
            rt.io().attachFlusher(flusher);
            rt.bindScheduler(MiliSchedulerHolder.get());
            installBukkitPermissionChecker(rt);
            installObservability();
            LOGGER.info("[RuntimeBootstrap] runtime attached to flusher ({} io threads)", flusher.getIoThreadCount());
        } catch (Throwable t) {
            // 装配失败不阻断主服务器启动（控制面降级为不可用，数据面不受影响）
            LOGGER.warn("[RuntimeBootstrap] runtime attach failed (control plane degraded)", t);
        }
    }

    /**
     * §11 P2-2 / §15 观测面装配：
     * <ul>
     *   <li>{@link fun.bm.mili.lmili.observability.PluginSchedulerCaptureImpl} —— 让 spark
     *       等外部 plugin 能声明式上报 task 数到 LMili ResourceQuota（修复
     *       {@code /pluginid status spark} 显示 0 的问题）</li>
     *   <li>{@link fun.bm.mili.lmili.observability.AutoSchedulerCapture} —— Bukkit listener，
     *       每 5 秒采样一次 Bukkit Scheduler pending tasks，自动把走 BukkitScheduler
     *       的外部 plugin 工作量回填到 LMili</li>
     *   <li>JMX 注册（{@link fun.bm.mili.lmili.observability.JmxRegistrar}）—— Spark/VisualVM
     *       通过 {@code fun.bm.mili:type=SchedulerMetrics} 读 §15 指标</li>
     *   <li>{@link fun.bm.mili.lmili.observability.threading.LmiliThreadingBackend} —— plugin-facing
     *       统一线程管理 API（{@code Threading.executor / scheduler / scope / miliScheduler}），
     *       复用 {@link fun.bm.mili.lmili.thread.scheduler.execute.VirtualThreadPool} 和
     *       {@link fun.bm.mili.lmili.thread.scheduler.MiliSchedulerHolder}（§18.1 优先复用）</li>
     * </ul>
     *
     * <p>幂等；任何一步失败仅日志，不阻断主流程（§6.2 fail-safe）。
     */
    private static volatile boolean observabilityInstalled = false;
    public static void installObservability() {
        if (observabilityInstalled) return;
        observabilityInstalled = true;
        try {
            // 0. I18n —— 必须先于所有命令 / 日志（确保 /pluginid 等输出按用户语言）
            //     注意：MiliOptimizations.init() 是死代码（无人调用），这里补上
            try {
                I18nManager.init(LanguageConfig.lang);
            } catch (Throwable ignored) {
                // 装配失败时静默（用默认 locale en_us）
            }

            // 0.1 Plugin-facing i18n SPI —— 把 LMili.i18n.* 桥接到 I18nManager / PluginI18n
            try {
                LMili.installI18n(new LMili.I18nFunctions() {
                    @Override public String getCurrentLocale() {
                        return I18nManager.getCurrentLocale();
                    }
                    @Override public String tr(String key, Object[] args) {
                        return I18nManager.get(key, args == null ? new Object[0] : args);
                    }
                    @Override public String trNs(PluginId id, String key, Object[] args) {
                        return PluginI18n.get(id, key, args == null ? new Object[0] : args);
                    }
                    @Override public String trForPlayer(PluginId id, String playerLocale, String key, Object[] args) {
                        if (playerLocale == null || playerLocale.isEmpty()) {
                            return PluginI18n.get(id, key, args == null ? new Object[0] : args);
                        }
                        // 把 Bukkit locale（zh_cn / en_us / zh-CN）转成 properties 文件名（zh_cn）
                        String norm = playerLocale.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
                        // 优先 player locale，否则 current
                        return PluginI18n.get(id, norm, key, args == null ? new Object[0] : args);
                    }
                    @Override public void registerProps(PluginId id, String locale, java.util.Properties props) {
                        PluginI18n.registerTranslations(id, locale, props);
                    }
                    @Override public void registerClasspath(PluginId id, String locale, ClassLoader loader) {
                        try {
                            String path = "/i18n/" + id.value() + "." + locale + ".properties";
                            try (java.io.InputStream is = loader.getResourceAsStream(path)) {
                                if (is == null) return;
                                java.util.Properties props = new java.util.Properties();
                                props.load(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                                PluginI18n.registerTranslations(id, locale, props);
                            }
                        } catch (Throwable ignored) {}
                    }
                    @Override public java.util.Set<String> availableLocales() {
                        return new java.util.HashSet<>(java.util.Arrays.asList(
                                "en_us","zh_cn","zh_tw","zh_hk","ja_jp","ko_kr","de_de","fr_fr",
                                "es_es","it_it","pt_br","ru_ru","uk_ua","pl_pl","tr_tr","vi_vn",
                                "th_th","id_id","ar_sa"));
                    }
                });
            } catch (Throwable ignored) {}

            // 1. Capture（spark 接入入口）
            new fun.bm.mili.lmili.observability.PluginSchedulerCaptureImpl();

            // 2. Threading backend（plugin-facing 统一线程管理 API）
            new fun.bm.mili.lmili.observability.threading.LmiliThreadingBackend();

            // 3. MetricsRegistry（plugin 自定义指标注册表）
            try {
                LMili.installMetricsRegistry(
                        new fun.bm.mili.lmili.observability.PluginMetricsRegistryImpl());
                // 自动清理：plugin unregister 时把它的 metrics 清空 + event bus 退订
                LMili.pluginLifecycleBus().register(LMili.SYSTEM_OWNER_ID,
                    new fun.bm.mili.lmili.api.identity.PluginLifecycleBus.Listener() {
                        @Override
                        public void onUnregistered(PluginId id) {
                            fun.bm.mili.lmili.api.observability.MetricsRegistry r =
                                    LMili.metricsRegistry();
                            if (r != null) r.unregisterAll(id);
                            fun.bm.mili.lmili.api.event.PluginEventBus bus = LMili.eventBus();
                            if (bus != null) bus.unsubscribeAll(id);
                        }
                    });
            } catch (Throwable t) {
                LOGGER.warn("[RuntimeBootstrap] MetricsRegistry install failed", t);
            }

            // 4. identity manager → lifecycle bus wiring
            //     桥接：每次 register / setStatus / unregister 时 fire 到 bus
            try {
                wireIdentityToBus();
            } catch (Throwable t) {
                LOGGER.warn("[RuntimeBootstrap] identity→bus wire failed", t);
            }

            LOGGER.info("[RuntimeBootstrap] observability primitives installed (capture / threading / JMX / MetricsRegistry / lifecycle bus)");
        } catch (Throwable t) {
            LOGGER.warn("[RuntimeBootstrap] observability install failed", t);
        }
    }

    /**
     * 把 LMili 默认 {@link fun.bm.mili.lmili.api.identity.DefaultPluginIdentityManager}
     * 包成"会 fire lifecycle bus"的事件源。
     *
     * <p>实现：保留原 manager 不变，注册一个 listener 在每次 register / setStatus /
     * unregister 后调 fire* 方法。listener 自身用 last-seen 状态对比避免重复触发。
     */
    private static volatile boolean busWired = false;
    private static void wireIdentityToBus() {
        if (busWired) return;
        busWired = true;
        final fun.bm.mili.lmili.api.identity.PluginIdentityManager mgr = LMili.getPluginIdentityManager();
        final fun.bm.mili.lmili.api.identity.PluginLifecycleBus bus = LMili.pluginLifecycleBus();
        if (mgr == null || bus == null) return;

        // last seen snapshot —— 用于检测 register / setStatus
        final java.util.Map<PluginId, fun.bm.mili.lmili.api.identity.PluginStatus> lastSeen =
                new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Set<PluginId> known = java.util.concurrent.ConcurrentHashMap.newKeySet();

        // 仅 fire（包装型 manager 由 plugin-bootstrap 在 register 时手动 fire；这里负责监听
        // "system-level" 修改（命令 / API 调用）。每 30 秒做一次轻量 reconcile。）
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LMili-lifecycle-reconcile");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> {
            try {
                for (fun.bm.mili.lmili.api.identity.PluginIdentity p : mgr.getAll()) {
                    final PluginId id = p.id();
                    if (!known.contains(id)) {
                        known.add(id);
                        lastSeen.put(id, p.status());
                        bus.fireRegistered(p);
                    } else {
                        fun.bm.mili.lmili.api.identity.PluginStatus prev = lastSeen.get(id);
                        if (prev != null && prev != p.status()) {
                            lastSeen.put(id, p.status());
                            bus.fireStatusChange(p, prev);
                        }
                    }
                }
                // 检测 unregister（id 在 mgr 消失但 known 里还有）
                for (PluginId id : new java.util.ArrayList<>(known)) {
                    if (mgr.find(id).isEmpty()) {
                        known.remove(id);
                        lastSeen.remove(id);
                        bus.fireUnregistered(id);
                    }
                }
            } catch (Throwable t) {
                // §6.2 fail-safe
            }
        }, 5, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 构造 flusher 时传入的观察者（D-08）：延迟解析到 runtime 的桥。
     * runtime 未就绪时全部 no-op/fail-open（onDispatch 返回 true = 放行）。
     */
    public static FlusherObserver createFlusherObserver() {
        return new FlusherObserver() {
            @Override
            public void onCycleFinished(long cycleNanos) {
                final OLinearFlusherBridge b = bridgeOrNull();
                if (b != null) {
                    b.onCycleFinished(cycleNanos);
                }
            }

            @Override
            public boolean onDispatch(OptimizedLinearRegionFile file) {
                final OLinearFlusherBridge b = bridgeOrNull();
                return b == null || b.onDispatch(file);
            }

            @Override
            public void onSyncCompleted(OptimizedLinearRegionFile file, long nanos, long bytes, boolean success) {
                final OLinearFlusherBridge b = bridgeOrNull();
                if (b != null) {
                    b.onSyncCompleted(file, nanos, bytes, success);
                }
            }

            @Override
            public void onSyncDispatched() {
                // D-08 附加钩子：必须转发到桥的 activeSyncs 记账，否则 drainForShutdown
                // 永远看到 activeWorkers=0（有界排水失去意义）。
                final OLinearFlusherBridge b = bridgeOrNull();
                if (b != null) {
                    b.onSyncDispatched();
                }
            }
        };
    }

    private static OLinearFlusherBridge bridgeOrNull() {
        final MiliRuntime rt = MiliRuntimeHolder.get();
        return rt != null ? rt.io().bridge() : null;
    }

    /**
     * Bukkit 权限检查器（命令路径二道闸）：actor 解析为在线玩家；
     * CONSOLE/RCON 视为已授权。Headless/未启动时一律拒绝（fail-closed）。
     */
    public static void installBukkitPermissionChecker(MiliRuntime rt) {
        if (rt == null || bukkitPermissionCheckerInstalled) {
            return;
        }
        bukkitPermissionCheckerInstalled = true;
        rt.setPermissionChecker((actor, permission) -> {
            try {
                if (actor == null || actor.isBlank()) {
                    return false;
                }
                if ("CONSOLE".equals(actor) || "RCON".equals(actor)) {
                    return true;
                }
                final org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayerExact(actor);
                return player != null && player.hasPermission(permission);
            } catch (Throwable t) {
                return false; // fail-closed
            }
        });
    }
}
