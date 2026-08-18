package fun.bm.mili.lmili.thread.regiontick.dag;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * DAG 执行状态 —— 运行时可变状态，独立于不可变的 {@link CompiledDag}。
 *
 * <p>核心设计原则：{@link CompiledDag} 必须是 immutable，运行时状态必须独立。
 * 不要把 {@code remainingInDegrees} 这种执行期可变状态直接放进 {@code CompiledDag}。
 *
 * <h3>结构</h3>
 * <pre>
 * CompiledDag          (immutable)
 *     ├── nodes
 *     ├── successors
 *     ├── readyNodes
 *     └── initialDependencies
 *
 * DagExecutionState    (mutable, per-execution)
 *     ├── remainingDependencies  (AtomicIntegerArray)
 *     ├── pendingNodes           (AtomicInteger)
 *     ├── failed                 (AtomicBoolean)
 *     └── cancelled              (AtomicBoolean)
 * </pre>
 *
 * <h3>线程安全</h3>
 * <p>所有状态使用原子类型，支持多线程并发递减入度。
 * 入度递减必须原子化：
 * <pre>{@code
 * if (remaining.decrementAndGet(successor) == 0) {
 *     submit(successor);
 * }
 * }</pre>
 * 这样当多个 predecessor 同时完成时，successor 只会被提交一次。
 */
public final class DagExecutionState {

    private final CompiledDag dag;

    /** 剩余依赖数 —— 每个节点的入度副本，使用 AtomicIntegerArray 保证原子递减 */
    private final AtomicIntegerArray remaining;

    /** 待完成的节点数 */
    private final AtomicInteger pendingNodes;

    /** 是否已失败 */
    private final AtomicBoolean failed = new AtomicBoolean(false);

    /** 是否已取消 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * 创建 DAG 执行状态。
     *
     * @param dag 编译后的 DAG（不可变）
     */
    public DagExecutionState(CompiledDag dag) {
        this.dag = dag;
        this.remaining = new AtomicIntegerArray(dag.nodeCount());

        for (int i = 0; i < dag.nodeCount(); i++) {
            remaining.set(i, dag.initialDependencyCount(i));
        }

        this.pendingNodes = new AtomicInteger(dag.nodeCount());
    }

    /**
     * 原子递减指定节点的剩余依赖数。
     *
     * <p>当多个 predecessor 同时完成时，此方法保证：
     * <ul>
     *   <li>依赖数正确递减</li>
     *   <li>只有最后一个递减到 0 的线程才会返回 true</li>
     * </ul>
     *
     * @param nodeId 节点 ID
     * @return true 如果剩余依赖降为 0（该节点已就绪）
     */
    public boolean decrementDependency(int nodeId) {
        return remaining.decrementAndGet(nodeId) == 0;
    }

    /**
     * 获取指定节点的剩余依赖数。
     */
    public int remainingDependencies(int nodeId) {
        return remaining.get(nodeId);
    }

    /**
     * 标记一个节点完成。
     *
     * @return true 如果所有节点都已完成
     */
    public boolean nodeCompleted() {
        return pendingNodes.decrementAndGet() == 0;
    }

    /**
     * 获取剩余待完成节点数。
     */
    public int pendingNodeCount() {
        return pendingNodes.get();
    }

    /**
     * 获取总节点数。
     */
    public int totalNodeCount() {
        return dag.nodeCount();
    }

    /**
     * 标记执行失败。
     */
    public void markFailed() {
        failed.set(true);
    }

    /**
     * 检查是否已失败。
     */
    public boolean isFailed() {
        return failed.get();
    }

    /**
     * 标记执行取消。
     */
    public void markCancelled() {
        cancelled.set(true);
    }

    /**
     * 检查是否已取消。
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 检查是否所有节点都已完成。
     */
    public boolean isComplete() {
        return pendingNodes.get() == 0;
    }

    /**
     * 获取关联的 DAG。
     */
    public CompiledDag dag() {
        return dag;
    }
}
