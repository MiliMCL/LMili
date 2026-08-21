package fun.bm.mili;

import fun.bm.mili.bridge.ChunkRegionBridge;
import fun.bm.mili.chunk.MiliChunkSystem;
import fun.bm.mili.lmili.i18n.I18nManager;
import fun.bm.mili.lmili.thread.regiontick.RegionTickBootstrap;
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
        // 初始化国际化系统
        String locale = plugin.getConfig().getString("language", "en_us");
        I18nManager.init(locale);

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
        RegionTickBootstrap.init();

        // 网络优化
        if (NetworkOptimizerConfig.enabled) {
            NetworkOptimizer.init();
        }

        LOGGER.info("[Mili] Optimizations initialized (v5.0 — unified scheduler, locale=" + locale + ")");
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