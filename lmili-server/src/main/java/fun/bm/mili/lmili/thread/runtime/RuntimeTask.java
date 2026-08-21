package fun.bm.mili.lmili.thread.runtime;

/**
 * 统一任务抽象 —— Runtime 中所有可调度任务的统一接口。
 *
 * <p>实现类必须保证线程安全，特别是 {@link #execute()} 方法将在 Worker 线程上执行。
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>创建线程：任意线程</li>
 *   <li>执行线程：Scheduler Worker 线程</li>
 *   <li>状态读取：任意线程（通过 {@link #state()} 获取快照）</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li>创建任务（state = CREATED）</li>
 *   <li>提交到 Scheduler（state = SCHEDULED）</li>
 *   <li>Worker 获取并执行（state = RUNNING）</li>
 *   <li>执行完成或失败（state = COMPLETED / FAILED / CANCELLED）</li>
 * </ol>
 */
public interface RuntimeTask {

    /**
     * 获取任务唯一 ID。
     */
    long taskId();

    /**
     * 获取任务关联的 Region ID。
     */
    long regionId();

    /**
     * 获取任务绑定的 Generation ID。
     *
     * <p>任务创建时必须捕获 Generation ID，
     * 如果 Generation 已过期，任务可能被拒绝执行。
     */
    long generationId();

    /**
     * 获取任务截止时间（纳秒）。
     *
     * <p>任务应该在 deadline 之前完成，否则可能被视为超时。
     */
    long deadlineNanos();

    /**
     * 获取任务优先级。
     */
    TaskPriority priority();

    /**
     * 获取任务当前状态。
     */
    TaskState state();

    /**
     * 执行任务。
     *
     * <p>此方法在 Worker 线程上执行，实现类必须保证线程安全。
     *
     * @throws Exception 如果执行失败
     */
    void execute() throws Exception;

    /**
     * 取消任务。
     *
     * <p>注意：cancel 只能视为请求，不能假设任务已经停止。
     * 实现类应该尽快响应取消请求。
     */
    default void cancel() {
        // 默认实现：无操作
    }

    /**
     * 任务优先级。
     */
    enum TaskPriority {
        /** 最高优先级（系统关键任务） */
        CRITICAL,
        /** 高优先级（玩家交互相关） */
        HIGH,
        /** 普通优先级（常规 Tick） */
        NORMAL,
        /** 低优先级（后台任务） */
        LOW,
        /** 最低优先级（空闲时执行） */
        IDLE
    }

    /**
     * 任务状态。
     */
    enum TaskState {
        /** 任务已创建 */
        CREATED,
        /** 任务已提交到 Scheduler */
        SCHEDULED,
        /** 任务正在执行 */
        RUNNING,
        /** 任务已完成 */
        COMPLETED,
        /** 任务执行失败 */
        FAILED,
        /** 任务已取消 */
        CANCELLED
    }
}
