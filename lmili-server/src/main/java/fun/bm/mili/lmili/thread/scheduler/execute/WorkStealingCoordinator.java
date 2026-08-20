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
import java.util.concurrent.atomic.LongAdder;

/**
 * 工作窃取协调器 —— 实现跨 region 的负载均衡。
 *
 * <h3>设计原理</h3>
 * <p>每个 worker 线程维护一个本地 RegionQueue 栈（LIFO 消费）。
 * 当本地队列为空时，随机选择其他 worker 的队列窃取任务（FIFO 窃取）。
 *
 * <h3>修复的并发问题</h3>
 * <ul>
 *   <li><b>C-02</b>：register/unregister lifecycle race —— 使用 RegionState CAS 协议替代
 *       unregisteredRegions + ConcurrentHashMap 组合</li>
 *   <li><b>C-08</b>：unregister 与已取出任务冲突 —— unregister 先标记 DRAINING，
 *       等待 executingCount==0 后再 drain</li>
 *   <li><b>C-24</b>：registeredRegionCount 与 map 不是统一状态 —— 单一度量来源</li>
 *   <li><b>C-25</b>：Scheduler shutdown 与 Region lifecycle 没有统一协议 ——
 *       通过 RegionState 协调</li>
 * </ul>
 *
 * <h3>Region 生命周期</h3>
 * <pre>
 * registerRegion: 创建 (regionId → RegionQueue.ACTIVE)
 * unregisterRegion: ACTIVE → DRAINING → 等待 executing=0 → CLOSED → remove
 * </pre>
 *
 * <h3>线程安全</h3>
 * <p>本协调器是线程安全的。多个 worker 线程可以同时调用 {@link #poll(int)}。
 */
public final class WorkStealingCoordinator {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 配置 ----
    private final int workerCount;

    // ---- Region 注册表 ----
    // 单一度量来源：regionQueues 的 size 就是真实的注册区域数 (C-24)
    private final ConcurrentHashMap<Long, RegionQueue> regionQueues;

    // ---- Worker 状态 ----
    private final WorkerState[] workers;

    // ---- 全局统计 ----
    private final LongAdder totalSteals = new LongAdder();
    private final LongAdder totalStealAttempts = new LongAdder();
    private final LongAdder failedSteals = new LongAdder();

    // ---- 控制 ----
    private final AtomicBoolean running = new AtomicBoolean(true);

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
     * <p>修复 C-02：使用 {@link RegionState} 的 CAS 协议替代 unregisteredRegions。
     * 如果 region 之前被注销（CLOSED），可以重新注册（创建新状态）。
     *
     * @param regionId region ID
     * @return 创建的 RegionQueue
     */
    @NotNull
    public RegionQueue registerRegion(final long regionId) {
        RegionQueue existing = regionQueues.get(regionId);
        if (existing != null && existing.isActive()) {
            return existing;
        }
        // 如果 region 存在但已关闭，或者不存在 —— 创建新的
        RegionQueue newQueue = new RegionQueue(regionId);
        RegionQueue previous = regionQueues.putIfAbsent(regionId, newQueue);
        if (previous != null) {
            // 有线程并发注册：检查其状态
            if (previous.isActive()) {
                return previous;
            }
            // 如果不是 ACTIVE，尝试替换（旧的可能正在被 unregister）
            if (regionQueues.replace(regionId, previous, newQueue)) {
                LOGGER.debug("[WorkStealingCoordinator] Re-registered region #{}", regionId);
                return newQueue;
            }
            return regionQueues.get(regionId);
        }
        LOGGER.debug("[WorkStealingCoordinator] Registered region #{}", regionId);
        return newQueue;
    }

    /**
     * 注销一个 region，清空其任务队列。
     *
     * <p>修复 C-08：unregister 与已取出任务冲突。
     * <ol>
     *   <li>先将 RegionState 标记为 DRAINING（阻止新任务入队）</li>
     *   <li>等待 executingCount==0（所有已取出任务执行完成）</li>
     *   <li>执行 drain() 清空剩余队列</li>
     *   <li>将 RegionState 标记为 CLOSED</li>
     *   <li>从 map 中移除</li>
     * </ol>
     *
     * @param regionId region ID
     * @return 未执行的任务列表，如果 region 不存在则返回空列表
     */
    @NotNull
    public List<RegionTask> unregisterRegion(final long regionId) {
        RegionQueue queue = regionQueues.get(regionId);
        if (queue == null) {
            return new ArrayList<>();
        }

        // 步骤 1：标记为 DRAINING，阻止新任务
        queue.deactivate();

        // 步骤 2：等待 executingCount == 0（已取出但未完成的任务）
        // 使用有限等待避免无限阻塞
        RegionState state = queue.regionState();
        int waitCount = 0;
        while (state.executingCount() > 0 && waitCount < 100) {
            Thread.yield();
            waitCount++;
        }

        // 步骤 3：尝试关闭（如果 executingCount==0）
        if (!queue.tryClose()) {
            LOGGER.warn("[WorkStealingCoordinator] Region #{} still has {} tasks executing after wait",
                    regionId, state.executingCount());
        }

        // 步骤 4：从 map 中移除并 drain 剩余
        regionQueues.remove(regionId);
        List<RegionTask> remaining = queue.drain();

        LOGGER.debug("[WorkStealingCoordinator] Unregistered region #{} (remaining tasks: {})",
                regionId, remaining.size());
        return remaining;
    }

    /**
     * 检查 region 是否已注册且处于 ACTIVE 状态。
     */
    public boolean isRegionRegistered(final long regionId) {
        RegionQueue queue = regionQueues.get(regionId);
        return queue != null && queue.isActive();
    }

    /**
     * 获取已注册的 region 数量。
     *
     * <p>修复 C-24：直接返回 map size，单一度量来源。
     */
    public int registeredRegionCount() {
        return regionQueues.size();
    }

    // ---- 任务提交 ----

    /**
     * 提交任务到对应 region 的队列。
     *
     * <p>如果 region 未注册，会自动注册。
     *
     * <p>修复 C-02/C-25：不再使用 unregisteredRegions hack。
     * 通过 RegionState.isActive() 判断是否可以接受任务。
     *
     * @param task 要提交的任务
     */
    public void submit(@NotNull RegionTask task) {
        if (!running.get()) {
            LOGGER.warn("[WorkStealingCoordinator] Submit rejected: coordinator not running");
            return;
        }
        long regionId = task.regionId();

        RegionQueue queue = getOrCreateQueue(regionId);
        if (queue == null) {
            // region 正在被注销或被关闭
            task.onCancel();
            return;
        }

        // RegionQueue.push 内部已通过 RegionState 检查 ACTIVE 状态
        queue.push(task);
    }

    /**
     * 获取或创建 region 队列。
     *
     * <p>如果 region 不存在或已关闭，创建新队列。
     * 如果 region 正在 DRAINING，返回 null。
     *
     * @return RegionQueue 或 null（如果正在 DRAINING）
     */
    @Nullable
    private RegionQueue getOrCreateQueue(final long regionId) {
        RegionQueue queue = regionQueues.get(regionId);
        if (queue != null) {
            if (queue.isActive()) {
                return queue;
            }
            // 正在 DRAINING/CLOSED
            return null;
        }

        // 不存在，创建新队列
        if (!running.get()) return null;

        RegionQueue newQueue = new RegionQueue(regionId);
        RegionQueue previous = regionQueues.putIfAbsent(regionId, newQueue);
        if (previous != null) {
            // 并发创建：使用已存在的
            if (previous.isActive()) {
                return previous;
            }
            return null;
        }
        return newQueue;
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
            RegionQueue queue = regionQueues.get(regionId);
            if (queue != null && !queue.isActive()) {
                task.onCancel();
                continue;
            }
            grouped.computeIfAbsent(regionId, k -> new ArrayList<>()).add(task);
        }

        for (var entry : grouped.entrySet()) {
            RegionQueue queue = getOrCreateQueue(entry.getKey());
            if (queue == null) {
                for (RegionTask task : entry.getValue()) {
                    task.onCancel();
                }
                continue;
            }
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
     * <p>修复 C-01：通过 RegionState 保证 Region 独占执行。
     * 每个 RegionQueue 的 RegionState 确保同一 region 不会被多个 Worker 同时执行。
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
        long cachedRegionId = worker.lastRegionId.get();

        if (cachedRegionId >= 0) {
            RegionQueue queue = regionQueues.get(cachedRegionId);
            if (queue != null && queue.isActive()) {
                // 标记开始执行
                RegionState state = queue.regionState();
                if (state.tryBeginExecution()) {
                    RegionTask task = queue.pop();
                    if (task != null) {
                        return task;
                    }
                    // 队列为空，回退
                    state.endExecution();
                }
            }
        }
        return null;
    }

    /**
     * 从其他 region 窃取任务。
     *
     * <p>窃取策略（C-13 优化）：随机选择 victim region，尝试窃取。
     * 如果失败，继续尝试其他随机 victim。限制最大尝试次数，避免扫描整个 map。
     *
     * <p>修复 C-01：通过 RegionState.tryBeginExecution 保证独占。
     */
    @Nullable
    private RegionTask stealWork(int localWorkerId) {
        int size = regionQueues.size();
        if (size == 0) return null;

        totalStealAttempts.increment();

        // C-13 优化：限制最大尝试次数，避免扫描整个 map
        // 对于少量 region，尝试全部；对于大量 region，采样固定数量
        int maxAttempts = Math.min(size, Math.max(3, workerCount));

        // 随机起点遍历 regionQueues
        int skip = size > maxAttempts ? ThreadLocalRandom.current().nextInt(size) : 0;
        int idx = 0;
        int attempts = 0;
        for (var entry : regionQueues.entrySet()) {
            if (idx < skip) {
                idx++;
                continue;
            }
            if (attempts >= maxAttempts) break;
            RegionTask task = tryStealFromEntry(entry, localWorkerId);
            if (task != null) return task;
            idx++;
            attempts++;
        }
        // 从头开始遍历剩余部分
        idx = 0;
        for (var entry : regionQueues.entrySet()) {
            if (idx >= skip) break;
            if (attempts >= maxAttempts) break;
            RegionTask task = tryStealFromEntry(entry, localWorkerId);
            if (task != null) return task;
            idx++;
            attempts++;
        }

        failedSteals.increment();
        return null;
    }

    /**
     * 尝试从指定 entry 窃取任务。
     */
    @Nullable
    private RegionTask tryStealFromEntry(java.util.Map.Entry<Long, RegionQueue> entry, int localWorkerId) {
        long regionId = entry.getKey();
        RegionQueue queue = entry.getValue();

        if (queue != null && queue.isActive()) {
            // 标记开始执行，保证独占
            RegionState state = queue.regionState();
            if (state.tryBeginExecution()) {
                RegionTask task = queue.steal();
                if (task != null) {
                    totalSteals.increment();
                    // 更新本地缓存
                    workers[localWorkerId].lastRegionId.set(regionId);
                    return task;
                }
                // 队列为空，回退
                state.endExecution();
            }
        }
        return null;
    }

    /**
     * 通知指定 region 的任务执行完成。
     *
     * <p>Worker 执行完任务后必须调用此方法，使 RegionState.executingCount 递减。
     * 这是 C-01/C-08 修复的关键：unregister 需要等待 executingCount==0。
     *
     * @param regionId region ID
     */
    public void notifyTaskCompleted(final long regionId) {
        RegionQueue queue = regionQueues.get(regionId);
        if (queue != null) {
            queue.regionState().endExecution();
        }
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
     *
     * <p>注意：这是近似值 (C-23)，仅用于 load balancing 和 metrics。
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

        for (var entry : regionQueues.entrySet()) {
            RegionQueue queue = entry.getValue();
            // 标记为 DRAINING
            queue.deactivate();
            // 直接强制关闭（shutdown 时不再等待 executing）
            queue.forceClose();
            remaining.addAll(queue.drain());
        }
        regionQueues.clear();

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
