package fun.bm.mili.lmili.thread.regiontick.executor;

import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.dag.CompiledDag;
import org.jetbrains.annotations.NotNull;

/**
 * DAG 节点执行路由器 —— 把 DAG "ready" 信号翻译成正确的执行路径。
 *
 * <p><b>核心约束</b>：DAG 只能决定调度顺序（A 完成 → B 才 ready），
 * 但 DAG <b>不能</b>绕过线程安全机制在同一线程直接执行跨 region 节点。
 * 这会破坏 region 不变量，导致 entity 数据竞争。</p>
 *
 * <h3>三种合法路径</h3>
 * <ol>
 *   <li><b>同 region 快路径</b>：节点 regionId == 当前线程 regionId →
 *       直接在当前线程同步执行</li>
 *   <li><b>跨 region 调度</b>：节点 regionId != 当前线程 regionId →
 *       通过 Bukkit 主线程调度器路由到目标 region</li>
 *   <li><b>global 节点</b>：节点 regionId == {@link CompiledDag#GLOBAL_REGION_ID} →
 *       视为无 region 约束，在当前线程直接执行</li>
 * </ol>
 *
 * <h3>实现策略</h3>
 * <ul>
 *   <li>{@link SameRegionNodeScheduler} —— 同 region 快路径 + global 路径</li>
 *   <li>{@link CompositeNodeScheduler} —— 按 regionId 自动路由</li>
 * </ul>
 *
 * @author Mili scheduler team
 */
public interface NodeScheduler {

    /**
     * 派发一个 ready 的 DAG 节点。
     *
     * <p>调用方（DAG 引擎）负责：传入节点的 regionId、当前 region tick 上下文（如果有）、
     * 以及节点本身的执行体。</p>
     *
     * <p>本方法会：
     * <ol>
     *   <li>检查节点 regionId 与当前线程 regionId 的关系</li>
     *   <li>同 region → 在当前线程同步执行 body</li>
     *   <li>跨 region → 通过 Bukkit 主线程调度器路由到目标 region</li>
     *   <li>global → 在当前线程直接执行</li>
     * </ol>
     * </p>
     *
     * @param nodeId         节点 ID（用于诊断日志）
     * @param nodeRegionId   节点所属 regionId（正数）或 {@link CompiledDag#GLOBAL_REGION_ID}
     * @param body           节点执行体（必须是 ready-to-run 状态，无外部依赖）
     * @param currentContext 当前正在执行的 region tick 上下文（可为 null —— 表示在非 region tick 线程上）
     */
    void dispatch(int nodeId,
                  long nodeRegionId,
                  @NotNull Runnable body,
                  @NotNull RegionTickContext currentContext);
}
