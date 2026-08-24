package fun.bm.mili.lmili.runtime.exec;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.runtime.budget.BudgetLease;
import fun.bm.mili.lmili.runtime.control.TickController;
import fun.bm.mili.lmili.runtime.task.TickTaskType;
import fun.bm.mili.lmili.thread.regiontick.dag.TickDagExecution;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并行执行器 —— 执行 TickDagExecution（ARCHITECTURE_AdaptiveRuntime.md §3.15）。
 *
 * <p><strong>预算语义（§6.3 R1）</strong>：预算不足的节点<strong>不阻塞</strong>——
 * 挂回（记入下轮）+ aging（连续被拒 3 次走 soft 窗口防饥饿）；
 * fan-out 受 FanoutController 治理；全局并行关闭时可退化为串行。
 *
 * <p>线程模型：协调在调用线程（非 tick carrier；文档约定），节点工作经 virtual-thread 池并行。
 */
public final class ParallelExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final TickController tick;
    private final FanoutController fanout;
    /** 节点类型元数据（规划期；executor 据此选预算池） */
    private final Map<Integer, TickTaskType> nodeTypes;
    /** 节点执行者（由集成方提供：通常是把 nodeId 映射回 CompiledDag executor 并在 region 上下文执行） */
    private final NodeRunner nodeRunner;
    /** aging 计数（连续预算被拒） */
    private final Map<Integer, AtomicInteger> rejections = new ConcurrentHashMap<>();

    private final ExecutorService parallelPool;

    @FunctionalInterface
    public interface NodeRunner {
        void run(int nodeId) throws Exception;
    }

    public ParallelExecutor(TickController tick, FanoutController fanout,
                            Map<Integer, TickTaskType> nodeTypes, NodeRunner nodeRunner) {
        this.tick = tick;
        this.fanout = fanout;
        this.nodeTypes = nodeTypes == null ? Map.of() : nodeTypes;
        this.nodeRunner = nodeRunner;
        this.parallelPool = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 执行一个计划（最多 3 轮：预算不足挂回重试；仍不足 → 留给调度器下一 tick）。
     * 返回的 future 在全部（或放弃）执行完成后完成。
     */
    public CompletableFuture<Void> execute(long regionId, TickDagExecution plan) {
        if (plan == null || plan.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final boolean serial = plan.nodeOrder().length <= 1 || plan.maxFanoutPerNode() <= 1 || !tick.isParallelTickEnabled();
        List<Integer> pending = new ArrayList<>();
        for (int nodeId : plan.nodeOrder()) {
            pending.add(nodeId);
        }
        for (int round = 0; round < 3 && !pending.isEmpty(); round++) {
            final List<Integer> current = new ArrayList<>(pending);
            pending.clear();
            if (serial) {
                for (int nodeId : current) {
                    if (!runNode(regionId, plan, nodeId)) {
                        pending.add(nodeId);
                    }
                }
            } else {
                final int fanOut = Math.max(1, Math.min(plan.maxFanoutPerNode(), fanout.effectiveFanOut()));
                for (int i = 0; i < current.size(); i += fanOut) {
                    final List<Integer> batch = new ArrayList<>(current.subList(i, Math.min(i + fanOut, current.size())));
                    final List<CompletableFuture<Boolean>> futures = batch.stream()
                            .map(id -> CompletableFuture.supplyAsync(() -> runNode(regionId, plan, id), parallelPool))
                            .toList();
                    for (CompletableFuture<Boolean> f : futures) {
                        final Boolean ok = f.join();
                        if (ok != null && !ok) {
                            pending.addAll(batch); // 简化：整批挂回（预算不足多为系统性）
                            break;
                        }
                    }
                }
            }
        }
        if (!pending.isEmpty()) {
            LOGGER.debug("[ParallelExecutor] {} nodes budget-starved after 3 rounds (region {}) — re-queued for next tick", pending.size(), regionId);
        }
        return CompletableFuture.completedFuture(null);
    }

    /** @return true = 已执行；false = 预算不足挂回 */
    private boolean runNode(long regionId, TickDagExecution plan, int nodeId) {
        final TickTaskType type = nodeTypes.getOrDefault(nodeId, TickTaskType.ENTITY);
        BudgetLease lease = tick.tryAcquire(regionId, type, plan.budgetNanosPerNode());
        if (lease == null) {
            // aging：连续被拒 3 次 → soft 窗口（仅 ENTITY+NORMAL 生效）
            final AtomicInteger counter = rejections.computeIfAbsent(nodeId, k -> new AtomicInteger());
            if (counter.incrementAndGet() >= 3) {
                counter.set(0);
                lease = tick.tryAcquireSoftWindow(regionId, type);
                if (lease != null) {
                    executeWithLease(lease, nodeId);
                    return true;
                }
            }
            return false;
        }
        executeWithLease(lease, nodeId);
        return true;
    }

    private void executeWithLease(BudgetLease lease, int nodeId) {
        try (BudgetLease ignored = lease) {
            nodeRunner.run(nodeId);
        } catch (Throwable t) {
            LOGGER.warn("[ParallelExecutor] node {} failed", nodeId, t);
        }
    }

    public void shutdown() {
        parallelPool.shutdown();
    }
}
