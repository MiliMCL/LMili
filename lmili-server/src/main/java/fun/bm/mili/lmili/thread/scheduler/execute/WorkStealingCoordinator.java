package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 工作窃取协调器 —— 实现跨 region 的负载均衡。
 *
 * <h3>设计原理</h3>
 * <p>每个 worker 线程维护一个本地 RegionQueue 栈（LIFO 消费）。
 * 当本地队列为空时，随机选择其他 worker 的队列窃取任务（FIFO 窃取）。
 *
 * <h3>Work-Stealing 算法</h3>
 * <pre>
 * 1. Worker 从本地队列 pop()（尾部，LIFO）
 * 2. 如果本地队列为空：
 *    a. 随机选择一个 victim worker
 *    b. 从 victim 的队列 steal()（头部，FIFO）
 *    c. 如果窃取失败，继续随机选择下一个 victim
 *    d. 如果所有 victim 都为空，返回 null（无任务可做）
 * </pre>
 *
 * <h3>为什么 LIFO 本地 + FIFO 窃取？</h3>
 * <ul>
 *   <li><b>本地 LIFO</b>：利用 CPU 缓存局部性，最近提交的任务数据更可能在缓存中</li>
 *   <li><b>窃取 FIFO</b>：保证最早提交的任务优先被处理，避免饥饿</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * WorkStealingCoordinator coordinator = new WorkStealingCoordinator(4);
 *
 * // 注册 region
 * coordinator.registerRegion(regionId1);
 * coordinator.registerRegion(regionId2);
 *
 * // 提交任务
 * coordinator.submit(task);
 *
 * // Worker 循环
 * while (running) {
 *     RegionTask task = coordinator.poll(localWorkerId);
 *     if (task != null) {
 *         task.execute();
 *     } else {
 *         // 无任务，短暂等待或让出 CPU
 *         Thread.onSpinWait();
 *     }
 * }
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>本协调器是线程安全的。多个 worker 线程可以同时调用 {@link #poll(int)}。
 */
public final class WorkStealingCoordinator {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 配置 ----
    private final int workerCount;

    // ---- Region 注册表 ----
    private final ConcurrentHashMap<Long, RegionQueue> regionQueues;

    // ---- Worker 状态 ----
    private final WorkerState[] workers;

    // ---- 全局统计 ----
    private final LongAdder totalSteals = new LongAdder();
    private final LongAdder totalStealAttempts = new LongAdder();
    private final LongAdder failedSteals = new LongAdder();

    // ---- 控制 ----
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger registeredRegionCount = new AtomicInteger(0);

    // ---- 已注销的 region 集合（防止被 submit 复活） ----
    private final ConcurrentHashMap<Long, Boolean> unregisteredRegions = new ConcurrentHashMap<>();

    /**
     * 创建工作窃取协调器。
     *
     * @param workerCount worker 线程数量（建议等于 carrier 并行度）
     */
    public WorkStealingCoordinator(int workerCount) {
        this.workerCount = Math.max(1, workerCount);
        this.regionQueues = new ConcurrentHashMap<>();
        this.workers = new WorkerState[this.workerCount];

        for (int i = 0; i < this.workerCount; i++) {
            workers[i] = new WorkerState(i);
        }

        LOGGER.info("[WorkStealingCoordinator] Initialized with {} workers", this.workerCount);
    }

    // ---- Region 管理 ----

    /**
     * 注册一个 region，创建对应的任务队列。
     *
     * <p>如果 region 已存在，返回现有队列。
     *
     * @param regionId region ID
     * @return 创建的 RegionQueue
     */
    @NotNull
    public RegionQueue registerRegion(final long regionId) {
        // 清除注销标记，允许重新注册
        unregisteredRegions.remove(regionId);
        return regionQueues.computeIfAbsent(regionId, id -> {
            RegionQueue queue = new RegionQueue(id);
            registeredRegionCount.incrementAndGet();
            LOGGER.debug("[WorkStealingCoordinator] Registered region #{}", id);
            return queue;
        });
    }

    /**
     * 注销一个 region，清空其任务队列。
     *
     * @param regionId region ID
     * @return 未执行的任务列表，如果 region 不存在则返回空列表
     */
    @NotNull
    public List<RegionTask> unregisterRegion(final long regionId) {
        // 标记为已注销，防止 submit 复活
        unregisteredRegions.put(regionId, Boolean.TRUE);
        RegionQueue queue = regionQueues.remove(regionId);
        if (queue != null) {
            registeredRegionCount.decrementAndGet();
            LOGGER.debug("[WorkStealingCoordinator] Unregistered region #{}", regionId);
            return queue.drain();
        }
        return new ArrayList<>();
    }

    /**
     * 检查 region 是否已注册。
     */
    public boolean isRegionRegistered(final long regionId) {
        return regionQueues.containsKey(regionId);
    }

    /**
     * 获取已注册的 region 数量。
     */
    public int registeredRegionCount() {
        return registeredRegionCount.get();
    }

    // ---- 任务提交 ----

    /**
     * 提交任务到对应 region 的队列。
     *
     * <p>如果 region 未注册，会自动注册。
     *
     * @param task 要提交的任务
     */
    public void submit(@NotNull RegionTask task) {
        if (!running.get()) {
            LOGGER.warn("[WorkStealingCoordinator] Submit rejected: coordinator not running");
            return;
        }
        long regionId = task.regionId();
        // 检查是否已注销，防止复活
        if (unregisteredRegions.containsKey(regionId)) {
            LOGGER.warn("[WorkStealingCoordinator] Submit rejected: region #{} has been unregistered", regionId);
            task.onCancel();
            return;
        }
        RegionQueue queue = regionQueues.computeIfAbsent(regionId, id -> {
            // 再次检查，防止在检查后、创建前被注销
            if (unregisteredRegions.containsKey(id)) {
                return null;
            }
            registeredRegionCount.incrementAndGet();
            return new RegionQueue(id);
        });
        if (queue == null) {
            LOGGER.warn("[WorkStealingCoordinator] Submit rejected: region #{} has been unregistered", regionId);
            task.onCancel();
            return;
        }
        queue.push(task);
    }

    /**
     * 批量提交任务。
     *
     * @param tasks 任务列表
     */
    public void submitAll(@NotNull List<RegionTask> tasks) {
        if (!running.get()) {
            LOGGER.warn("[WorkStealingCoordinator] SubmitAll rejected: coordinator not running");
            return;
        }
        // 按 regionId 分组，减少 map 查找
        ConcurrentHashMap<Long, java.util.List<RegionTask>> grouped = new ConcurrentHashMap<>();
        for (RegionTask task : tasks) {
            long regionId = task.regionId();
            // 跳过已注销的 region
            if (unregisteredRegions.containsKey(regionId)) {
                task.onCancel();
                continue;
            }
            grouped.computeIfAbsent(regionId, k -> new ArrayList<>()).add(task);
        }

        for (var entry : grouped.entrySet()) {
            RegionQueue queue = regionQueues.computeIfAbsent(entry.getKey(), id -> {
                if (unregisteredRegions.containsKey(id)) {
                    return null;
                }
                registeredRegionCount.incrementAndGet();
                return new RegionQueue(id);
            });
            if (queue == null) continue;
            for (RegionTask task : entry.getValue()) {
                queue.push(task);
            }
        }
    }

    // ---- 任务消费 ----

    /**
     * 获取下一个要执行的任务。
     *
     * <p>消费顺序：
     * <ol>
     *   <li>从本地 worker 关联的 region 队列 pop()</li>
     *   <li>如果本地为空，尝试从其他 region 窃取</li>
     *   <li>如果全部为空，返回 null</li>
     * </ol>
     *
     * @param localWorkerId 本地 worker ID（0-based）
     * @return 下一个任务，或 null 如果没有可用任务
     */
    @Nullable
    public RegionTask poll(int localWorkerId) {
        if (!running.get()) return null;

        // 1. 尝试从本地 worker 的最近使用队列获取
        RegionTask task = pollFromLocalCache(localWorkerId);
        if (task != null) return task;

        // 2. 尝试从所有 region 队列窃取
        return stealWork(localWorkerId);
    }

    /**
     * 从本地缓存队列获取任务。
     */
    @Nullable
    private RegionTask pollFromLocalCache(int localWorkerId) {
        WorkerState worker = workers[localWorkerId];
        Long cachedRegionId = worker.lastRegionId.get();

        if (cachedRegionId >= 0) {
            RegionQueue queue = regionQueues.get(cachedRegionId);
            if (queue != null && queue.isActive()) {
                RegionTask task = queue.pop();
                if (task != null) return task;
            }
        }
        return null;
    }

    /**
     * 从其他 region 窃取任务。
     *
     * <p>窃取策略：随机选择一个 region，从其队列头部窃取。
     * 如果失败，继续尝试其他 region。
     */
    @Nullable
    private RegionTask stealWork(int localWorkerId) {
        int size = regionQueues.size();
        if (size == 0) return null;

        totalStealAttempts.increment();

        // Mili start - 优化：避免每次分配数组，使用迭代器遍历
        // 使用迭代器遍历以减少分配，但随机起点通过跳过实现
        int skip = ThreadLocalRandom.current().nextInt(size);
        int idx = 0;
        for (var entry : regionQueues.entrySet()) {
            if (idx < skip) {
                idx++;
                continue;
            }
            long regionId = entry.getKey();
            RegionQueue queue = entry.getValue();

            if (queue != null && queue.isActive()) {
                RegionTask task = queue.steal();
                if (task != null) {
                    totalSteals.increment();
                    // 更新本地缓存
                    workers[localWorkerId].lastRegionId.set(regionId);
                    return task;
                }
            }
            idx++;
        }
        // 从头开始遍历剩余部分
        for (var entry : regionQueues.entrySet()) {
            long regionId = entry.getKey();
            RegionQueue queue = entry.getValue();

            if (queue != null && queue.isActive()) {
                RegionTask task = queue.steal();
                if (task != null) {
                    totalSteals.increment();
                    // 更新本地缓存
                    workers[localWorkerId].lastRegionId.set(regionId);
                    return task;
                }
            }
        }
        // Mili end

        failedSteals.increment();
        return null;
    }

    /**
     * 获取指定 region 的队列（用于直接操作）。
     *
     * @param regionId region ID
     * @return RegionQueue 或 null
     */
    @Nullable
    public RegionQueue getQueue(final long regionId) {
        return regionQueues.get(regionId);
    }

    /**
     * 获取所有已注册的 region ID。
     */
    @NotNull
    public List<Long> getRegisteredRegions() {
        return new ArrayList<>(regionQueues.keySet());
    }

    // ---- 状态查询 ----

    /**
     * 获取总窃取次数。
     */
    public long totalSteals() {
        return totalSteals.sum();
    }

    /**
     * 获取总窃取尝试次数。
     */
    public long totalStealAttempts() {
        return totalStealAttempts.sum();
    }

    /**
     * 计算窃取成功率。
     */
    public double stealSuccessRate() {
        long attempts = totalStealAttempts.sum();
        return attempts > 0 ? (double) totalSteals.sum() / attempts : 0.0;
    }

    /**
     * 获取所有 region 的总待处理任务数。
     */
    public int totalPendingTasks() {
        int total = 0;
        for (RegionQueue queue : regionQueues.values()) {
            total += queue.approximateSize();
        }
        return total;
    }

    /**
     * 检查是否正在运行。
     */
    public boolean isRunning() {
        return running.get();
    }

    // ---- 关闭 ----

    /**
     * 停止协调器并清空所有队列。
     *
     * @return 所有未执行的任务
     */
    @NotNull
    public List<RegionTask> shutdown() {
        running.set(false);
        List<RegionTask> remaining = new ArrayList<>();

        for (RegionQueue queue : regionQueues.values()) {
            remaining.addAll(queue.drain());
        }
        regionQueues.clear();
        unregisteredRegions.clear();
        registeredRegionCount.set(0);

        LOGGER.info("[WorkStealingCoordinator] Shutdown (steals: {}, success rate: {}%)",
                totalSteals.sum(), String.format("%.1f", stealSuccessRate() * 100));

        return remaining;
    }

    // ---- 内部组件 ----

    /**
     * Worker 状态 —— 每个 worker 的本地信息。
     *
     * <p>使用原子变量避免同步开销。
     */
    private static final class WorkerState {
        final int workerId;

        /**
         * 最近访问的 region ID —— 用于缓存局部性优化。
         */
        final java.util.concurrent.atomic.AtomicLong lastRegionId =
                new java.util.concurrent.atomic.AtomicLong(-1);

        WorkerState(int workerId) {
            this.workerId = workerId;
        }
    }
}
