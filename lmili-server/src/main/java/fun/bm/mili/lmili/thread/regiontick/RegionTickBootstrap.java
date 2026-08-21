package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import fun.bm.mili.api.Mili;
import fun.bm.mili.api.internal.PublicSchedulerAdapter;
import fun.bm.mili.lmili.thread.scheduler.MiliSchedulerBuilder;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

public final class RegionTickBootstrap {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean initialized;
    private static volatile fun.bm.mili.lmili.thread.scheduler.api.MiliScheduler scheduler;

    private RegionTickBootstrap() {}

    public static synchronized void init() {
        if (initialized) return;
        try {
            RegionTickDispatcher.init();
            // 创建新调度器（使用 MiliSchedulerBuilder）
            scheduler = MiliSchedulerBuilder.create("region-scheduler")
                    .threadNamePrefix("MiliRegion-")
                    .carrierThreads(Runtime.getRuntime().availableProcessors())
                    .maxBlockingTasks(Math.max(2, Runtime.getRuntime().availableProcessors() / 2))
                    .build();
            initialized = true;
            // 注册公共 API —— 让 Mili.scheduler() 可用
            Mili.registerScheduler(new PublicSchedulerAdapter(scheduler));
            LOGGER.info("[RegionTickPool] Initialization complete (public API registered, new scheduler active)");
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Initialization failed", throwable);
        }
    }

    public static synchronized void shutdown() {
        if (!initialized) return;
        initialized = false;
        try {
            RegionTickDispatcher dispatcher = RegionTickDispatcher.getInstance();
            if (dispatcher != null) {
                dispatcher.shutdown();
            }
            if (scheduler != null) {
                scheduler.shutdown(5, TimeUnit.SECONDS);
                scheduler = null;
            }
            Mili.resetScheduler();
            LOGGER.info("[RegionTickPool] Shutdown complete");
        } catch (Throwable throwable) {
            LOGGER.error("[RegionTickPool] Shutdown error", throwable);
        }
    }

    public static boolean isInitialized() { return initialized; }
}
