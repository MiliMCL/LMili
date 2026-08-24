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
 */
public final class PluginIdentityAutoDiscovery {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 防止反复查 Bukkit plugin manager；缓存已发现的 PluginId → Plugin */
    private static final ConcurrentMap<String, Plugin> DISCOVERED_CACHE = new ConcurrentHashMap<>();

    private PluginIdentityAutoDiscovery() {}

    /**
     * 解析 Bukkit 插件名为 PluginId；若 LMili 未注册，自动注册 DISCOVERED 状态。
     *
     * @param raw 用户输入的 plugin 简写或完整 id
     * @return PluginId（永远非 null 当 Bukkit plugin 存在）；返回 null 仅当 Bukkit 中查无此 plugin
     */
    public static PluginId resolveOrDiscover(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // 1. 已注册 → 直接返回
        PluginId direct = PluginId.parseNullable(raw);
        if (direct != null) {
            PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            if (mgr.find(direct).isPresent()) return direct;
        }

        // 2. 单段 → lmili.<single>（修复 spark 这种简写）
        PluginId fallback = makeIdFromName(raw);
        if (fallback != null) {
            PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            if (mgr.find(fallback).isPresent()) return fallback;
        }

        // 3. 查 Bukkit；找到 → 自动以 DISCOVERED 注册
        try {
            Plugin plugin = findBukkitPlugin(raw);
            if (plugin == null) return null;

            // 用 Bukkit plugin.name 转 PluginId（单段 → 加 lmili. 前缀，2+段 → 原样 normalize）
            String normalizedName = plugin.getName().toLowerCase();
            PluginId targetId = makeIdFromName(normalizedName);
            if (targetId == null) {
                LOGGER.warn("[PluginIdentityAutoDiscovery] cannot normalize '{}'", normalizedName);
                return null;
            }

            PluginIdentityManager mgr = LMili.getPluginIdentityManager();
            Optional<PluginIdentity> existing = mgr.find(targetId);
            if (existing.isPresent()) return targetId;

            // 4. 注册 DISCOVERED 副本
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

    /**
     * 构造 PluginId：单段 → 加 "lmili." 前缀；多段 → 原样 normalize。
     * 避免 {@link PluginId#tryNormalize(String)} 单段直接返回 null 的限制。
     */
    private static PluginId makeIdFromName(String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim();
        // 单段：加 lmili. 前缀
        if (!n.contains(".")) {
            n = "lmili." + n;
        }
        return PluginId.tryNormalize(n);
    }

    private static PluginIdentity buildDiscoveredIdentity(Plugin plugin, PluginId id) {
        String name = plugin.getName();
        String version = safeVersion(plugin);
        String publisher = guessPublisher(name);
        String source = "auto-discovery (Bukkit)";

        // PluginIdentity 是 final，只能通过 of() 构造；non-addon 必须 parent 为空。
        // PluginIdentity.of 默认状态 DISCOVERED + TrustLevel UNKNOWN（看代码）。
        // 强制 trust = UNVERIFIED 需要 register 后再 withTrustLevel。
        PluginIdentity base = PluginIdentity.of(
                id, name, version, publisher,
                PluginType.LEGACY, // 外部 plugin 未声明 lmili.json，视为 LEGACY
                java.util.Optional.empty(),
                source,
                plugin.getName()
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
