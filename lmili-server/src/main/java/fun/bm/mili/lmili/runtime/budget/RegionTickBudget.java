package fun.bm.mili.lmili.runtime.budget;

import fun.bm.mili.lmili.runtime.task.TickTaskType;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region Tick 预算 —— 单个 region 单个 tick 的 CPU 时间账本（需求 #3）（ARCHITECTURE_AdaptiveRuntime.md §3.12）。
 *
 * <p><strong>禁止魔法数字</strong>（D-21）：默认示例（4ms 总预算）只是 {@link TickBudgetConfig} 的基准值，
 * 实际预算由 {@link BudgetAllocator} 依据 CPU 核数、当前 TPS、region 类型每控制周期动态推导。
 *
 * <p>双阈值语义：
 * <pre>
 *   softBudgetNanos ─ 常规可消费上限（分池：Entity 42.5% / Block 20% / Chunk 25% / Plugin 7.5% / Reserve 5%）
 *   hardBudgetNanos ─ 硬上限（soft × hardMultiplier，默认 1.25）
 * </pre>
 * 池间不可挪用；soft~hard 窗口仅 ENTITY 在 NORMAL 态可动用（{@link #tryAcquireSoftWindow}）；
 * 超过 hard 一律拒绝。预留 5% 是"并行 tick 不得无条件塞任务"的缓冲，仅 reserve 开启时可动用。
 *
 * <p>线程模型：单写者（该 region 的 tick 执行者）+ 控制线程只读（诊断）；计数器 long + CAS（lock-free，§6.5）。
 */
public final class RegionTickBudget {

    /** 示例基准（可配，非硬编码语义；实际值由 BudgetAllocator 推导覆盖） */
    public static final long DEFAULT_SOFT_NANOS = 4_000_000L;
    /** hard = soft × 1.25 */
    public static final double DEFAULT_HARD_MULTIPLIER = 1.25;

    private static final int RESERVE_IDX = TickTaskType.values().length; // 分池数组多一格放 reserve

    private final long softBudgetNanos;
    private final long hardBudgetNanos;
    private final long reserveNanos;

    /** 总账（起点 = hardBudgetNanos；含 soft~hard 窗口与 reserve） */
    private final AtomicLong remaining;
    /** 分池（ENTITY/BLOCK/CHUNK/FLUID/PLUGIN + RESERVE） */
    private final AtomicLong[] poolRemaining;
    private final AtomicBoolean reserveOpen;

    public RegionTickBudget(long softBudgetNanos, long hardBudgetNanos, TickBudgetConfig config, boolean reserveOpen) {
        this.softBudgetNanos = softBudgetNanos;
        this.hardBudgetNanos = Math.max(softBudgetNanos, hardBudgetNanos);
        this.remaining = new AtomicLong(this.hardBudgetNanos);
        this.poolRemaining = new AtomicLong[TickTaskType.values().length + 1];
        long allocated = 0;
        for (TickTaskType t : TickTaskType.values()) {
            if (t == TickTaskType.SAVE) {
                continue; // SAVE 不占 CPU 预算
            }
            final long share = config.defaultEstimateNanos(t);
            this.poolRemaining[t.ordinal()] = new AtomicLong(Math.min(share, this.softBudgetNanos));
            allocated += share;
        }
        this.reserveNanos = Math.max(0, softBudgetNanos - allocated);
        this.poolRemaining[RESERVE_IDX] = new AtomicLong(this.reserveNanos);
        this.reserveOpen = new AtomicBoolean(reserveOpen);
    }

    public long softBudgetNanos() {
        return softBudgetNanos;
    }

    public long hardBudgetNanos() {
        return hardBudgetNanos;
    }

    /**
     * 尝试获取预算（非阻塞，lock-free）。超 hard 上限的申请直接拒绝 —— 调用方必须处理 false（退避/降级，§6.3 R1）。
     */
    public boolean tryAcquire(TickTaskType type, long nanos) {
        if (type == TickTaskType.SAVE) {
            return true; // SAVE 走 IO 通道，不占 tick CPU 预算
        }
        if (nanos <= 0) {
            return true;
        }
        if (nanos > hardBudgetNanos) {
            return false; // 硬上限：任何类型都不允许（防 tick 超支累积拖垮整轮）
        }
        // 1. 总账硬顶
        if (!tryDeduct(remaining, nanos)) {
            return false;
        }
        // 2. 分池（池间不可挪用，防止 Plugin 挤占 Entity）
        final AtomicLong pool = poolRemaining[type.ordinal()];
        if (pool != null && tryDeduct(pool, nanos)) {
            return true;
        }
        // 3. 分池耗尽 → reserve（仅 ParallelExecutor 的 fan-out 消耗场景）
        if (reserveOpen.get() && tryDeduct(poolRemaining[RESERVE_IDX], nanos)) {
            return true;
        }
        // 未消费成功 → 归还总账
        remaining.addAndGet(nanos);
        return false;
    }

    /**
     * soft 阈值之上的受限获取：仅 ENTITY 在 NORMAL 态允许（soft~hard 窗口，吸收计时噪声）。
     * 由 TickController 先行校验状态与类型后调用。
     */
    public boolean tryAcquireSoftWindow(TickTaskType type, long nanos) {
        if (type != TickTaskType.ENTITY) {
            return false;
        }
        if (nanos <= 0) {
            return true;
        }
        if (nanos > hardBudgetNanos) {
            return false;
        }
        return tryDeduct(remaining, nanos); // 只动总账（分池已耗尽）
    }

    /** 归还预算（正常完成/提前结束） */
    public void release(long nanos) {
        if (nanos <= 0) {
            return;
        }
        remaining.accumulateAndGet(nanos, (cur, add) -> Math.min(hardBudgetNanos, cur + add));
    }

    /** 剩余（含 reserve） */
    public long remaining() {
        return remaining.get();
    }

    /** 压力态：关闭 reserve 池 */
    public void closeReserve() {
        reserveOpen.set(false);
    }

    public void openReserve() {
        reserveOpen.set(true);
    }

    public boolean isReserveOpen() {
        return reserveOpen.get();
    }

    /** 诊断：各类预算消费统计 */
    public BudgetStats stats() {
        final Map<TickTaskType, Long> pools = new EnumMap<>(TickTaskType.class);
        for (TickTaskType t : TickTaskType.values()) {
            if (t == TickTaskType.SAVE) {
                continue;
            }
            final AtomicLong pool = poolRemaining[t.ordinal()];
            pools.put(t, pool != null ? pool.get() : 0);
        }
        return new BudgetStats(softBudgetNanos, hardBudgetNanos, remaining.get(), reserveOpen.get(), pools);
    }

    private static boolean tryDeduct(AtomicLong counter, long nanos) {
        while (true) {
            final long cur = counter.get();
            if (cur < nanos) {
                return false;
            }
            if (counter.compareAndSet(cur, cur - nanos)) {
                return true;
            }
        }
    }
}
