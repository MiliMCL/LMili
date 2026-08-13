package fun.bm.mili.lmili.thread.regiontick;

import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface RegionTickExecutor {

    void executeSlice(@NotNull RegionTickWorker worker,
                      @NotNull RegionTickSlice slice,
                      @NotNull RegionTickContext context);

    class Registry {
        private static volatile RegionTickExecutor executor;
        public static void register(@NotNull RegionTickExecutor exec) { executor = exec; }
        public static RegionTickExecutor get() { return executor; }
    }

    static RegionTickExecutor getRegisteredExecutor() { return Registry.get(); }
    static void register(@NotNull RegionTickExecutor executor) { Registry.register(executor); }
}
