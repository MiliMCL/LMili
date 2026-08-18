package fun.bm.mili.utils.performance;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionBalancerConfig;
import io.papermc.paper.threadedregions.TickRegionScheduler;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class AdaptiveTPSManager {

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicLong currentInterval = new AtomicLong(50_000_000L);

    private static final long minIntervalNs = 20_000_000L;
    private static final long maxIntervalNs = 100_000_000L;
    private static final long baseIntervalNs = 50_000_000L;

    // Mili start - fix: removed dead snapshotCache field that was never used

    // Mili start - fix: store thread reference for interrupt on shutdown
    private static volatile Thread managerThread;
    // Mili end

    public static void start() {
        if (!RegionBalancerConfig.enabled) return;
        if (running.getAndSet(true)) return;

        // Mili start - fix: store thread reference for proper shutdown
        managerThread = new Thread(AdaptiveTPSManager::runLoop, "AdaptiveTPS-Manager");
        managerThread.setDaemon(true);
        managerThread.start();
        // Mili end

        LogUtils.getClassLogger().info("AdaptiveTPSManager started");
    }

    private static void runLoop() {
        while (running.get()) {
            try {
                TimeUnit.SECONDS.sleep(1);

                if (!RegionBalancerConfig.enabled) continue;

                double avgLoad = 0;
                int count = 0;
                for (RegionLoadMonitor.RegionLoadSnapshot snap : RegionLoadMonitor.getAllSnapshots()) {
                    avgLoad += snap.loadFactor();
                    count++;
                }

                if (count == 0) continue;

                avgLoad /= count;

                long adjusted = (long) (baseIntervalNs * (1.0 + avgLoad * 0.5));
                adjusted = Math.max(minIntervalNs, Math.min(maxIntervalNs, adjusted));

                currentInterval.set(adjusted);
                TickRegionScheduler.TIME_BETWEEN_TICKS = adjusted;

                LogUtils.getClassLogger().debug(
                        "AdaptiveTPS: avgLoad={}%, interval={}ms",
                        (int) (avgLoad * 100), adjusted / 1_000_000L);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            // Mili start - fix: catch Throwable to handle Error (OOM/StackOverflowError);
            // distinguish between fatal and transient errors
            } catch (Throwable ex) {
                LogUtils.getClassLogger().error("AdaptiveTPS error in manager thread", ex);
                if (ex instanceof OutOfMemoryError) {
                    // fatal - stop the thread to prevent repeated OOM loops
                    running.set(false);
                    break;
                }
                // For other transient errors (e.g., ConcurrentModificationException), just log and continue
            }
            // Mili end
        }
    }

    static long getCurrentInterval() {
        return currentInterval.get();
    }

    // Mili start - fix: interrupt the sleeping thread for immediate shutdown
    public static void shutdown() {
        running.set(false);
        Thread t = managerThread;
        if (t != null) {
            t.interrupt();
        }
    }
    // Mili end
}