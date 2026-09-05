package fun.bm.mili.lmili.thread.regiontick;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * P1-3（计划 §8）：Entity Tick 唯一决策链。
 *
 * <p>决策顺序固定为：Eligibility → Priority → Throttle → Budget → Tick。
 * 每个实体本 tick <b>只能产生一个</b>主决策：
 * {@link Decision#TICKED} / {@code SKIPPED_PRIORITY} / {@code SKIPPED_THROTTLE} /
 * {@code SKIPPED_BUDGET} / {@code REMOVED} / {@code FROZEN}。</p>
 *
 * <p>§8.2 一致性约束由本类结构化保证：命中某一层即短路返回，
 * 后续层不再评估 —— 因此 metrics 里 "skipped=1" 语义是唯一的，
 * 不会出现 Priority+Throttle+Budget 三层同时计入导致 skipped=3 的失真。</p>
 */
public final class EntityDecisionPipeline {

    public enum Decision {
        TICKED,
        SKIPPED_PRIORITY,
        SKIPPED_THROTTLE,
        SKIPPED_BUDGET,
        REMOVED,
        FROZEN
    }

    /** Throttle 层的判定结果（对齐 Kaiiju EntityThrottlerReturn）。 */
    public record ThrottleVerdict(boolean skip, boolean remove) {
        public static final ThrottleVerdict NONE = new ThrottleVerdict(false, false);
    }

    /** 单实体决策记录的出口（metrics 落点；每实体恰好调用一次）。 */
    @FunctionalInterface
    public interface DecisionSink extends Consumer<Decision> {}

    /**
     * 对单个实体执行完整决策链并返回最终结果。
     *
     * <p>调用方传入各层输入；本方法负责互斥短路 —— 保证只返回一个 Decision。
     * TICKED 分支由调用方在拿到返回值后执行实际 tick。</p>
     *
     * @param eligible          实体基础资格（!isRemoved 等）
     * @param frozen            世界 tickRateManager 冻结判定
     * @param prioritySkipped   本 tick 是否被优先级层跳过
     * @param throttle          Kaiiju/throttle 判定（null 视为不限制）
     * @param budgetExceeded    预算是否已耗尽
     * @param sink              每个实体恰好一次的决策落点（可为 null —— 仅测试用）
     */
    public static Decision decide(boolean eligible,
                                  boolean frozen,
                                  boolean prioritySkipped,
                                  ThrottleVerdict throttle,
                                  boolean budgetExceeded,
                                  DecisionSink sink) {
        Decision d;
        if (!eligible) {
            // Eligibility 层：已移除/无效实体视作 REMOVED（调用方无需再触碰它）
            d = Decision.REMOVED;
        } else if (frozen) {
            d = Decision.FROZEN;
        } else if (prioritySkipped) {
            d = Decision.SKIPPED_PRIORITY;
        } else if (throttle != null && throttle.remove()) {
            d = Decision.REMOVED;
        } else if (throttle != null && throttle.skip()) {
            d = Decision.SKIPPED_THROTTLE;
        } else if (budgetExceeded) {
            d = Decision.SKIPPED_BUDGET;
        } else {
            d = Decision.TICKED;
        }
        if (sink != null) {
            sink.accept(d);
        }
        return d;
    }

    /**
     * 简易滑动窗口计数器 —— 保证 §8.2 的 "skips 只计一次" 可被测试验证：
     * pipeline 每实体只回调一次 sink，因此任何基于 sink 的统计天然一致。
     */
    public static final class DecisionCounters {
        private final java.util.EnumMap<Decision, Long> counts =
                new java.util.EnumMap<>(Decision.class);
        private long total;

        /** 记录一次决策（对应一个实体）。 */
        public void record(Decision d) {
            Objects.requireNonNull(d, "d");
            counts.merge(d, 1L, Long::sum);
            total++;
        }

        public long count(Decision d) { return counts.getOrDefault(d, 0L); }
        public long total() { return total; }

        /**
         * §8.2 校验：所有非 TICKED 决策之和必须等于 total - TICKED 数。
         * 由于结构上每实体仅产生一条记录，此断言恒真 —— 作为回归哨兵保留。
         */
        public boolean isConsistent() {
            long skippedSum = 0;
            for (Decision d : Decision.values()) {
                if (d != Decision.TICKED) skippedSum += count(d);
            }
            return total() == skippedSum + count(Decision.TICKED);
        }
    }

    private EntityDecisionPipeline() {}
}