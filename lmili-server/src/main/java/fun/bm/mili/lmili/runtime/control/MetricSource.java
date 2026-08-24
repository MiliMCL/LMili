package fun.bm.mili.lmili.runtime.control;

/**
 * 指标源 —— 现有类无需改动即可接入（包装为 MetricSource 适配器即可）（ARCHITECTURE_AdaptiveRuntime.md §3.8）。
 *
 * @param <T> 采样值类型（Number / IOState 等）
 */
public interface MetricSource<T> {

    /** 源名称（诊断/面板用） */
    String name();

    /** 该源提供哪类信号（MetricsController 据此聚合） */
    MetricKind kind();

    /** 非阻塞采样；失败/不可用返回 null（调用方走 fail-safe，§6.2） */
    T sample();

    /** 采样时间戳（纳秒）；用于 2s 过期判定（failSafeStaleNanos） */
    default long timestampNanos() {
        return System.nanoTime();
    }
}
