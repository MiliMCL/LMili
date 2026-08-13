package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.api.Mili;
import fun.bm.mili.api.internal.PublicSchedulerAdapter;
import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import fun.bm.mili.lmili.thread.regiontick.suspend.VirtualThreadScheduler;
import org.slf4j.Logger;

public final class RegionTickBootstrap {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean initialized;

    private RegionTickBootstrap() {}

    public static synchronized void init() {
        if (initialized) return;
        if (!RegionTickPoolConfig.enabled) {
            LOGGER.debug("[RegionTickPool] Not enabled, skipping");
            return;
        }
        disableConflicting();
        try {
            RegionTickPool pool = RegionTickPool.initIfNeeded();
            if (pool != null) {
                initialized = true;
                // 注册公共 API —— 让 Mili.scheduler() 可用
                Mili.registerScheduler(new PublicSchedulerAdapter(VirtualThreadScheduler.getInstance()));
                LOGGER.info("[RegionTickPool] Initialization complete (public API registered)");
            } else {
                LOGGER.warn("[RegionTickPool] Initialization returned null");
            }
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Initialization failed", throwable);
        }
    }

    public static synchronized void shutdown() {
        if (!initialized) return;
        initialized = false;
        try {
            RegionTickPool.shutdown();
            Mili.resetScheduler();
            LOGGER.info("[RegionTickPool] Shutdown complete");
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Shutdown error", throwable);
        }
    }

    public static boolean isInitialized() { return initialized; }

    private static void disableConflicting() {
        if (fun.bm.mili.config.modules.experiment.RegionBalancerConfig.enabled) {
            LOGGER.info("[RegionTickPool] Disabling RegionBalancer (RegionTickPool is its successor)");
            fun.bm.mili.config.modules.experiment.RegionBalancerConfig.enabled = false;
        }
    }
}
