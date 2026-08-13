package fun.bm.mili.api;

import org.jetbrains.annotations.NotNull;

/**
 * Mili public API entry point.
 *
 * <p>Usage: Mili.scheduler().forEntity(entity).run(ctx -> { ... });</p>
 *
 * <p>Soft check: if (Mili.isSupported()) { ... } else { fallback }</p>
 */
public final class Mili {

    private static volatile Scheduler scheduler;

    private Mili() {}

    /**
     * Get the Mili scheduler instance.
     *
     * <p>If Mili is not initialized or RegionTickPool is not enabled,
     * returns a no-op scheduler (all calls do nothing, no exceptions thrown).</p>
     *
     * @return Mili scheduler, never null
     */
    public static @NotNull Scheduler scheduler() {
        Scheduler s = scheduler;
        if (s != null) return s;
        return NoopScheduler.INSTANCE;
    }

    /**
     * Check if Mili scheduling framework is available.
     *
     * @return true if Mili is initialized and RegionTickPool is enabled
     */
    public static boolean isSupported() {
        return scheduler != null;
    }

    /**
     * Get Mili version string.
     *
     * @return version string, "unknown" if not initialized
     */
    @NotNull
    public static String version() {
        Scheduler s = scheduler;
        if (s != null) return s.version();
        return "unknown";
    }

    /**
     * Internal method: register the underlying scheduler implementation.
     * Called by RegionTickBootstrap after successful initialization.
     *
     * @param impl the actual scheduler implementation
     */
    public static void registerScheduler(@NotNull Scheduler impl) {
        scheduler = impl;
    }

    /**
     * Internal method: reset the scheduler.
     * Called by RegionTickBootstrap on shutdown.
     */
    public static void resetScheduler() {
        scheduler = null;
    }

    private static final class NoopScheduler implements Scheduler {
        static final NoopScheduler INSTANCE = new NoopScheduler();

        @Override
        public @NotNull EntityScheduler forEntity(@NotNull org.bukkit.entity.Entity entity) {
            return NoopEntityScheduler.INSTANCE;
        }

        @Override
        public void runAt(@NotNull org.bukkit.Location location,
                          @NotNull java.util.function.Consumer<EntityTaskContext> task) { }

        @Override
        public void runAsync(@NotNull Runnable task) { }

        @Override
        @NotNull
        public String version() { return "noop"; }
    }

    private static final class NoopEntityScheduler implements EntityScheduler {
        static final NoopEntityScheduler INSTANCE = new NoopEntityScheduler();

        @Override
        public void run(@NotNull java.util.function.Consumer<EntityTaskContext> task) { }

        @Override
        public void runDelayed(@NotNull java.util.function.Consumer<EntityTaskContext> task, long delayTicks) { }
    }
}
