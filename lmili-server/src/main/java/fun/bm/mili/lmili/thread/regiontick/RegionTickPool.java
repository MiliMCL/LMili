package fun.bm.mili.lmili.thread.regiontick;

import fun.bm.mili.config.modules.experiment.RegionTickPoolConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public final class RegionTickPool {

    private RegionTickPool() {}

    public static @Nullable RegionTickPool getInstance() { return Holder.INSTANCE; }
    public static @Nullable RegionTickDispatcher getDispatcher() { return RegionTickDispatcher.getInstance(); }

    public static boolean isRunning() {
        return RegionTickDispatcher.getInstance() != null && RegionTickPoolConfig.enabled;
    }

    public static @NotNull Map<String, Object> getStats() {
        RegionTickDispatcher dispatcher = RegionTickDispatcher.getInstance();
        if (dispatcher == null) return Map.of("status", "not_initialized");
        return Map.copyOf(dispatcher.getStats());
    }

    public static void shutdown() {
        RegionTickDispatcher dispatcher = RegionTickDispatcher.getInstance();
        if (dispatcher !=null) dispatcher.shutdown();
        Holder.INSTANCE = null;
    }

    static @Nullable RegionTickPool initIfNeeded() {
        if (!RegionTickPoolConfig.enabled) return null;
        if (Holder.INSTANCE != null) return Holder.INSTANCE;
        synchronized (RegionTickPool.class) {
            if (Holder.INSTANCE == null) {
                try {
                    RegionTickDispatcher.init();
                    Holder.INSTANCE = new RegionTickPool();
                } catch (Throwable throwable) {
                    com.mojang.logging.LogUtils.getClassLogger().error("[RegionTickPool] Init failed", throwable);
                    return null;
                }
            }
            return Holder.INSTANCE;
        }
    }

    private static final class Holder {
        private static volatile RegionTickPool INSTANCE;
    }
}
