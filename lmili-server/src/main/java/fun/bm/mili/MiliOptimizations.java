package fun.bm.mili;

import fun.bm.mili.bridge.ChunkRegionBridge;
import fun.bm.mili.chunk.MiliChunkSystem;
import fun.bm.mili.lmili.i18n.I18nManager;
import fun.bm.mili.lmili.thread.regiontick.RegionTickBootstrap;
import fun.bm.mili.config.modules.function.LanguageConfig;
import fun.bm.mili.config.modules.optimizations.ChunkSystemConfig;
import fun.bm.mili.config.modules.optimizations.NetworkOptimizerConfig;
import fun.bm.mili.config.modules.optimizations.VillagerOptimizerConfig;
import fun.bm.mili.utils.performance.LagRemover;
import fun.bm.mili.utils.network.AsyncKeepaliveManager;
import fun.bm.mili.utils.network.NetworkOptimizer;
import fun.bm.mili.villager.VillagerOptimizer;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Mili 优化系统总初始化入口
 * 管理所有优化子系统的生命周期:
 * - 区块系统 (ChunkSystem)
 * - 实体优化 (VillagerOptimizer)
 * - 网络优化 (NetworkOptimizer)
 * - 延迟缓解 (LagRemover)
 * - 调度器 (RegionTickPool — 永久启用)
 * - 国际化 (I18n)
 *
 * 注意：RegionBalancer 和 SmartRegionManager 已废弃，
 * 被 RegionTickPool（Mili 统一调度器）完全替代。
 */
public final class MiliOptimizations {
    private static final Logger LOGGER = Logger.getLogger("Mili");

    private MiliOptimizations() {}

    public static void init(Plugin plugin) {
        // 初始化国际化系统（使用 LanguageConfig.lang 配置）
        I18nManager.init(LanguageConfig.lang);

        // Mili: 提供 Mili plugin 实例给 EntityTickPerformanceConfig 的 Bukkit listener
        // 这样 listener 注册时不用走 Bukkit.getPluginManager().getPlugin("Mili") 间接查找
        fun.bm.mili.config.modules.optimizations.EntityTickPerformanceConfig.setMiliPlugin(plugin);

        // 核心延迟缓解
        LagRemover.init(plugin);

        // 村民优化
        if (VillagerOptimizerConfig.enabled) {
            VillagerOptimizer.init(plugin);
        }

        // 区块系统
        if (ChunkSystemConfig.enabled) {
            MiliChunkSystem.init(plugin);
            ChunkRegionBridge.init();
        }

        // Mili 统一调度器（RegionTickPool — 永久启用）
        //
        // 注意：单 runtime 修复后，RegionTickBootstrap.init() 也可由 TickRegions.start() 调用
        // （更早的时机，且 worker 线程已就绪）。此处调用仍保留作为兜底路径：init() 是幂等的。
        RegionTickBootstrap.init();

        // §11 P2-2 / §15 观测面：自动捕获 BukkitScheduler 上的外部 plugin 任务（修复
        // spark 等"看不到调度次数/处理次数"问题）。需 plugin owner；幂等，失败仅日志。
        try {
            fun.bm.mili.lmili.observability.AutoSchedulerCapture auto =
                    new fun.bm.mili.lmili.observability.AutoSchedulerCapture();
            auto.registerSelf(plugin);
        } catch (Throwable t) {
            LOGGER.warning("[Mili] AutoSchedulerCapture register failed: " + t.getMessage());
        }

        // 网络优化
        if (NetworkOptimizerConfig.enabled) {
            NetworkOptimizer.init();
        }

        // 修复：使用 I18nManager.getCurrentLocale() 替换未定义的 locale 变量
        LOGGER.info("[Mili] Optimizations initialized (v5.0 — unified scheduler, locale=" + I18nManager.getCurrentLocale() + ")");
    }

    public static void shutdown() {
        AsyncKeepaliveManager.shutdown();
        if (ChunkSystemConfig.enabled) {
            MiliChunkSystem.shutdown();
            ChunkRegionBridge.shutdown();
        }
        if (VillagerOptimizerConfig.enabled) {
            VillagerOptimizer.shutdown();
        }
        LagRemover.shutdown();
        if (NetworkOptimizerConfig.enabled) {
            NetworkOptimizer.shutdown();
        }

        // Mili 统一调度器关闭
        RegionTickBootstrap.shutdown();

        LOGGER.info("[Mili] All optimizations shutdown");
    }
}
