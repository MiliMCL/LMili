package fun.bm.mili.utils.performance;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.experiment.RegionBalancerConfig;
import fun.bm.mili.utils.region.RegionLoadMonitor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive TPS Manager — 根据区域负载动态调整 tick 间隔。
 *
 * <p>修复：
 * <ul>
 *   <li>移除直接写入 TickRegionScheduler.TIME_BETWEEN_TICKS（线程不安全，会导致 Folia 调度撕裂读）</li>
 *   <li>修复负载公式：高负载时应缩短间隔（加速 tick），而非延长</li>
 *   <li>使用 max 负载而非 avg，避免热点被平均值掩盖</li>
 * </ul>
 *
 * @deprecated 已废弃，依赖 RegionBalancer 的负载监控。Mili 调度器内置了自适应延迟机制。
 */
@Deprecated
public class AdaptiveTPSManager {

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicLong currentInterval = new AtomicLong(50_000_000L);

    private static final long minIntervalNs = 25_000_000L;  // 40 TPS cap
    private static final long maxIntervalNs = 50_000_000L;  // 20 TPS floor
    private static final long baseIntervalNs = 50_000_000L; // 20 TPS base

    private static volatile Thread managerThread;

    public static void start() {
        if (!RegionBalancerConfig.enabled) return;
        if (running.getAndSet(true)) return;

        managerThread = new Thread(AdaptiveTPSManager::runLoop, "AdaptiveTPS-Manager");
        managerThread.setDaemon(true);
        managerThread.start();

        LogUtils.getClassLogger().info("AdaptiveTPSManager started");
    }

    private static void runLoop() {
        while (running.get()) {
            try {
                TimeUnit.SECONDS.sleep(1);

                if (!RegionBalancerConfig.enabled) continue;

                // Mili start - fix: use MAX load instead of average to detect hotspots.
                // A single overloaded region should not be masked by 99 idle regions.
                double maxLoad = 0;
                int count = 0;
                for (RegionLoadMonitor.RegionLoadSnapshot snap : RegionLoadMonitor.getAllSnapshots()) {
                    double load = snap.loadFactor();
                    if (load > maxLoad) maxLoad = load;
                    count++;
                }

                if (count == 0) continue;

                // Mili start - fix: formula is now INVERSE — high load → shorter interval (maintain TPS).
                // Under high load, we want to keep ticking fast to process backlog.
                // Under low load, we can relax slightly to save CPU.
                // Formula: adjusted = baseInterval * (1.0 - load * 0.5)
                // At load=0: 50ms (20 TPS)
                // At load=0.5: 37.5ms (~26 TPS)
                // At load=1.0: 25ms (40 TPS cap)
                long adjusted = (long) (baseIntervalNs * (1.0 - maxLoad * 0.5));
                adjusted = Math.max(minIntervalNs, Math.min(maxIntervalNs, adjusted));

                currentInterval.set(adjusted);
                // Mili end - removed direct write to TickRegionScheduler.TIME_BETWEEN_TICKS
                // which was thread-unsafe with Folia's tick scheduling

                LogUtils.getClassLogger().debug(
                        "AdaptiveTPS: maxLoad={}%, interval={}ms",
                        (int) (maxLoad * 100), adjusted / 1_000_000L);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ex) {
                LogUtils.getClassLogger().error("AdaptiveTPS error in manager thread", ex);
                if (ex instanceof OutOfMemoryError) {
                    running.set(false);
                    break;
                }
            }
        }
    }

    static long getCurrentInterval() {
        return currentInterval.get();
    }

    public static void shutdown() {
        running.set(false);
        Thread t = managerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * 停用（AdaptiveRuntime §5.7 规则 10 / D-14）：检测到旧 AdaptiveTPSManager 时由
     * {@code MiliRuntime.start()} 调用，避免新旧两条自适应路径同时改 tick 节奏。
     *
     * <p>不改动任何既有逻辑 —— 与 {@link #shutdown()} 语义完全一致（置 running=false +
     * 中断线程），仅提供语义化的幂等入口，可与 shutdown() 重复调用。
     */
    public static void disable() {
        shutdown();
    }
}
