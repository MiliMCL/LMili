package fun.bm.mili.lmili.runtime.budget;

import fun.bm.mili.lmili.runtime.io.RegionLoadSnapshot;
import fun.bm.mili.lmili.runtime.policy.PressureState;
import fun.bm.mili.utils.performance.TPSTracker;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * 预算分配器 —— 决定每 region 每 tick 的预算（由 TickController 持有）（ARCHITECTURE_AdaptiveRuntime.md §3.12 / D-21）。
 *
 * <p><strong>动态推导</strong>：soft 预算不是常量，而是 {@code f(cores, tps, regionType)}：
 * <pre>
 *   baseNanos  = TickBudgetConfig.baseSoftNanos()                  // 默认 4ms
 *   coreFactor = clamp(cores / 8, 0.6, 1.6)                        // 核数越多预算越多
 *   tpsFactor  = clamp(tps / 20.0, 0.5, 1.2)                       // TPS 越低越有余量
 *   regionFactor = {HOT:1.0, NORMAL:0.8, IDLE:0.5}                 // region 类型
 *   soft = baseNanos × coreFactor × tpsFactor × regionFactor
 *   hard = soft × TickBudgetConfig.hardMultiplier()                // 默认 1.25
 * </pre>
 * 推导结果经 EMA 平滑（α=0.3）+ 钳制 [minSoftNanos, maxSoftNanos]。
 *
 * <p>每 region 每 tick 一个账本实例（§3.12 线程模型）；region 注销即清（{@link #release}）。
 */
public final class BudgetAllocator {

    private volatile TickBudgetConfig config = TickBudgetConfig.DEFAULTS;
    /** 每 region 的 EMA 平滑 soft 值（分配模板缓存；region 注销即清） */
    private final ConcurrentHashMap<Long, Double> emaCache = new ConcurrentHashMap<>();
    /** region 类型提供者（默认 NORMAL；测试/集成可注入 RegionLoadMonitor 折算结果） */
    private volatile LongFunction<RegionLoadSnapshot.RegionType> regionTypeProvider =
            id -> RegionLoadSnapshot.RegionType.NORMAL;
    /** 压力态 reserve 开关（CPU_PRESSURE 时关闭） */
    private volatile boolean reserveClosedByPolicy = false;

    /**
     * 按 region 分配预算（每 tick 新建账本实例；推导结果 EMA 平滑 + 钳制）。
     */
    public RegionTickBudget allocate(long regionId, PressureState state) {
        return computeBudget(regionId, state);
    }

    /**
     * 压力态调整：CPU_PRESSURE 时 low-priority region（IDLE）soft ×0.6，reserve 关闭（§4.5）。
     */
    public RegionTickBudget allocateUnderPressure(long regionId, PressureState state) {
        if (state == PressureState.CPU_PRESSURE) {
            final RegionTickBudget base = computeBudget(regionId, state);
            final RegionLoadSnapshot.RegionType type = regionTypeProvider.apply(regionId);
            if (type == RegionLoadSnapshot.RegionType.IDLE) {
                final long reducedSoft = (long) (base.softBudgetNanos() * 0.6);
                final long reducedHard = (long) (reducedSoft * config.hardMultiplier());
                final RegionTickBudget reduced = new RegionTickBudget(reducedSoft, reducedHard, config, false);
                return reduced;
            }
            return base;
        }
        return computeBudget(regionId, state);
    }

    private RegionTickBudget computeBudget(long regionId, PressureState state) {
        final TickBudgetConfig cfg = config;
        final double cores = Runtime.getRuntime().availableProcessors();
        final double coreFactor = clamp(cores / 8.0, 0.6, 1.6);
        final double tps;
        try {
            tps = Math.max(1.0, TPSTracker.getTPS());
        } catch (Throwable t) {
            // TPSTracker 未初始化（非服务器环境）：按 20 TPS 中性值推导
            // fall-through 用 20.0
            final double tps2 = 20.0;
            return computeBudget0(regionId, state, cfg, coreFactor, tps2, regionFactor(regionId));
        }
        final double tpsFactor = clamp(tps / 20.0, 0.5, 1.2);
        return computeBudget0(regionId, state, cfg, coreFactor, tpsFactor, regionFactor(regionId));
    }

    private RegionTickBudget computeBudget0(long regionId, PressureState state, TickBudgetConfig cfg,
                                            double coreFactor, double tpsFactor, double regionFactor) {
        double soft = cfg.baseSoftNanos() * coreFactor * tpsFactor * regionFactor;
        // EMA 平滑（α=0.3）+ 钳制 [minSoft, maxSoft]（防逐周期抖动，§4.5）
        final double prev = emaCache.getOrDefault(regionId, soft);
        soft = MetricsEma.ema(prev, soft, cfg.emaAlpha());
        soft = clamp(soft, cfg.minSoftNanos(), cfg.maxSoftNanos());
        emaCache.put(regionId, soft);

        final long softNanos = (long) soft;
        final long hardNanos = Math.max(softNanos + 1, (long) (softNanos * cfg.hardMultiplier()));
        final boolean reserve = !reserveClosedByPolicy && state != PressureState.CPU_PRESSURE;
        return new RegionTickBudget(softNanos, hardNanos, cfg, reserve);
    }

    private double regionFactor(long regionId) {
        return switch (regionTypeProvider.apply(regionId)) {
            case HOT -> 1.0;
            case NORMAL -> 0.8;
            case IDLE -> 0.5;
        };
    }

    /** 预算释放（region 注销） */
    public void release(long regionId) {
        emaCache.remove(regionId);
    }

    /** 压力态策略：关闭/打开 reserve 池（由 TickController 经受控通道调用） */
    public void setReserveClosed(boolean closed) {
        this.reserveClosedByPolicy = closed;
    }

    public boolean isReserveClosed() {
        return reserveClosedByPolicy;
    }

    /** 应用 BudgetPolicy（soft/hard 基准覆盖；仅 PolicyController 编排调用） */
    public void applyPolicy(fun.bm.mili.lmili.runtime.policy.BudgetPolicy policy) {
        if (policy != null && policy.regionSoftNanos() > 0) {
            this.config = this.config.withBase(policy.regionSoftNanos());
        }
    }

    /** 配置覆盖（测试/调优；运行期建议走 applyPolicy 受控通道） */
    public void setConfig(TickBudgetConfig cfg) {
        if (cfg != null) {
            this.config = cfg;
        }
    }

    public TickBudgetConfig config() {
        return config;
    }

    /** region 类型提供者覆盖（测试/集成：从 RegionLoadMonitor 折算） */
    public void setRegionTypeProvider(LongFunction<RegionLoadSnapshot.RegionType> provider) {
        if (provider != null) {
            this.regionTypeProvider = provider;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 内聚 EMA 公式（α 由配置注入；MetricsController.ema 为静态工具，此处独立实现避免跨包循环依赖） */
    static final class MetricsEma {
        static double ema(double prev, double sample, double alpha) {
            return alpha * sample + (1.0 - alpha) * prev;
        }
    }
}
