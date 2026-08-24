package fun.bm.mili.lmili.runtime.policy;

/**
 * 策略命令动作 —— PolicyController 可受理的动作全集（ARCHITECTURE_AdaptiveRuntime.md §3.9，
 * 由本实施按 §4.7 状态动作表与 §6.1 权限表补齐的最小集合）。
 */
public enum CommandAction {
    /** 调度器模式 / worker 数（schedulerWorkers ∈ [1,64]） */
    SET_SCHEDULER_MODE,
    /** IO worker 数（ioWorkers ∈ [1,24]） */
    SET_IO_WORKERS,
    /** 并行 tick 开关（on/off） */
    SET_PARALLEL,
    /** 实体节流开关（on/off） */
    SET_THROTTLE,
    /** 全局 CPU 预算百分比（[10,100]） */
    SET_CPU_BUDGET,
    /** 全局 IO 预算百分比（[10,100]） */
    SET_IO_BUDGET,
    /** 手动降级（reason） */
    DEGRADE,
    /** 手动恢复（仅 DEGRADED 时有效） */
    RESTORE,
    /** 回滚到上一快照（限 1 级） */
    ROLLBACK,
    /** 设置某 region 的 flush 优先级（regionId, priority） */
    SET_FLUSH_PRIORITY,
    /** TPS 治理目标（tpsTarget ∈ [10,20]） */
    SET_TPS_TARGET,
    /** 并行 fan-out 上限（fanOut ∈ [1,64]） */
    SET_FANOUT,
    /** 压力状态机迁移（state）—— 由 GlobalController 提交，来源 STATE_MACHINE */
    APPLY_STATE
}
