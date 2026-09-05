package fun.bm.mili.lmili.thread.regiontick.executor;

/**
 * DAG 节点派发时共享 Scheduler 不可用（未接线 / 已关闭 / admission 拒绝）。
 *
 * <p>这是 P0-1（DAG → Shared Scheduler 架构断链修复）中的 <b>安全 fallback 契约</b>：
 * <ul>
 *   <li>不 NPE —— 统一抛出类型化异常</li>
 *   <li>不 silently drop tick —— 由 {@link DagExecutionEngine} 捕获后显式 fail 整个 DAG
 *       （完成阶段以异常结束，调用方观察到失败而不会永久挂起）</li>
 *   <li>不产生重复调度 —— 拒绝路径不重新入队、不重试</li>
 *   <li>不绕过生命周期管理 —— 拒绝路径绝不直接在本线程执行节点 body</li>
 * </ul>
 *
 * <p>调用方（DAG 引擎）应把本异常视为 DAG 级失败：记录日志、标记 state、
 * fail handle、通知 tick barrier，而不是忽略。</p>
 */
public final class DagSchedulerUnavailableException extends RuntimeException {

    public DagSchedulerUnavailableException(final String message) {
        super(message);
    }

    public DagSchedulerUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}