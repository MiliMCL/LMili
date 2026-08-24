package fun.bm.mili.lmili.runtime.policy;

/**
 * 状态动作 —— 状态迁移时 PolicyController 执行的动作集合（ARCHITECTURE_AdaptiveRuntime.md §3.10 / §4.2 状态动作汇总表）。
 * 各动作委托给对应 Controller 的受控方法；实现由 PolicyController 提供。
 */
public interface StateAction {

    /** 进入 NORMAL：恢复默认预算、恢复 fan-out、恢复 flush 节奏 */
    void onEnterNormal();

    /** 退出 NORMAL（进入压力态）：记录原因 */
    void onExitNormal(PressureState next);

    /** 进入 CPU_PRESSURE：低优先级 Entity 预算 ×0.5、background 任务降速、fan-out 限制 */
    void onEnterCpuPressure();

    void onExitCpuPressure();

    /** 进入 IO_PRESSURE：背景 flush 降级、关键 flush 提高、非关键压缩暂缓 */
    void onEnterIoPressure();

    void onExitIoPressure();
}
