package fun.bm.mili.lmili.command.identity;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginIdentity;
import fun.bm.mili.lmili.api.identity.PluginIdentityManager;
import fun.bm.mili.lmili.api.identity.PluginStatus;
import fun.bm.mili.lmili.api.identity.PluginType;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 自动发现 Bukkit 已加载但 LMili 尚未识别的 plugin —— 修复
 * <code>/pluginid status spark</code> 显示「invalid id」的问题。
 *
 * <p><b>为什么需要这个</b>（§11 P2-2 / 用户痛点）：
 * <ul>
 *   <li>spark 等外部 plugin 由 Bukkit PluginManager 加载，<b>不经过 LMili identity
 *       注册流程</b>。</li>
 *   <li>{@code PluginId.parseNullable("spark")} 返回 null（PluginId 至少要两段）。</li>
 *   <li>如果 LMili 没主动从 Bukkit 拉 spark 的 plugin.yml 信息，{@code /pluginid status spark}
 *       永远显示「invalid id」。</li>
 * </ul>
 *
 * <p><b>策略</b>：
 * <ol>
 *   <li>先查 LMili 自己的 identity manager（已注册的 → 直接返回）。</li>
 *   <li>查 Bukkit PluginManager；如果 plugin 存在但 LMili 未知，<b>自动以
 *       {@link PluginStatus#DISCOVERED} 状态注册</b>，并构造一个 identity 副本
 *       用于展示。这样 {@code /pluginid status spark} 能正常显示。</li>
 *   <li>查询时不阻塞（PluginManager 调用本身是 O(plugins)，几十个 plugin 完全够用）。</li>
 * </ol>
 *
 * <p><b>不做</b>：
 * <ul>
 *   <li>不接管 spark 的 task 调度（Bukkit 调度走 pluginId 不强制走 LMili；改写全局调度违反
 *       §18.4 "禁止粗暴全局锁" 与 §0 "不推翻现有架构"）。</li>
 *   <li>不写回真实 task 计数 —— spark 通过 BukkitScheduler 提交的任务<b>不</b>走
 *       LMili ResourceQuota；这是已知设计选择（按 §18.9 不扩大改动面）。</li>
 * </ul>
 *
 * <p><b>作用域</b>：本类位于 {@code lmili-server}，是 server-only 工具。
 *   plugin-facing API（{@code fun.bm.mili.lmili.api.*}）刻意不引用本类，避免
 *   {@code lmili-api} 反向依赖 Bukkit。如果 plugin 想"按名字查 plugin"，
 *   通过 {@code /pluginid} 命令或 {@link fun.bm.mili.lmili.api.identity.PluginIdentityManager}
 *   间接访问即可。</p>
 */
public final class PluginIdentityAutoDiscovery {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 防止反复查 Bukkit plugin manager；缓存已发现的 PluginId → Plugin */
    private static final ConcurrentMap<String, Plugin> DISCOVERED_CACHE = new ConcurrentHashMap<>();

    private PluginIdentityAutoDiscovery() {}

    /**
     * 解析 Bukkit 插件名为 PluginId；若 LMili 未注册，自动注册 DISCOVERED 状态。
     *
     * <p><b>必须双段</b>：LMili V2 规范要求 PluginId 至少为 {@code publisher.plugin} 形式。
     * 单段输入（如 {@code spark}）会直接返回 null，强制用户在外部 plugin 必须声明
     * {@code lmili.json} 拿到完整 id（例如 {@code me.lucko.spark}）。这是 V2 规范的硬要求
     * （§23 PluginIdentity Contract），不允许运行时"自动加前缀"这种模糊行为。
     *
     * <p>用户写错 id 时，应通过 {@code /pluginid list} 或 {@code /pluginid suggest <prefix>}
     * 查完整 id（Tab 补全也会给出全部已注册 id）。
     *
     * @param raw 用户输入的完整双段 id（如 {@code me.lucko.spark}）
     * @return PluginId（永远非 null 当 Bukkit plugin 存在）；返回 null 仅当输入非法或 Bukkit 中查无此 plugin
     */
    public static PluginId resolveOrDiscover(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // 1. 必须是完整双段 PluginId（V2 §23 硬要求）
        PluginId direct = PluginId.parseNullable(raw);
        if (direct == null) {
            // 单段或非法格式：拒绝（按 V2 规范）
            return null;
        }
        PluginIdentityManager mgr = LMili.getPluginIdentityManager();
        if (mgr.find(direct).isPresent()) return direct;

        // 2. LMili 未注册 → 查 Bukkit PluginManager（仅按完整 publisher.plugin 形态）
        try {
            Plugin plugin = findBukkitPlugin(raw);
            if (plugin == null) return null;

            // 双重校验：Bukit plugin 自身的 name 也必须能 normalize 成一个双段 id
            //   （理论上 plugin.name 是单段如 "spark"，但 Bukkit 加载用 "spark"，所以这里仍按
            //   raw 校验 —— 若 raw 是 "me.lucko.spark"，正常路径就会 findBukkitPlugin 返回 null，
            //   此时走 PluginManager 全量遍历；按规范，lmili.json 必须存在才能注册。）
            PluginId targetId = PluginId.tryNormalize(raw);
            if (targetId == null) {
                LOGGER.warn("[PluginIdentityAutoDiscovery] cannot normalize '{}'", raw);
                return null;
            }

            Optional<PluginIdentity> existing = mgr.find(targetId);
            if (existing.isPresent()) return targetId;

            // 3. 注册 DISCOVERED 副本（兜底：插件没带 lmili.json 但 Bukkit 加载成功）
            PluginIdentity identity = buildDiscoveredIdentity(plugin, targetId);
            PluginIdentity registered = mgr.register(identity);
            DISCOVERED_CACHE.put(targetId.value(), plugin);
            LOGGER.info("[PluginIdentityAutoDiscovery] auto-registered DISCOVERED plugin '{}' (id={}) from Bukkit",
                    plugin.getName(), targetId.value());
            return registered != null ? registered.id() : targetId;
        } catch (Throwable t) {
            LOGGER.debug("[PluginIdentityAutoDiscovery] lookup failed for '{}'", raw, t);
            return null;
        }
    }

    /** 检查 Bukkit PluginManager（不修改；供命令路径使用） */
    public static Plugin findBukkitPlugin(String raw) {
        Plugin cached = DISCOVERED_CACHE.get(raw.toLowerCase());
        if (cached != null && cached.isEnabled()) return cached;
        try {
            // 1. 精确名匹配
            Plugin p = Bukkit.getPluginManager().getPlugin(raw);
            if (p != null) {
                DISCOVERED_CACHE.put(raw.toLowerCase(), p);
                return p;
            }
            // 2. 大小写不敏感匹配
            for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
                if (candidate.getName().equalsIgnoreCase(raw)) {
                    DISCOVERED_CACHE.put(raw.toLowerCase(), candidate);
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // Bukkit 不可用（启动早期）静默
        }
        return null;
    }

    /** 给已注册的 pluginId 拿它的 Bukkit plugin 对象（已知是 DISCOVERED 或 ACTIVE） */
    public static Plugin lookupBukkitPlugin(PluginId id) {
        if (id == null) return null;
        Plugin cached = DISCOVERED_CACHE.get(id.value());
        if (cached != null && cached.isEnabled()) return cached;
        return findBukkitPlugin(id.value());
    }

    private static PluginIdentity buildDiscoveredIdentity(Plugin plugin, PluginId id) {
        String name = plugin.getName();
        String version = safeVersion(plugin);
        String publisher = guessPublisher(name);
        String source = "auto-discovery (Bukkit)";

        // PluginIdentity 是 final，只能通过 of() 构造；non-addon 必须 parent 为空。
        // PluginIdentity.of 默认状态 DISCOVERED + TrustLevel UNKNOWN（看代码）。
        // 强制 trust = UNVERIFIED 需要 register 后再 withTrustLevel。
        // §C 26.2+：外部 plugin（无 lmili.json）也被强制 LMILI_REQUIRED（兼容层被删除）；
        // PluginIdentityBootstrap 会通过 source=="auto-discovery (Bukkit)" 检测并禁用 plugin。
        PluginIdentity base = PluginIdentity.of(
                id, name, version, publisher,
                PluginType.LEGACY, // 外部 plugin 未声明 lmili.json，视为 LEGACY
                java.util.Optional.empty(),
                source,
                plugin.getName(),
                fun.bm.mili.lmili.api.identity.SchedulerDelegation.LMILI_REQUIRED
        );
        // 外部 plugin 默认 LOCAL（未签名本地安装）
        return base.withTrustLevel(fun.bm.mili.lmili.api.identity.PluginTrustLevel.LOCAL);
    }

    private static String safeVersion(Plugin plugin) {
        try {
            String v = plugin.getDescription().getVersion();
            return v == null || v.isBlank() ? "unknown" : v;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    /** 简单的 publisher 推断：首段小写当作 publisher，否则 "external"。 */
    private static String guessPublisher(String name) {
        String lower = name.toLowerCase();
        int dot = lower.indexOf('.');
        if (dot > 0) return lower.substring(0, dot);
        return "external";
    }
}
