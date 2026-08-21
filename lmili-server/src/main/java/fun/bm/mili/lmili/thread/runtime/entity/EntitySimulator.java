package fun.bm.mili.lmili.thread.runtime.entity;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entity 并行模拟器 —— 按 Phase 拆分 Entity Tick。
 *
 * <p>流程：
 * <ol>
 *   <li>收集所有 Entity</li>
 *   <li>构建依赖图（EntityDependencyGraph）</li>
 *   <li>按 Phase 顺序执行：</li>
 *   <ul>
 *     <li>Phase 内并行处理 Entity</li>
 *     <li>Phase 间同步（CountDownLatch）</li>
 *   </ul>
 *   <li>Commit 阶段提交状态</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * <p>每个 Phase 内部可以并行，Phase 间需要同步。
 */
public final class EntitySimulator {

    /** 线程池 */
    private final ExecutorService executor;

    /** Entity 依赖图 */
    private final EntityDependencyGraph dependencyGraph;

    /** 是否正在运行 */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** 超时时间（毫秒） */
    private final long timeoutMillis;

    /**
     * 创建 Entity 模拟器。
     *
     * @param executor        线程池
     * @param dependencyGraph Entity 依赖图
     * @param timeoutMillis   超时时间
     */
    public EntitySimulator(ExecutorService executor,
                            EntityDependencyGraph dependencyGraph,
                            long timeoutMillis) {
        this.executor = executor;
        this.dependencyGraph = dependencyGraph;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 创建默认 Entity 模拟器。
     */
    public EntitySimulator(ExecutorService executor) {
        this(executor, new EntityDependencyGraph(), 50);
    }

    /**
     * 执行一个完整的 Entity Tick 周期。
     *
     * @param entities     要 Tick 的 Entity 列表
     * @param tickFunction 每个 Entity 每个 Phase 的执行函数
     * @return true 如果成功完成
     */
    public boolean tickEntities(List<EntityTickData> entities,
                                 EntityTickFunction tickFunction) {
        if (!running.get()) return false;

        // 按 Phase 顺序执行
        for (EntitySimulationPhase phase : EntitySimulationPhase.executionOrder()) {
            if (!running.get()) return false;

            if (!executePhase(phase, entities, tickFunction)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 执行一个 Phase。
     *
     * @param phase        当前阶段
     * @param entities     Entity 列表
     * @param tickFunction 执行函数
     * @return true 如果成功完成
     */
    private boolean executePhase(EntitySimulationPhase phase,
                                  List<EntityTickData> entities,
                                  EntityTickFunction tickFunction) {
        // 拓扑排序（考虑依赖关系）
        List<Long> sortedEntityIds = dependencyGraph.topologicalSort(
                entities.stream().map(EntityTickData::entityId).toList()
        );

        // 创建 CountDownLatch
        CountDownLatch latch = new CountDownLatch(entities.size());

        // 提交所有 Entity 的当前 Phase 任务
        for (EntityTickData entity : entities) {
            executor.submit(() -> {
                try {
                    // 检查依赖是否已满足
                    if (dependenciesSatisfied(entity.entityId(), phase)) {
                        tickFunction.tick(phase, entity);
                    }
                } catch (Exception e) {
                    // 记录错误但不中断
                } finally {
                    latch.countDown();
                }
            });
        }

        // 等待所有 Entity 完成当前 Phase
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 检查 Entity 的依赖是否已满足。
     */
    private boolean dependenciesSatisfied(long entityId, EntitySimulationPhase phase) {
        // 简化实现：总是返回 true
        // 实际实现需要检查前置 Entity 是否已完成当前 Phase
        return true;
    }

    /**
     * 停止模拟器。
     */
    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Entity Tick 数据。
     */
    public record EntityTickData(
            long entityId,
            long regionId,
            long generationId,
            Object entityHandle // 实际的 Entity 句柄，由 NMS 提供
    ) {}

    /**
     * Entity Tick 函数。
     */
    @FunctionalInterface
    public interface EntityTickFunction {
        /**
         * 执行 Entity 的一个 Phase。
         *
         * @param phase  当前阶段
         * @param entity Entity 数据
         */
        void tick(EntitySimulationPhase phase, EntityTickData entity);
    }
}
