package fun.bm.mili.lmili.thread.scheduler.tick;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TickContext —— 一次完整 Tick 的生命周期对象。
 *
 * <p>TickContext 不负责执行任务，只负责描述：
 * <ul>
 *   <li>当前 Tick ID</li>
 *   <li>Tick Barrier（完成追踪）</li>
 *   <li>Tick 生命周期状态</li>
 *   <li>Tick 级别错误/取消状态</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * <pre>
 * TickCoordinator
 *     │
 *     ├── register Region A
 *     ├── register Region B
 *     ├── register Region C
 *     │
 *     └── seal()
 *              │
 *              ▼
 *        等待 pending == 0
 *              │
 *              ▼
 *         Tick Complete
 * </pre>
 *
 * <h3>线程安全</h3>
 * <p>本类是线程安全的。
 */
public final class TickContext {

    private final long tickId;

    private final TickBarrier barrier;

    private final AtomicReference<TickState> state = new AtomicReference<>(TickState.CREATED);

    private volatile Throwable failure;

    /**
     * 创建 TickContext。
     *
     * @param tickId 当前 tick ID
     */
    public TickContext(long tickId) {
        this.tickId = tickId;
        this.barrier = new TickBarrier();
    }

    /**
     * 获取当前 Tick ID。
     */
    public long tickId() {
        return tickId;
    }

    /**
     * 获取 Tick Barrier。
     */
    public TickBarrier barrier() {
        return barrier;
    }

    /**
     * 获取当前 Tick 状态。
     */
    public TickState state() {
        return state.get();
    }

    /**
     * 转换到新状态。
     *
     * @param expected 期望的当前状态
     * @param newState 新状态
     * @return true 如果转换成功
     */
    public boolean transitionTo(TickState expected, TickState newState) {
        return state.compareAndSet(expected, newState);
    }

    /**
     * 注册一个待完成的任务到 barrier。
     */
    public void register() {
        barrier.register();
    }

    /**
     * 密封 barrier —— 表示不再有新任务注册。
     */
    public void seal() {
        if (state.compareAndSet(TickState.RUNNING, TickState.DRAINING)) {
            barrier.seal();
        }
    }

    /**
     * 标记任务完成。
     */
    public void complete() {
        barrier.complete();
    }

    /**
     * 报告 tick 失败。
     *
     * @param error 失败原因
     */
    public void reportFailure(Throwable error) {
        this.failure = error;
        barrier.fail(error);
    }

    /**
     * 获取失败原因（如果有）。
     */
    public Throwable failure() {
        return failure;
    }

    /**
     * 检查 tick 是否已完成。
     */
    public boolean isComplete() {
        return state.get() == TickState.COMPLETE || barrier.isComplete();
    }

    /**
     * 获取 tick 完成阶段 —— 非阻塞观察 tick 完成状态。
     */
    public CompletionStage<Void> completion() {
        return barrier.completion();
    }
}
