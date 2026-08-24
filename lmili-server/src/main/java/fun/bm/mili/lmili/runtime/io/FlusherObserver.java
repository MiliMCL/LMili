package fun.bm.mili.lmili.runtime.io;

import fun.bm.mili.lmili.data.OptimizedLinearRegionFile;
import org.jetbrains.annotations.NotNull;

/**
 * flusher 观察者钩子（新增到 OptimizedLinearRegionFileFlusher 的可选构造参数，见 ARCHITECTURE_AdaptiveRuntime.md §5.4）。
 * 默认 null 时 flusher 行为与现状完全一致（D-08）。
 *
 * <p>实现要求：三个回调都<strong>必须快速返回、不得抛异常</strong>；
 * flusher 侧对 observer 异常一律捕获吞掉并记日志（observer 永不反向破坏 flusher）。
 */
public interface FlusherObserver {

    /** checker 周期结束回调（flusher 单线程调用） */
    void onCycleFinished(long cycleNanos);

    /**
     * sync 任务派发前回调（flusher checker 线程调用）。
     *
     * @return false = 本次跳过派发（记入延后；flusher 会 clearBeingSynced 保证下周期可重估，数据不丢失）
     */
    boolean onDispatch(@NotNull OptimizedLinearRegionFile file);

    /**
     * sync 任务实际进入 IO 池执行前回调（IO worker 线程；默认空实现）。
     *
     * <p>签名调整说明（不改语义）：设计文档 §3.16 的观察者钩子只有三个回调
     * （onCycleFinished / onDispatch / onSyncCompleted），但桥接侧需要准确跟踪
     * "正在执行的 sync 数"（IOState.activeWorkers）以支撑有界排水 —— 由桥
     * （{@code OLinearFlusherBridge}）覆盖本方法在 onDispatch 放行后、syncIfNeeded
     * 前记账。默认空实现保证旧实现（未覆盖）零行为变化。
     */
    default void onSyncDispatched() {
        // 默认空实现：旧观察者无需感知派发事件
    }

    /** sync 完成回调（成功/失败都调用；IO worker 线程） */
    void onSyncCompleted(@NotNull OptimizedLinearRegionFile file, long nanos, long bytes, boolean success);
}
