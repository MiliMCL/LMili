package io.papermc.paper.threadedregions;

import com.mojang.logging.LogUtils;
import fun.bm.mili.config.modules.misc.LMiliWatchdogConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LMili 看门狗线程 —— 使用 LMili API 实现 tick region 超时检测。
 *
 * <p><b>设计目标</b>：替代 Folia 的看门狗线程，使用 LMili 统一调度 API
 * 实现 tick region 超时检测和报告。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>超时检测：检测执行时间超过阈值的 tick region</li>
 *   <li>日志警告：超时时记录详细日志</li>
 *   <li>统计指标：提供超时检测的详细统计</li>
 *   <li>可配置：支持动态调整超时阈值</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有公共方法都是线程安全的。使用 ConcurrentHashMap 和原子变量确保并发安全。</p>
 *
 * @since 2.0.0
 */
public final class LMiliWatchdogThread {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单例实例 */
    private static volatile LMiliWatchdogThread instance;

    /** 是否运行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 看门狗线程 */
    private Thread watchdogThread;

    /** 统计指标 */
    private final AtomicLong totalChecks = new AtomicLong();
    private final AtomicLong totalTimeouts = new AtomicLong();
    private final AtomicLong totalSevereTimeouts = new AtomicLong();

    /** region 执行开始时间（用于检测超时） */
    private final Map<Long, Long> regionStartTimes = new ConcurrentHashMap<>();

    /** 最后警告时间（用于防止日志洪水） */
    private final Map<Long, Long> lastWarningTimes = new ConcurrentHashMap<>();

    /** 配置 */
    private volatile long checkIntervalMs = 1000; // 默认 1 秒检查一次

    /** 当前运行的 tick */
    private final Map<Thread, RunningTick> runningTicks = new ConcurrentHashMap<>();

    /** 默认构造器 */
    public LMiliWatchdogThread() {}

    /**
     * 获取单例实例。
     *
     * @return 看门狗实例
     */
    @NotNull
    public static LMiliWatchdogThread getInstance() {
        if (instance == null) {
            synchronized (LMiliWatchdogThread.class) {
                if (instance == null) {
                    instance = new LMiliWatchdogThread();
                }
            }
        }
        return instance;
    }

    /**
     * 启动看门狗线程。
     */
    public void start() {
        if (running.getAndSet(true)) return;

        if (!LMiliWatchdogConfig.enableWatchdog) {
            LOGGER.info("[LMiliWatchdog] Watchdog is disabled by configuration");
            return;
        }

        watchdogThread = new Thread(this::runWatchdog, "LMili-Watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.setPriority(Thread.MIN_PRIORITY);
        watchdogThread.start();

        LOGGER.info("[LMiliWatchdog] Watchdog started (timeout={}ms, interval={}ms)",
                LMiliWatchdogConfig.tickRegionTimeOutMs, checkIntervalMs);
    }

    /**
     * 停止看门狗线程。
     */
    public void stop() {
        running.set(false);
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }
        instance = null;
    }

    /**
     * 记录 region tick 开始。
     *
     * @param regionId region ID
     */
    public void onRegionTickStart(long regionId) {
        if (!LMiliWatchdogConfig.enableWatchdog) return;
        regionStartTimes.put(regionId, System.currentTimeMillis());
    }

    /**
     * 记录 region tick 结束。
     *
     * @param regionId region ID
     */
    public void onRegionTickEnd(long regionId) {
        if (!LMiliWatchdogConfig.enableWatchdog) return;
        regionStartTimes.remove(regionId);
        lastWarningTimes.remove(regionId);
    }

    /**
     * 看门狗主循环。
     */
    private void runWatchdog() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                checkTimeouts();
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.error("[LMiliWatchdog] Error in watchdog loop", e);
            }
        }
    }

    /**
     * 检查所有 region 的超时情况。
     */
    private void checkTimeouts() {
        if (regionStartTimes.isEmpty()) return;

        totalChecks.incrementAndGet();
        long now = System.currentTimeMillis();

        for (Map.Entry<Long, Long> entry : regionStartTimes.entrySet()) {
            long regionId = entry.getKey();
            long startTime = entry.getValue();
            long elapsed = now - startTime;

            if (LMiliWatchdogConfig.isTimeout(elapsed)) {
                totalTimeouts.incrementAndGet();

                // 检查是否是严重超时
                if (LMiliWatchdogConfig.isSevereTimeout(elapsed)) {
                    totalSevereTimeouts.incrementAndGet();
                }

                // 防止日志洪水（每个 region 最多每秒警告一次）
                Long lastWarning = lastWarningTimes.get(regionId);
                if (lastWarning == null || (now - lastWarning) > 1000) {
                    lastWarningTimes.put(regionId, now);

                    if (LMiliWatchdogConfig.logOnTimeout) {
                        if (LMiliWatchdogConfig.isSevereTimeout(elapsed)) {
                            LOGGER.warn("[LMiliWatchdog] SEVERE timeout: region #{} has been ticking for {}ms (threshold {}ms)",
                                    regionId, elapsed, LMiliWatchdogConfig.tickRegionTimeOutMs);
                        } else if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("[LMiliWatchdog] Timeout: region #{} has been ticking for {}ms",
                                    regionId, elapsed);
                        }
                    }
                }
            }
        }
    }

    /**
     * 获取总检查次数。
     *
     * @return 检查次数
     */
    public long getTotalChecks() {
        return totalChecks.get();
    }

    /**
     * 获取总超时次数。
     *
     * @return 超时次数
     */
    public long getTotalTimeouts() {
        return totalTimeouts.get();
    }

    /**
     * 获取总严重超时次数。
     *
     * @return 严重超时次数
     */
    public long getTotalSevereTimeouts() {
        return totalSevereTimeouts.get();
    }

    /**
     * 获取当前监控的 region 数量。
     *
     * @return region 数量
     */
    public int getMonitoredRegionCount() {
        return regionStartTimes.size();
    }

    /**
     * 设置检查间隔。
     *
     * @param intervalMs 检查间隔（毫秒）
     */
    public void setCheckIntervalMs(long intervalMs) {
        this.checkIntervalMs = Math.max(100, intervalMs);
    }

    /**
     * 获取统计信息。
     *
     * @return 统计信息映射
     */
    @NotNull
    public java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("running", running.get());
        stats.put("totalChecks", totalChecks.get());
        stats.put("totalTimeouts", totalTimeouts.get());
        stats.put("totalSevereTimeouts", totalSevereTimeouts.get());
        stats.put("monitoredRegions", regionStartTimes.size());
        stats.put("checkIntervalMs", checkIntervalMs);
        stats.put("enabled", LMiliWatchdogConfig.enableWatchdog);
        return stats;
    }

    /**
     * 清理所有数据。
     */
    public void clear() {
        regionStartTimes.clear();
        lastWarningTimes.clear();
        runningTicks.clear();
        instance = null;
    }

    /**
     * 添加一个正在运行的 tick。
     *
     * @param tick 运行的 tick
     */
    public void addTick(@NotNull RunningTick tick) {
        runningTicks.put(tick.thread, tick);
    }

    /**
     * 移除一个正在运行的 tick。
     *
     * @param thread 线程
     */
    public void removeTick(@NotNull Thread thread) {
        runningTicks.remove(thread);
    }

    /**
     * 表示一个正在执行的 tick。
     */
    public static final class RunningTick {
        public final long startTime;
        public final Object regionScheduler;
        public final Thread thread;

        public RunningTick(long startTime, Object regionScheduler, Thread thread) {
            this.startTime = startTime;
            this.regionScheduler = regionScheduler;
            this.thread = thread;
        }
    }
}
