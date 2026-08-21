package fun.bm.mili.lmili.thread.runtime.generation;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TickGeneration —— 单次 Tick 的生命周期抽象。
 *
 * <p>每次 Tick 开始时生成新的、单调递增的 generationId。
 * 任务创建时必须捕获 generationId，禁止依赖"当前 Context generation"推断任务身份。
 *
 * <h3>状态机</h3>
 * <pre>
 * CREATED
 *    ↓
 * RUNNING
 *    ↓
 * DEADLINE_EXCEEDED
 *    ↓
 * DRAINING
 *    ↓
 * COMPLETED / CANCELLED
 * </pre>
 *
 * <h3>线程安全</h3>
 * <p>本类是线程安全的。所有状态转换都通过 CAS 操作完成。
 *
 * <h3>内存可见性</h3>
 * <p>{@code state} 和 {@code completedTasks} 使用 AtomicReference/AtomicInteger 保证happens-before。
 * {@code generationId} 和 {@code expectedTasks} 在构造后不可变。
 *
 * <h3>并发正确性保证</h3>
 * <ul>
 *   <li>旧 Generation 的任务迟到完成不得修改新 Generation 状态</li>
 *   <li>Timeout 不等于 Task Stopped —— cancel(true) 只是 interrupt request</li>
 *   <li>旧 Tick 的任务绝对不能影响新 Tick</li>
 * </ul>
 */
public final class TickGeneration {

    /**
     * Generation 状态枚举。
     */
    public enum State {
        /** Generation 已创建，尚未开始执行 */
        CREATED,
        /** Generation 正在执行，任务已提交 */
        RUNNING,
        /** Tick 已超过 deadline，进入 draining */
        DEADLINE_EXCEEDED,
        /** Generation 正在排空，已密封，等待剩余任务完成 */
        DRAINING,
        /** Generation 已完成（所有任务正常结束） */
        COMPLETED,
        /** Generation 已取消（被主动取消或强制终止） */
        CANCELLED
    }

    /** 单调递增的 Generation ID */
    private final long generationId;

    /** 本次 Tick 期望完成的任务总数 */
    private final int expectedTasks;

    /** 已完成的任务数 */
    private final AtomicInteger completedTasks;

    /** Deadline 时间戳（纳秒） */
    private final long deadlineNanos;

    /** 当前状态 */
    private final AtomicReference<State> state;

    /** 失败原因（如果有） */
    private volatile Throwable failure;

    /**
     * 创建 TickGeneration。
     *
     * @param generationId 单调递增的 generation ID
     * @param expectedTasks 期望完成的任务数
     * @param deadlineNanos deadline 时间戳（纳秒）
     */
    public TickGeneration(long generationId, int expectedTasks, long deadlineNanos) {
        this.generationId = generationId;
        this.expectedTasks = expectedTasks;
        this.deadlineNanos = deadlineNanos;
        this.completedTasks = new AtomicInteger(0);
        this.state = new AtomicReference<>(State.CREATED);
    }

    /**
     * 获取 Generation ID。
     */
    public long generationId() {
        return generationId;
    }

    /**
     * 获取期望完成的任务数。
     */
    public int expectedTasks() {
        return expectedTasks;
    }

    /**
     * 获取已完成的任务数。
     */
    public int completedTasks() {
        return completedTasks.get();
    }

    /**
     * 获取 deadline。
     */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * 获取当前状态。
     */
    public State state() {
        return state.get();
    }

    /**
     * 获取失败原因。
     */
    public Throwable failure() {
        return failure;
    }

    /**
     * 尝试从 CREATED 转换到 RUNNING。
     *
     * @return true 如果转换成功
     */
    public boolean tryBegin() {
        return state.compareAndSet(State.CREATED, State.RUNNING);
    }

    /**
     * 标记任务完成 —— 仅当 generation 处于 RUNNING 或 DEADLINE_EXCEEDED 状态时有效。
     *
     * <p>线程安全：通过 CAS 确保状态一致性。
     *
     * @return true 如果成功增加完成计数
     */
    public boolean markTaskCompleted() {
        State current = state.get();
        if (current != State.RUNNING && current != State.DEADLINE_EXCEEDED) {
            return false;
        }
        completedTasks.incrementAndGet();
        return true;
    }

    /**
     * 检查是否超时 —— 如果已超时且状态为 RUNNING，转换为 DEADLINE_EXCEEDED。
     *
     * @param nowNanos 当前时间（纳秒）
     * @return true 如果本次调用触发了超时转换
     */
    public boolean checkTimeout(long nowNanos) {
        if (nowNanos < deadlineNanos) {
            return false;
        }
        // 只有 RUNNING 状态才能转换为 DEADLINE_EXCEEDED
        return state.compareAndSet(State.RUNNING, State.DEADLINE_EXCEEDED);
    }

    /**
     * 检查是否已超时（不触发转换）。
     *
     * @param nowNanos 当前时间（纳秒）
     */
    public boolean isTimedOut(long nowNanos) {
        return nowNanos >= deadlineNanos;
    }

    /**
     * 尝试进入 DRAINING 状态。
     *
     * <p>从 DEADLINE_EXCEEDED 转换到 DRAINING，表示不再接受新任务。
     *
     * @return true 如果转换成功
     */
    public boolean tryBeginDraining() {
        return state.compareAndSet(State.DEADLINE_EXCEEDED, State.DRAINING);
    }

    /**
     * 尝试从 RUNNING 直接进入 DRAINING（所有任务已完成时的快速路径）。
     *
     * @return true 如果转换成功
     */
    public boolean trySealFromRunning() {
        return state.compareAndSet(State.RUNNING, State.DRAINING);
    }

    /**
     * 完成 Generation —— 从 DRAINING 转换到 COMPLETED。
     *
     * @return true 如果转换成功
     */
    public boolean complete() {
        return state.compareAndSet(State.DRAINING, State.COMPLETED);
    }

    /**
     * 取消 Generation —— 从 DRAINING 或 DEADLINE_EXCEEDED 转换到 CANCELLED。
     *
     * @return true 如果转换成功
     */
    public boolean cancel() {
        State current = state.get();
        if (current == State.COMPLETED || current == State.CANCELLED) {
            return false;
        }
        return state.compareAndSet(current, State.CANCELLED);
    }

    /**
     * 记录失败。
     *
     * @param error 失败原因
     */
    public void reportFailure(Throwable error) {
        this.failure = error;
    }

    /**
     * 检查是否处于终止状态（COMPLETED 或 CANCELLED）。
     */
    public boolean isTerminal() {
        State s = state.get();
        return s == State.COMPLETED || s == State.CANCELLED;
    }

    /**
     * 检查所有任务是否已完成。
     */
    public boolean allTasksCompleted() {
        return completedTasks.get() >= expectedTasks;
    }

    /**
     * 检查是否可以进入 DRAINING（所有任务已完成）。
     */
    public boolean canDrain() {
        return allTasksCompleted() && state.get() == State.RUNNING;
    }

    @Override
    public String toString() {
        return "TickGeneration{id=" + generationId +
                ", state=" + state.get() +
                ", completed=" + completedTasks.get() + "/" + expectedTasks +
                ", deadline=" + deadlineNanos + "}";
    }
}
