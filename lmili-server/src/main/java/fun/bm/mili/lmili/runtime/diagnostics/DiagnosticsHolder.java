package fun.bm.mili.lmili.runtime.diagnostics;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 长尾诊断单例 holder（§12 P2-3 LMili_Next_Step_Optimization.md）。
 *
 * <p>被 {@link fun.bm.mili.lmili.runtime.MiliRuntime} 装配时注入；runtime 未装配时返回 null。
 * 命令路径（{@code /lmili debug longtail}）通过本 holder 只读访问；tick 完成路径
 * （MiliTickRegionScheduler / UnifiedRuntime）调用 {@link #record}。
 *
 * <p>为什么独立于 MiliRuntime：
 * <ul>
 *   <li>§18.1 "优先复用已有接口" —— 现有 RuntimeMetrics 已记录指标，本 holder 只补"事件级"
 *       结构化诊断，不重叠也不抢接口。</li>
 *   <li>§18.9 "不要重构已经稳定工作的模块" —— MiliRuntime 不动；诊断组件只通过静态 hook 接入。</li>
 * </ul>
 */
public final class DiagnosticsHolder {

    private static final AtomicReference<LongTailTickDiagnostics> INSTANCE = new AtomicReference<>();

    private DiagnosticsHolder() {}

    public static LongTailTickDiagnostics get() {
        return INSTANCE.get();
    }

    /** 装配期调用（runtime 启动时）。重复注入会替换（但不应发生，由装配期单调性保证）。 */
    public static void install(LongTailTickDiagnostics diag) {
        if (diag != null) {
            INSTANCE.set(diag);
        }
    }

    /** 测试隔离 */
    public static void resetForTest() {
        INSTANCE.set(null);
    }

    /**
     * 转发一条记录（holder 为 null 时静默丢弃，调用方零负担）。
     */
    public static void record(long tickId, long regionId, Thread thread, String taskName,
                              long elapsedNanos, LongTailTickDiagnostics.BlockingReason reason,
                              long chunkWaitNanos, int chunkWaitCount,
                              long dependencyWaitNanos, int dependencyWaitCount,
                              boolean pluginTask, int entityCount) {
        final LongTailTickDiagnostics diag = INSTANCE.get();
        if (diag == null) {
            return;
        }
        try {
            diag.record(tickId, regionId, thread, taskName, elapsedNanos, reason,
                    chunkWaitNanos, chunkWaitCount,
                    dependencyWaitNanos, dependencyWaitCount,
                    pluginTask, entityCount);
        } catch (Throwable ignored) {
            // 诊断路径不允许反向影响 tick
        }
    }
}
