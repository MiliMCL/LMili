package fun.bm.mili.lmili.thread.scheduler.tick;

/**
 * Tick 生命周期状态。
 *
 * <pre>
 * CREATED
 *   ↓
 * RUNNING
 *   ↓
 * DRAINING
 *   ↓
 * COMPLETE
 * </pre>
 */
public enum TickState {
    /** Tick 已创建，尚未开始执行。 */
    CREATED,
    /** Tick 正在执行，任务已提交。 */
    RUNNING,
    /** Tick 正在排空，已密封，等待剩余任务完成。 */
    DRAINING,
    /** Tick 已完成（成功或失败）。 */
    COMPLETE
}
