package fun.bm.mili.lmili.runtime;

/**
 * 降级原因 —— 供 {@code GlobalHealthSnapshot}/{@code ControlPanelSnapshot} 面板展示与审计
 * （ARCHITECTURE_AdaptiveRuntime.md §3.2，由本实施新建的最小类型）。
 */
public enum DegradeReason {
    /** 未降级 */
    NONE,
    /** 手动 /lmili control degrade */
    MANUAL,
    /** fail-safe：全部指标源失败或聚合异常（§6.2） */
    METRICS_FAILURE,
    /** 压力状态机内部连续失败（§6.2：连续 5 次 → DEGRADED） */
    STATE_MACHINE_FAILURE,
    /** 发散检测：连续 10 周期恶化且策略已到最激进档（§6.2） */
    DIVERGENCE,
    /** 策略通道非法输入（服务端侧校验拒绝，见 §6.1） */
    POLICY_INVALID,
    /** 关闭编排（终态） */
    SHUTDOWN
}
