package fun.bm.mili.lmili.runtime.control;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.runtime.io.IOState;
import fun.bm.mili.lmili.runtime.policy.PressureSignals;
import fun.bm.mili.lmili.runtime.policy.PressureThresholds;
import fun.bm.mili.lmili.thread.regiontick.EntityTickMetrics;
import fun.bm.mili.lmili.thread.runtime.metrics.RuntimeMetrics;
import fun.bm.mili.utils.performance.TPSTracker;
import fun.bm.mili.utils.region.RegionLoadMonitor;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标聚合中枢 —— State/Telemetry Plane 的出口（ARCHITECTURE_AdaptiveRuntime.md §3.8）。
 *
 * <p>职责：每控制周期从所有 {@link MetricSource} 采集 → EMA 平滑（α=0.3）→ 组装
 * {@link PressureSignals} 交给 GlobalController（→ PressureStateMachine）；
 * 同时对外提供 /lmili control status 面板数据。
 *
 * <p>铁律：本类<strong>不做决策</strong>（纯观察者聚合）；指标缺失不 panic，走 fail-safe（§6.2）：
 * tps→最后值、cpu→0.5、ioQueue→0、ioP99→0、schedulerLoad→0.5；全部源失败 → 标记 allSourcesFailed，
 * GlobalController 据此进入 DEGRADED。
 */
public final class MetricsController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<MetricSource<?>> sources = new CopyOnWriteArrayList<>();

    // ---- 失败追踪 ----
    private final AtomicLong sourceFailures = new AtomicLong();
    private volatile boolean lastAllSourcesFailed = false;
    private final AtomicLong staleSources = new AtomicLong();

    // ---- EMA 状态（控制线程单写者）----
    private double tpsEma = 20.0;
    private double cpuEma = 0.2;
    private double ioQueueEma = 0;
    private double ioP99Ema = 0;
    private double schedulerLoadEma = 0.2;
    private int lastActiveRegions = 0;
    private int lastTotalRegions = 0;

    // ---- 面板数据（最近一次 collect 的原始值）----
    private volatile double lastTps = 20.0;
    private volatile double lastCpuLoad = 0.2;
    private volatile int lastSchedulerLoadPct = 0;
    private volatile long lastIoQueue = 0;
    private volatile long lastIoP99 = 0;
    private volatile int lastWorkers = 0;
    private volatile long lastSyncedRegions = 0;

    private volatile RuntimeMetrics runtimeMetrics = null;

    public void registerSource(MetricSource<?> source) {
        if (source != null) {
            sources.add(source);
        }
    }

    public void unregisterSource(String name) {
        sources.removeIf(s -> s.name().equals(name));
    }

    public int sourceCount() {
        return sources.size();
    }

    public long sourceFailures() {
        return sourceFailures.get();
    }

    /** 当前控制周期是否全部源失败（GlobalController 据此 fail-safe → DEGRADED，§6.2） */
    public boolean allSourcesFailed() {
        return lastAllSourcesFailed;
    }

    public long staleSources() {
        return staleSources.get();
    }

    /** 接线现有 RuntimeMetrics（可选；null = 面板缺失该维度，fail-safe） */
    public void wireRuntimeMetrics(RuntimeMetrics metrics) {
        this.runtimeMetrics = metrics;
    }

    /**
     * 采集 → EMA 平滑 → PressureSignals（每控制周期一次；控制线程单写者）。
     */
    public PressureSignals collect() {
        final long now = System.nanoTime();
        boolean tpsOk = false, cpuOk = false, ioQueueOk = false, ioP99Ok = false;
        boolean schedOk = false, activeOk = false, totalOk = false;
        double tps = lastTps;
        double cpu = 0.5;
        long ioQueue = 0;
        long ioP99 = 0;
        double sched = 0.5;
        int activeRegions = 0;
        int totalRegions = 0;
        long failures = 0;
        boolean anySource = false;
        final long staleNanos = PressureThresholds.DEFAULTS.failSafeStaleNanos();

        for (MetricSource<?> source : sources) {
            anySource = true;
            try {
                final Object value = source.sample();
                if (value == null || now - source.timestampNanos() > staleNanos) {
                    failures++;
                    staleSources.incrementAndGet();
                    continue;
                }
                switch (source.kind()) {
                    case TPS -> {
                        tps = ((Number) value).doubleValue();
                        tpsOk = true;
                    }
                    case CPU_LOAD -> {
                        cpu = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
                        cpuOk = true;
                    }
                    case IO_QUEUE_DEPTH -> {
                        // ioStateSource 的采样值是 IOState（非 Number）—— 一个源同时带 queueDepth 与 p99。
                        // 原实现直接 ((Number) value).longValue() 会 ClassCastException，导致 IO 反馈环
                        // 永远走 fail-safe 中性值（IO_PRESSURE 永不可达）。此处按类型分派修复。
                        if (value instanceof IOState ios) {
                            ioQueue = ios.queueDepth();
                            ioP99 = ios.p99LatencyNanos();
                            ioQueueOk = true;
                            ioP99Ok = true;
                        } else {
                            ioQueue = ((Number) value).longValue();
                            ioQueueOk = true;
                        }
                    }
                    case IO_P99_NANOS -> {
                        ioP99 = ((Number) value).longValue();
                        ioP99Ok = true;
                    }
                    case SCHEDULER_LOAD -> {
                        sched = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
                        schedOk = true;
                    }
                    case ACTIVE_REGIONS -> {
                        activeRegions = Math.max(0, ((Number) value).intValue());
                        activeOk = true;
                    }
                    case TOTAL_REGIONS -> {
                        totalRegions = Math.max(0, ((Number) value).intValue());
                        totalOk = true;
                    }
                    default -> {
                        // ENTITY_STATS / RUNTIME_STATS：仅面板，不参与压力判定
                    }
                }
            } catch (Throwable t) {
                failures++;
                sourceFailures.incrementAndGet();
                LOGGER.debug("[MetricsController] source {} failed (fail-safe)", source.name(), t);
            }
        }

        if (failures > 0) {
            sourceFailures.addAndGet(failures);
        }
        // 全部源失败（或没有任何源）：进入 DEGRADED 观察态的前提标记
        final boolean allFailed = sources.isEmpty() || failures >= sources.size();
        lastAllSourcesFailed = allFailed;

        // EMA 平滑（α=0.3，§4.2）：缺失时保持上次值（fail-safe）
        if (tpsOk) {
            tpsEma = ema(tpsEma, tps);
            lastTps = tpsEma;
        }
        if (cpuOk) {
            cpuEma = ema(cpuEma, cpu);
            lastCpuLoad = cpuEma;
        } else {
            cpuEma = ema(cpuEma, 0.5); // 中性：cpu→0.5
            lastCpuLoad = cpuEma;
        }
        if (ioQueueOk) {
            ioQueueEma = ema(ioQueueEma, ioQueue);
            lastIoQueue = (long) ioQueueEma;
        } else {
            ioQueueEma = ema(ioQueueEma, 0); // 中性：ioQueue→0
            lastIoQueue = (long) ioQueueEma;
        }
        if (ioP99Ok) {
            ioP99Ema = ema(ioP99Ema, ioP99);
            lastIoP99 = (long) ioP99Ema;
        } else {
            ioP99Ema = ema(ioP99Ema, 0); // 中性：ioP99→0
            lastIoP99 = (long) ioP99Ema;
        }
        if (schedOk) {
            schedulerLoadEma = ema(schedulerLoadEma, sched);
            lastSchedulerLoadPct = (int) (schedulerLoadEma * 100);
        } else {
            schedulerLoadEma = ema(schedulerLoadEma, 0.5);
            lastSchedulerLoadPct = (int) (schedulerLoadEma * 100);
        }
        if (activeOk) {
            lastActiveRegions = activeRegions;
        }
        if (totalOk) {
            lastTotalRegions = totalRegions;
        }
        lastWorkers = lastActiveRegions; // 面板近似：活跃 region 即并行面

        return new PressureSignals(
                tpsEma, cpuEma, (long) ioQueueEma, (long) ioP99Ema,
                schedulerLoadEma, lastActiveRegions, lastTotalRegions, false, System.nanoTime()
        );
    }

    // ================= 内置 MetricSource 适配器（§5.5：现有类零改动） =================

    /** TPSTracker 适配器（getTPS() 本身已按 100 tick 窗口计算；此处再 EMA 平滑） */
    public static MetricSource<Double> tpsSource() {
        return new MetricSource<>() {
            @Override
            public String name() {
                return "TPSTracker";
            }

            @Override
            public MetricKind kind() {
                return MetricKind.TPS;
            }

            @Override
            public Double sample() {
                try {
                    return TPSTracker.getTPS();
                } catch (Throwable t) {
                    return null; // 未初始化（测试环境）→ fail-safe
                }
            }
        };
    }

    /** Scheduler 适配器（只读 performanceSnapshot：activeWorkers / carrierThreads） */
    public static MetricSource<Double> schedulerSource(SchedulerController scheduler) {
        return new MetricSource<>() {
            @Override
            public String name() {
                return "MiliScheduler";
            }

            @Override
            public MetricKind kind() {
                return MetricKind.SCHEDULER_LOAD;
            }

            @Override
            public Double sample() {
                return scheduler.schedulerLoadPct() / 100.0;
            }
        };
    }

    /** Scheduler 活跃 region 适配器 */
    public static MetricSource<Integer> activeRegionsSource(SchedulerController scheduler) {
        return new MetricSource<>() {
            @Override
            public String name() {
                return "ActiveRegions";
            }

            @Override
            public MetricKind kind() {
                return MetricKind.ACTIVE_REGIONS;
            }

            @Override
            public Integer sample() {
                return scheduler.activeRegionCount();
            }
        };
    }

    /** EntityTick 适配器（面板维度；不参与压力判定） */
    public static MetricSource<Long> entityTickSource(EntityController entity) {
        return new MetricSource<>() {
            @Override
            public String name() {
                return "EntityTick";
            }

            @Override
            public MetricKind kind() {
                return MetricKind.ENTITY_STATS;
            }

            @Override
            public Long sample() {
                final EntityTickMetrics m = entity.entityMetrics();
                return m != null ? 1L : 0L; // 面板占位（详情直接经 entityMetrics() 查询）
            }
        };
    }

    /** RuntimeMetrics 适配器（面板维度；可空） */
    public MetricSource<RuntimeMetrics.Snapshot> runtimeMetricsSource() {
        return new MetricSource<>() {
            @Override
            public String name() {
                return "RuntimeMetrics";
            }

            @Override
            public MetricKind kind() {
                return MetricKind.RUNTIME_STATS;
            }

            @Override
            public RuntimeMetrics.Snapshot sample() {
                final RuntimeMetrics m = runtimeMetrics;
                if (m == null) {
                    return null;
                }
                return m.snapshot();
            }
        };
    }

    /** RegionLoad 适配器（RegionLoadMonitor.getAllSnapshots() 最大负载 → TOTAL_REGIONS + 负载诊断） */
    public static MetricSource<Integer> regionLoadSource() {
        return new MetricSource<>() {
            @Override
            public String name() {
                return "RegionLoad";
            }

            @Override
            public MetricKind kind() {
                return MetricKind.TOTAL_REGIONS;
            }

            @Override
            public Integer sample() {
                try {
                    return RegionLoadMonitor.getAllSnapshots().size();
                } catch (Throwable t) {
                    return 0;
                }
            }
        };
    }

    /** IOState 适配器（IOController.latestState()；IO_QUEUE_DEPTH + IO_P99 两个维度一个源） */
    public static MetricSource<IOState> ioStateSource(IOController io) {
        return new MetricSource<>() {
            @Override
            public String name() {
                return "OLinearIO";
            }

            @Override
            public MetricKind kind() {
                return MetricKind.IO_QUEUE_DEPTH;
            }

            @Override
            public IOState sample() {
                return io.latestState();
            }
        };
    }

    // ================= 面板数据 =================

    /** 面板元数据（最近一次 collect 的聚合值；供 PolicyController 组装 ControlPanelSnapshot） */
    public PanelMetrics panelMetrics() {
        return new PanelMetrics(
                System.nanoTime(),
                lastTps, lastCpuLoad, lastSchedulerLoadPct,
                lastIoQueue, lastIoP99, lastTotalRegions, lastActiveRegions, lastWorkers, lastSyncedRegions
        );
    }

    /** 面板指标载体（内部 record） */
    public record PanelMetrics(
            long timestampNanos,
            double tps,
            double cpuLoad,
            int schedulerLoadPct,
            long ioQueueDepth,
            long ioP99Nanos,
            int totalRegions,
            int activeRegions,
            int workers,
            long syncedRegions
    ) {
    }

    /** EMA 公式（α=0.3 固定，§4.2 输入平滑） */
    public static double ema(double previous, double sample) {
        return 0.3 * sample + 0.7 * previous;
    }
}
