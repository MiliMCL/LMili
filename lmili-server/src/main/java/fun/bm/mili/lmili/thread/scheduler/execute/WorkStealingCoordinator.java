package fun.bm.mili.lmili.thread.scheduler.execute;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * 工作窃取协调器 —— 实现跨 region 的负载均衡。
 *
 * <h3>修复的并发问题</h3>
 * <ul>
 *   <li><b>C-01</b>：Region 独占执行（通过 ExecutionToken）</li>
 *   <li><b>C-02/C-08</b>：unregister 与已取出任务冲突</li>
 *   <li><b>R2-01</b>：原子化执行权获取</li>
 *   <li><b>R2-05</b>：RegionSlot generation 防止 register/unregister 替换</li>
 *   <li><b>R2-06</b>：CompletableFuture barrier 替代有限 yield 等待</li>
 * </ul>
 */
public final class WorkStealingCoordinator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final int workerCount;
    private final ConcurrentHashMap<Long, RegionSlot> regionSlots;
    private final WorkerState[] workers;
    private final LongAdder totalSteals = new LongAdder();
    private final LongAdder totalStealAttempts = new LongAdder();
    private final LongAdder failedSteals = new LongAdder();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong generationCounter = new AtomicLong(0);
    private final AtomicBoolean[] parkedWorkers;

    // ---- R2-07: worker 唤醒支持 ----
    private volatile Thread[] workerThreads = null;

    /**
     * Region 槽位 —— 包含 generation 和 RegionQueue 引用。
     *
     * <p>R2-05 修复：使用 generation 区分同一 regionId 的不同生命周期。
     */
    public static final class RegionSlot {
        final long generation;
        final RegionQueue queue;
        final CompletableFuture<Void> closeBarrier;

        RegionSlot(long generation, RegionQueue queue) {
            this.generation = generation;
            this.queue = queue;
            this.closeBarrier = new CompletableFuture<>();
        }
    }

    public WorkStealingCoordinator(int workerCount) {
        this.workerCount = Math.max(1, workerCount);
        this.regionSlots = new ConcurrentHashMap<>();
        this.workers = new WorkerState[this.workerCount];
        this.parkedWorkers = new AtomicBoolean[this.workerCount];
        for (int i = 0; i < this.workerCount; i++) {
            workers[i] = new WorkerState(i);
            parkedWorkers[i] = new AtomicBoolean(false);
        }
        LOGGER.info("[WorkStealingCoordinator] Initialized with {} workers", this.workerCount);
    }

    /**
     * R2-07: 注册 worker 线程引用，使 submit 能唤醒 parked worker。
     *
     * <p>解决已存在的 missed-wakeup 问题：当前 submit() 推送任务后不会唤醒 parked worker，
     * 导致任务可能长时间滞留在队列中。</p>
     */
    public void setWorkerThreads(Thread[] threads) {
        this.workerThreads = threads;
    }

    /**
     * 标记 worker 为 parked 状态。
     */
    public void markWorkerParked(int workerId) {
        parkedWorkers[workerId].set(true);
    }

    /**
     * 标记 worker 为 unparked 状态。
     */
    public void markWorkerUnparked(int workerId) {
        parkedWorkers[workerId].set(false);
    }

    /**
     * 唤醒一个 parked 的 worker（如果存在）。
     */
    private void unparkOneWorker() {
        Thread[] threads = workerThreads;
        if (threads == null) return;
        for (int i = 0; i < workerCount; i++) {
            if (parkedWorkers[i].get() && threads[i] != null) {
                LockSupport.unpark(threads[i]);
                return;
            }
        }
    }

    // ---- Region 管理 (R2-05 generation 修复) ----

    @NotNull
    public RegionQueue registerRegion(final long regionId) {
        RegionSlot existing = regionSlots.get(regionId);
        if (existing != null && existing.queue.isActive()) {
            return existing.queue;
        }
        long gen = generationCounter.incrementAndGet();
        RegionQueue newQueue = new RegionQueue(regionId);
        RegionSlot newSlot = new RegionSlot(gen, newQueue);
        // LATEST-02: 连接 closeBarrier 到 RegionState 回调
        newQueue.regionState().setCloseCallback(() -> newSlot.closeBarrier.complete(null));
        RegionSlot previous = regionSlots.putIfAbsent(regionId, newSlot);
        if (previous != null) {
            if (previous.queue.isActive()) {
                return previous.queue;
            }
            // 替换旧的（已关闭的）slot
            if (regionSlots.replace(regionId, previous, newSlot)) {
                LOGGER.debug("[WorkStealingCoordinator] Re-registered region #{} (gen {} → {})",
                        regionId, previous.generation, gen);
                return newQueue;
            }
            return regionSlots.get(regionId).queue;
        }
        LOGGER.debug("[WorkStealingCoordinator] Registered region #{} (gen {})", regionId, gen);
        return newQueue;
    }

    /**
     * R2-06 修复：使用 CompletableFuture barrier 等待所有执行完成。
     */
    @NotNull
    public List<RegionTask> unregisterRegion(final long regionId) {
        RegionSlot slot = regionSlots.get(regionId);
        if (slot == null) {
            return new ArrayList<>();
        }
        RegionQueue queue = slot.queue;

        // 步骤 1：标记为 DRAINING
        queue.deactivate();

        // 步骤 2：等待 barrier（所有 execution token 被释放）
        try {
            slot.closeBarrier.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("[WorkStealingCoordinator] Region #{} close barrier timeout (still running: {})",
                    regionId, queue.regionState().isRunning());
        }

        // 步骤 3：尝试关闭
        if (!queue.tryClose()) {
            queue.forceClose();
        }

        // 步骤 4：从 map 中移除并 drain
        regionSlots.remove(regionId);
        List<RegionTask> remaining = queue.drain();

        LOGGER.debug("[WorkStealingCoordinator] Unregistered region #{} (gen {}, remaining: {})",
                regionId, slot.generation, remaining.size());
        return remaining;
    }

    public boolean isRegionRegistered(final long regionId) {
        RegionSlot slot = regionSlots.get(regionId);
        return slot != null && slot.queue.isActive();
    }

    public int registeredRegionCount() {
        return regionSlots.size();
    }

    // ---- 任务提交 (LATEST-05: generation 验证 + 重试) ----

    /**
     * LATEST-05: 提交任务到指定 region。
     *
     * <p>通过验证 queue 仍然 active 确保任务不会进入 stale generation 的队列。
     * 如果 queue 已停用（被 unregister 替换），自动重试获取新 queue。
     */
    public void submit(@NotNull RegionTask task) {
        if (!running.get()) {
            task.onCancel();
            return;
        }
        long regionId = task.regionId();

        // LATEST-05: 最多重试 3 次以应对 register/unregister 并发
        for (int attempt = 0; attempt < 3; attempt++) {
            RegionSlot slot = regionSlots.get(regionId);
            if (slot == null || !slot.queue.isActive()) {
                // region 未注册或正在 drain，创建或等待新 slot
                RegionQueue freshQueue = registerRegion(regionId);
                if (freshQueue == null) {
                    task.onCancel();
                    return;
                }
                slot = regionSlots.get(regionId);
                if (slot == null) continue; // 重试
            }

            try {
                slot.queue.push(task);
                // R2-07: 推送任务后唤醒一个 parked worker（解决 missed-wakeup）
                unparkOneWorker();
                return;
            } catch (IllegalStateException e) {
                // LATEST-05: queue 已停用（被 unregister），重试获取新 queue
            }
        }
        // 重试耗尽，取消任务
        task.onCancel();
    }

    /**
     * LATEST-05: 批量提交任务，每个任务独立走 submit 的 generation 验证 + 重试逻辑。
     */
    public void submitAll(@NotNull List<RegionTask> tasks) {
        for (RegionTask task : tasks) {
            submit(task);
        }
    }

    // ---- 任务消费 (R2-01 ExecutionToken 修复) ----

    /**
     * 获取下一个要执行的任务。
     *
     * <p>返回的 PollResult 包含任务和对应的 ExecutionToken。
     * 调用者在任务完成后必须调用 {@link PollResult#release()}。
     */
    @Nullable
    public PollResult poll(int localWorkerId) {
        if (!running.get()) return null;

        PollResult result = pollFromLocalCache(localWorkerId);
        if (result != null) return result;
        return stealWork(localWorkerId);
    }

    @Nullable
    private PollResult pollFromLocalCache(int localWorkerId) {
        WorkerState worker = workers[localWorkerId];
        long cachedRegionId = worker.lastRegionId.get();

        if (cachedRegionId >= 0) {
            RegionSlot slot = regionSlots.get(cachedRegionId);
            if (slot != null && slot.queue.isActive()) {
                RegionState state = slot.queue.regionState();
                RegionState.ExecutionToken token = state.tryAcquireExecution(localWorkerId);
                if (token != null) {
                    RegionTask task = slot.queue.pop();
                    if (task != null) {
                        return new PollResult(task, token, slot.queue);
                    }
                    token.release(); // 队列为空，回退
                }
            }
        }
        return null;
    }

    @Nullable
    private PollResult stealWork(int localWorkerId) {
        int size = regionSlots.size();
        if (size == 0) return null;

        totalStealAttempts.increment();
        int maxAttempts = Math.min(size, Math.max(3, workerCount));
        int skip = size > maxAttempts ? ThreadLocalRandom.current().nextInt(size) : 0;

        int idx = 0, attempts = 0;
        for (var entry : regionSlots.entrySet()) {
            if (idx < skip) { idx++; continue; }
            if (attempts >= maxAttempts) break;
            PollResult result = tryStealFromSlot(entry.getValue(), localWorkerId);
            if (result != null) return result;
            idx++; attempts++;
        }
        idx = 0;
        for (var entry : regionSlots.entrySet()) {
            if (idx >= skip) break;
            if (attempts >= maxAttempts) break;
            PollResult result = tryStealFromSlot(entry.getValue(), localWorkerId);
            if (result != null) return result;
            idx++; attempts++;
        }

        failedSteals.increment();
        return null;
    }

    @Nullable
    private PollResult tryStealFromSlot(RegionSlot slot, int localWorkerId) {
        if (slot != null && slot.queue.isActive()) {
            RegionState state = slot.queue.regionState();
            RegionState.ExecutionToken token = state.tryAcquireExecution(localWorkerId);
            if (token != null) {
                RegionTask task = slot.queue.steal();
                if (task != null) {
                    totalSteals.increment();
                    workers[localWorkerId].lastRegionId.set(slot.queue.regionId());
                    return new PollResult(task, token, slot.queue);
                }
                token.release(); // 队列为空，回退
            }
        }
        return null;
    }

    /**
     * 轮询结果 —— 封装了任务及其对应的 ExecutionToken 和所在队列。
     *
     * <p>调用者在任务完成后必须调用 {@link #release()} 释放执行权，
     * 或使用 {@link #transferTo(int)} 转移给其他 worker。
     *
     * <p>RISK-01：暴露 {@link #regionId()} 与 {@link #generation()} 用于
     * {@link fun.bm.mili.lmili.thread.scheduler.MiliTickThread#setRegionOwnership(long, long)}
     * —— 这是 TickThread 身份校验之外的"Region ownership" 二因素之一。
     */
    public static final class PollResult {
        public final RegionTask task;
        private final RegionState.ExecutionToken token;
        private final RegionQueue queue;
        /**
         * RISK-12 修复：PollResult 构造时 snapshot 的 regionState.generation。
         *
         * <p>执行 task 前必须与 token.generation 比对：
         *   - 相等 → token 有效，可执行
         *   - 不等 → region 已被 unregister/reregister，token stale，必须取消
         */
        private final long regionGenerationSnapshot;

        PollResult(RegionTask task, RegionState.ExecutionToken token, RegionQueue queue) {
            this.task = task;
            this.token = token;
            this.queue = queue;
            this.regionGenerationSnapshot = queue.regionState().getGeneration();
        }

        /**
         * RISK-01 修复：返回 token 持有的 regionId。
         */
        public long regionId() {
            return token.regionId();
        }

        /**
         * RISK-01 修复：返回 token 的 generation。
         *
         * <p>每个 ExecutionToken 都有一个唯一 generation，用于防止 stale token 被
         * 同一线程重复验证通过。</p>
         */
        public long generation() {
            return token.generation();
        }

        /**
         * RISK-12 修复：返回 PollResult 构造时 snapshot 的 regionState.generation。
         *
         * <p>与 {@link #generation()} 一起用于 RISK-08 的 ownership 三因素校验：
         * <pre>
         *   if (token.generation != regionGeneration) → stale → reject
         * </pre>
         */
        public long regionGeneration() {
            return regionGenerationSnapshot;
        }

        /**
         * RISK-09 修复：返回 token 的 owner workerId。
         *
         * <p>执行前必须校验 token.owner == currentWorkerId，防止 steal 跨 ownership。
         */
        public int ownerId() {
            return token.owner();
        }

        /**
         * 释放 Region 执行权。
         */
        public void release() {
            token.release();
        }

        /**
         * 转移执行权到新 worker（R2-03 修复：BlockingTask 场景）。
         *
         * @param newOwner 新 worker ID
         * @return 新的 ExecutionToken（持有者需在完成后 release）
         */
        public RegionState.ExecutionToken transferTo(int newOwner) {
            return token.transferTo(newOwner);
        }

        /**
         * 获取 Region Queue（用于标记 task 完成）。
         */
        public RegionQueue queue() {
            return queue;
        }
    }

    /**
     * 通知任务完成并释放 Region 执行权。
     *
     * @deprecated 使用 {@link PollResult#release()} 代替。
     */
    @Deprecated
    public void notifyTaskCompleted(final long regionId) {
        // 保留用于向后兼容，新代码使用 PollResult.release()
    }

    @Nullable
    public RegionQueue getQueue(final long regionId) {
        RegionSlot slot = regionSlots.get(regionId);
        return slot != null ? slot.queue : null;
    }

    @NotNull
    public List<Long> getRegisteredRegions() {
        return new ArrayList<>(regionSlots.keySet());
    }

    public long totalSteals() { return totalSteals.sum(); }
    public long totalStealAttempts() { return totalStealAttempts.sum(); }

    public double stealSuccessRate() {
        long attempts = totalStealAttempts.sum();
        return attempts > 0 ? (double) totalSteals.sum() / attempts : 0.0;
    }

    public int totalPendingTasks() {
        int total = 0;
        for (RegionSlot slot : regionSlots.values()) {
            total += slot.queue.approximateSize();
        }
        return total;
    }

    public boolean isRunning() { return running.get(); }

    // ---- 关闭 ----

    @NotNull
    public List<RegionTask> shutdown() {
        running.set(false);
        List<RegionTask> remaining = new ArrayList<>();
        for (var entry : regionSlots.entrySet()) {
            RegionQueue queue = entry.getValue().queue;
            queue.deactivate();
            queue.forceClose();
            remaining.addAll(queue.drain());
        }
        regionSlots.clear();

        LOGGER.info("[WorkStealingCoordinator] Shutdown (steals: {}, success rate: {}%)",
                totalSteals.sum(), String.format("%.1f", stealSuccessRate() * 100));
        return remaining;
    }

    private static final class WorkerState {
        final int workerId;
        final AtomicLong lastRegionId = new AtomicLong(-1);
        WorkerState(int workerId) { this.workerId = workerId; }
    }
}
