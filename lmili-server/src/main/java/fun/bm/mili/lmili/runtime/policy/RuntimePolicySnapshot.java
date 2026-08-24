package fun.bm.mili.lmili.runtime.policy;

import fun.bm.mili.lmili.runtime.io.FlushPriority;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 运行期策略快照 —— 所有策略变更的原子载体（不可变，版本单调递增）（ARCHITECTURE_AdaptiveRuntime.md §3.17 / D-04）。
 * 经 PolicyController 的 AtomicReference CAS 发布；读取方永远看到完整一致快照。
 */
public record RuntimePolicySnapshot(
        long version,
        PressureState pressure,
        boolean parallelTickEnabled,
        boolean oLinearEnabled,
        boolean entityThrottleEnabled,
        boolean backpressureActive,
        int cpuBudgetPct,
        int ioBudgetPct,
        int schedulerWorkers,
        int ioWorkers,
        double entityLowPriorityFactor,
        double backgroundTaskFactor,
        int maxFanOut,
        int maxEntitiesPerRegion,
        long regionTickSoftBudgetNanos,
        long regionTickHardBudgetNanos,
        Map<Long, FlushPriority> flushPriorities,
        String appliedBy,
        long appliedAtNanos
) {

    /** 初始快照（版本 0 —— PolicyController 装配后立即发布版本 1） */
    public static RuntimePolicySnapshot initial() {
        return new RuntimePolicySnapshot(
                0, PressureState.NORMAL, true, true, false, false,
                75, 60, 0, 0,
                1.0, 1.0,
                Math.max(1, Runtime.getRuntime().availableProcessors()),
                0,
                4_000_000L, 5_000_000L,
                Collections.emptyMap(),
                "SYSTEM", System.nanoTime()
        );
    }

    /**
     * 基于当前快照派生新快照（copy-on-write；仅 PolicyController 使用，§6.4）。
     */
    public RuntimePolicySnapshot derive(long newVersion, Function<Builder, Builder> mutate) {
        final Builder builder = new Builder(this);
        return mutate.apply(builder).build(newVersion);
    }

    /** 拷贝构造用 Builder（copy-on-write） */
    public static final class Builder {
        private PressureState pressure;
        private boolean parallelTickEnabled;
        private boolean oLinearEnabled;
        private boolean entityThrottleEnabled;
        private boolean backpressureActive;
        private int cpuBudgetPct;
        private int ioBudgetPct;
        private int schedulerWorkers;
        private int ioWorkers;
        private double entityLowPriorityFactor;
        private double backgroundTaskFactor;
        private int maxFanOut;
        private int maxEntitiesPerRegion;
        private long regionTickSoftBudgetNanos;
        private long regionTickHardBudgetNanos;
        private final Map<Long, FlushPriority> flushPriorities;
        private String appliedBy;
        private long appliedAtNanos;

        Builder(RuntimePolicySnapshot base) {
            this.pressure = base.pressure();
            this.parallelTickEnabled = base.parallelTickEnabled();
            this.oLinearEnabled = base.oLinearEnabled();
            this.entityThrottleEnabled = base.entityThrottleEnabled();
            this.backpressureActive = base.backpressureActive();
            this.cpuBudgetPct = base.cpuBudgetPct();
            this.ioBudgetPct = base.ioBudgetPct();
            this.schedulerWorkers = base.schedulerWorkers();
            this.ioWorkers = base.ioWorkers();
            this.entityLowPriorityFactor = base.entityLowPriorityFactor();
            this.backgroundTaskFactor = base.backgroundTaskFactor();
            this.maxFanOut = base.maxFanOut();
            this.maxEntitiesPerRegion = base.maxEntitiesPerRegion();
            this.regionTickSoftBudgetNanos = base.regionTickSoftBudgetNanos();
            this.regionTickHardBudgetNanos = base.regionTickHardBudgetNanos();
            this.flushPriorities = new LinkedHashMap<>(base.flushPriorities());
            this.appliedBy = base.appliedBy();
            this.appliedAtNanos = base.appliedAtNanos();
        }

        public Builder pressure(PressureState v) { this.pressure = v; return this; }
        public Builder parallelTickEnabled(boolean v) { this.parallelTickEnabled = v; return this; }
        public Builder oLinearEnabled(boolean v) { this.oLinearEnabled = v; return this; }
        public Builder entityThrottleEnabled(boolean v) { this.entityThrottleEnabled = v; return this; }
        public Builder backpressureActive(boolean v) { this.backpressureActive = v; return this; }
        public Builder cpuBudgetPct(int v) { this.cpuBudgetPct = v; return this; }
        public Builder ioBudgetPct(int v) { this.ioBudgetPct = v; return this; }
        public Builder schedulerWorkers(int v) { this.schedulerWorkers = v; return this; }
        public Builder ioWorkers(int v) { this.ioWorkers = v; return this; }
        public Builder entityLowPriorityFactor(double v) { this.entityLowPriorityFactor = v; return this; }
        public Builder backgroundTaskFactor(double v) { this.backgroundTaskFactor = v; return this; }
        public Builder maxFanOut(int v) { this.maxFanOut = v; return this; }
        public Builder maxEntitiesPerRegion(int v) { this.maxEntitiesPerRegion = v; return this; }
        public Builder regionTickSoftBudgetNanos(long v) { this.regionTickSoftBudgetNanos = v; return this; }
        public Builder regionTickHardBudgetNanos(long v) { this.regionTickHardBudgetNanos = v; return this; }

        public Builder flushPriority(long regionId, FlushPriority p) {
            this.flushPriorities.put(regionId, p);
            return this;
        }

        public Builder flushPriorities(Map<Long, FlushPriority> map) {
            this.flushPriorities.clear();
            this.flushPriorities.putAll(map);
            return this;
        }

        public Builder appliedBy(String v) { this.appliedBy = v; return this; }
        public Builder appliedAtNanos(long v) { this.appliedAtNanos = v; return this; }

        public RuntimePolicySnapshot build(long newVersion) {
            return new RuntimePolicySnapshot(
                    newVersion, pressure, parallelTickEnabled, oLinearEnabled, entityThrottleEnabled,
                    backpressureActive, cpuBudgetPct, ioBudgetPct, schedulerWorkers, ioWorkers,
                    entityLowPriorityFactor, backgroundTaskFactor, maxFanOut, maxEntitiesPerRegion,
                    regionTickSoftBudgetNanos, regionTickHardBudgetNanos,
                    Collections.unmodifiableMap(new LinkedHashMap<>(flushPriorities)),
                    appliedBy, appliedAtNanos
            );
        }
    }
}
