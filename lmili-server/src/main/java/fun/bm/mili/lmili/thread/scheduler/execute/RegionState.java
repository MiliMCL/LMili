package fun.bm.mili.lmili.thread.scheduler.execute;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Region 运行时状态机 —— 统一生命周期 + 执行权所有权。
 *
 * <h3>状态转换（R2-01/R2-02 修复）</h3>
 * <pre>
 * 单一 AtomicReference 保证所有转换原子：
 *
 * (ACTIVE, IDLE) ──acquire──▶ (ACTIVE, RUNNING(owner))
 *                                  └───release──▶ (ACTIVE, IDLE)
 *
 * (ACTIVE, *) ──drain──▶ (DRAINING, *) ──(running==0)──▶ (CLOSED, IDLE)
 * </pre>
 *
 * <h3>任务提交协议（R2-02 修复）</h3>
 * <pre>
 * tryAcceptTask(): 原子递增 queuedCount (仅 ACTIVE)
 * tryAcquireExecution(): CAS IDLE → RUNNING (仅 ACTIVE)
 * releaseExecution(): CAS RUNNING → IDLE
 * releaseTask(): 递减 queuedCount
 * </pre>
 *
 * <p>所有状态转换通过单一 AtomicReference 的 CAS 保证原子性，
 * 消除 phase 检查与计数器递增之间的 race condition。
 */
public final class RegionState {

    /**
     * 生命周期阶段。
     */
    public enum Phase {
        /** 正常运行 */
        ACTIVE,
        /** 正在排空 */
        DRAINING,
        /** 已关闭 */
        CLOSED
    }

    /**
     * 执行状态（R2-01 核心修复）。
     */
    public enum ExecState {
        /** 空闲 */
        IDLE,
        /** 正在执行（值为 owner worker id） */
        RUNNING
    }

    /**
     * 不可变的 Region 运行时状态。
     *
     * <p>包含 phase + executionOwner，通过单一 AtomicReference CAS 保证原子转换。
     */
    public record Snapshot(Phase phase, ExecState execState, int owner, int queued, long generation) {
        static final Snapshot INITIAL = new Snapshot(Phase.ACTIVE, ExecState.IDLE, -1, 0, 0);

        boolean isActive() {
            return phase == Phase.ACTIVE;
        }

        boolean isIdle() {
            return execState == ExecState.IDLE;
        }

        boolean isRunning() {
            return execState == ExecState.RUNNING;
        }
    }

    /**
     * 执行令牌 —— 持有它即表示拥有 Region 执行权。
     *
     * <p>必须在使用完毕后调用 {@link #release()}。
     *
     * <p>RISK-01 修复：每个 token 携带 regionId 与 generation 两个不变量。
     * regionId + generation 与持有该 token 的 worker 线程（TreadLocal
     * MiliTickThread.setRegionOwnership）一一对应，杜绝 TickThread 身份伪造。</p>
     */
    public static final class ExecutionToken {
        final RegionState state;
        final long regionId;
        final long generation;
        final int owner;
        private final AtomicBoolean released = new AtomicBoolean(false);

        ExecutionToken(RegionState state, long regionId, long generation, int owner) {
            this.state = state;
            this.regionId = regionId;
            this.generation = generation;
            this.owner = owner;
        }

        /**
         * RISK-01 修复：返回 token 持有的 regionId。
         */
        public long regionId() {
            return regionId;
        }

        /**
         * RISK-01 修复：返回 token 的 generation（用于防 stale token）。
         */
        public long generation() {
            return generation;
        }

        /**
         * RISK-09 修复：返回 token 的 owner workerId。
         *
         * <p>用于校验"持有此 token 的 worker 才是合法执行者"——防 work stealing
         * 偷走 region ownership。</p>
         */
        public int owner() {
            return owner;
        }

        /**
         * LATEST-01 修复：释放执行权。
         *
         * <p>使用 {@code AtomicBoolean.compareAndSet(false, true)} 保证幂等性：
         * 多次调用 release() 只会产生一次有效的 state.releaseExecution()。
         *
         * <p>修复前 bug：released 是 volatile boolean，release() 先设 released=true
         * 再调用 releaseExecution()；后者检查 token.released 后直接 return，导致
         * RUNNING→IDLE CAS 永远不执行。
         */
        public void release() {
            if (released.compareAndSet(false, true)) {
                state.releaseExecution(this);
            }
        }

        /**
         * 转移执行权到另一个 worker（用于 BlockingTask 场景，R2-03 修复）。
         *
         * <p>新的 owner 将在其任务完成后调用 release()。
         */
        public ExecutionToken transferTo(int newOwner) {
            if (released.get()) return null;
            return state.transferExecution(this, newOwner);
        }

        @Override
        public String toString() {
            return "Token{region=" + regionId + ", owner=" + owner + ", gen=" + generation + "}";
        }
    }

    // ---- 内部状态 ----

    private final AtomicReference<Snapshot> snapshot;
    private final AtomicLong generationCounter = new AtomicLong(0);

    // ---- LATEST-02: close barrier callback ----
    private volatile Runnable closeCallback;

    /**
     * RISK-01 修复：本 RegionState 关联的 regionId。
     *
     * <p>创建后由 {@link RegionQueue} 设置（因为 RegionState 可能先于 queue 构造）。
     * 写入是不可变的（final field），因此 token 持有 regionId 是线程安全的。</p>
     */
    private final AtomicLong regionIdRef = new AtomicLong(0L);

    /**
     * 创建 ACTIVE/IDLE 状态的 RegionState。
     *
     * <p>无参构造保持向后兼容。生产代码应在创建后立即调用
     * {@link #bindRegionId(long)} 注入 regionId；未注入时 token.regionId() == 0L。
     */
    public RegionState() {
        this.snapshot = new AtomicReference<>(Snapshot.INITIAL);
    }

    /**
     * RISK-01 修复：绑定 regionId（创建 token 时会读取）。
     *
     * <p>只能设置一次；多次调用会被忽略。</p>
     */
    public void bindRegionId(final long regionId) {
        regionIdRef.compareAndSet(0L, regionId);
    }

    /**
     * RISK-01 修复：获取绑定的 regionId。
     */
    public long regionId() {
        return regionIdRef.get();
    }

    /**
     * LATEST-02: 设置 close 回调。
     *
     * <p>当 RegionState 成功转换到 CLOSED 时，此回调会被触发（且仅触发一次）。
     * 用于通知 {@link WorkStealingCoordinator} 的 closeBarrier 完成。
     */
    public void setCloseCallback(Runnable callback) {
        this.closeCallback = callback;
    }

    /**
     * LATEST-02: 触发 close 回调（幂等）。
     */
    private void fireCloseCallback() {
        Runnable cb = closeCallback;
        if (cb != null) {
            closeCallback = null; // 防止重复触发
            cb.run();
        }
    }

    /**
     * 获取当前状态快照。
     */
    public Snapshot getSnapshot() {
        return snapshot.get();
    }

    /**
     * 获取当前生命周期阶段。
     */
    public Phase getPhase() {
        return snapshot.get().phase;
    }

    /**
     * 是否处于 ACTIVE 阶段。
     */
    public boolean isActive() {
        return snapshot.get().phase == Phase.ACTIVE;
    }

    // ---- 任务提交协议 (R2-02 修复) ----

    /**
     * 尝试接受一个新任务（原子递增 queuedCount）。
     *
     * <p>仅当 ACTIVE 时成功。保证：
     * <ul>
     *   <li>如果返回 true，后续 deactivate() 不能立即 CLOSED（因为 queued > 0）</li>
     *   <li>DRAINING/CLOSED 状态下始终返回 false</li>
     * </ul>
     *
     * @return true 如果成功接受任务
     */
    public boolean tryAcceptTask() {
        while (true) {
            Snapshot current = snapshot.get();
            if (current.phase != Phase.ACTIVE) {
                return false;
            }
            Snapshot next = new Snapshot(
                    current.phase, current.execState, current.owner,
                    current.queued + 1, current.generation);
            if (snapshot.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /**
     * LATEST-02: 释放一个已完成任务的 queuedCount。
     *
     * <p>释放后若处于 DRAINING 且满足关闭条件，自动尝试关闭并触发 barrier。
     */
    public void releaseTask() {
        while (true) {
            Snapshot current = snapshot.get();
            if (current.queued <= 0) return;
            Snapshot next = new Snapshot(
                    current.phase, current.execState, current.owner,
                    current.queued - 1, current.generation);
            if (snapshot.compareAndSet(current, next)) {
                // LATEST-02: 释放后检查是否满足 close 条件
                tryCloseIfDraining();
                return;
            }
        }
    }

    // ---- 执行权获取/释放 (R2-01 修复) ----

    /**
     * 尝试获取 Region 执行权。
     *
     * <p>R2-01 核心修复：通过单一原子 CAS 同时检查 ACTIVE + IDLE，
     * 消除 check-then-increment 的 race window。
     *
     * <p>成功时返回 {@link ExecutionToken}，失败时返回 null。
     * 持有 token 期间，其他 worker 无法获取同一 Region 的执行权。
     *
     * @param owner worker id（用于追踪所有权）
     * @return ExecutionToken 或 null
     */
    public ExecutionToken tryAcquireExecution(int owner) {
        while (true) {
            Snapshot current = snapshot.get();
            if (current.phase != Phase.ACTIVE) {
                return null;
            }
            if (current.execState != ExecState.IDLE) {
                return null; // 已有执行者
            }
            long newGen = generationCounter.incrementAndGet();
            Snapshot next = new Snapshot(
                    Phase.ACTIVE, ExecState.RUNNING, owner,
                    current.queued, newGen);
            if (snapshot.compareAndSet(current, next)) {
                // RISK-01：token 携带 regionId
                return new ExecutionToken(this, regionIdRef.get(), newGen, owner);
            }
        }
    }

    /**
     * LATEST-01 修复：释放执行权（RUNNING → IDLE）。
     *
     * <p>幂等性由 {@link ExecutionToken#release()} 的 {@code AtomicBoolean.compareAndSet} 保证。
     * 本方法通过 generation + owner 双重验证确保只有当前持有者能释放。
     *
     * <p>修复前 bug：先检查 token.released（已被 release() 设为 true）后直接 return，
     * 导致 RUNNING→IDLE CAS 永远不执行。
     */
    void releaseExecution(ExecutionToken token) {
        while (true) {
            Snapshot current = snapshot.get();
            if (current.generation != token.generation) {
                return; // 已经被转移或重置
            }
            if (current.execState != ExecState.RUNNING || current.owner != token.owner) {
                return; // 不是当前持有者
            }
            Snapshot next = new Snapshot(
                    current.phase, ExecState.IDLE, -1,
                    current.queued, current.generation);
            if (snapshot.compareAndSet(current, next)) {
                // LATEST-02: 释放后检查是否满足 close 条件
                tryCloseIfDraining();
                return;
            }
        }
    }

    /**
     * LATEST-01 修复：转移执行权到新的 owner（R2-03 修复：BlockingTask 场景）。
     *
     * <p>用于将执行权从 SchedulerWorker 转移到 BlockingWorker。
     */
    ExecutionToken transferExecution(ExecutionToken token, int newOwner) {
        if (token.released.get()) return null;
        while (true) {
            Snapshot current = snapshot.get();
            if (current.generation != token.generation) {
                return null; // generation 已经变了
            }
            if (current.execState != ExecState.RUNNING || current.owner != token.owner) {
                return null; // 不是当前持有者
            }
            long newGen = generationCounter.incrementAndGet();
            Snapshot next = new Snapshot(
                    current.phase, ExecState.RUNNING, newOwner,
                    current.queued, newGen);
            if (snapshot.compareAndSet(current, next)) {
                token.released.set(true); // 旧 token 失效（AtomicBoolean）
                // RISK-01：新 token 也携带 regionId
                return new ExecutionToken(this, regionIdRef.get(), newGen, newOwner);
            }
        }
    }

    // ---- 生命周期转换 ----

    /**
     * 尝试进入 DRAINING 阶段。
     *
     * <p>阻止新任务提交。已在队列中的任务仍可消费。
     *
     * @return true 如果成功进入 DRAINING；false 如果已经 CLOSED
     */
    public boolean tryBeginDrain() {
        while (true) {
            Snapshot current = snapshot.get();
            if (current.phase == Phase.CLOSED) return false;
            if (current.phase == Phase.DRAINING) return true; // 幂等
            if (current.execState == ExecState.RUNNING) {
                // 仍在执行中，只标记 DRAINING 但不关闭
                Snapshot next = new Snapshot(
                        Phase.DRAINING, current.execState, current.owner,
                        current.queued, current.generation);
                if (snapshot.compareAndSet(current, next)) {
                    return true;
                }
                continue;
            }
            Snapshot next = new Snapshot(
                    Phase.DRAINING, current.execState, current.owner,
                    current.queued, current.generation);
            if (snapshot.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /**
     * LATEST-02 修复：尝试关闭 Region（DRAINING → CLOSED）。
     *
     * <p>仅在 running==IDLE 且 queued==0 时成功。
     * 成功时触发 close 回调（完成 closeBarrier）。
     *
     * @return true 如果成功关闭
     */
    public boolean tryClose() {
        while (true) {
            Snapshot current = snapshot.get();
            if (current.phase == Phase.ACTIVE) {
                if (!snapshot.compareAndSet(current, new Snapshot(
                        Phase.DRAINING, current.execState, current.owner,
                        current.queued, current.generation))) {
                    continue;
                }
                current = snapshot.get(); // 重新读取
            }
            if (current.phase == Phase.CLOSED) return true;
            if (current.phase == Phase.DRAINING) {
                if (current.execState == ExecState.RUNNING) return false;
                if (current.queued > 0) return false;
                if (snapshot.compareAndSet(current, new Snapshot(
                        Phase.CLOSED, ExecState.IDLE, -1, 0, current.generation))) {
                    fireCloseCallback(); // LATEST-02: 触发 barrier
                    return true;
                }
            }
        }
    }

    /**
     * LATEST-02 修复：如果当前处于 DRAINING 且满足关闭条件，尝试关闭。
     *
     * <p>在 releaseExecution / releaseTask 后调用，
     * 当所有任务完成时自动完成 closeBarrier。</p>
     */
    private void tryCloseIfDraining() {
        Snapshot current = snapshot.get();
        if (current.phase == Phase.DRAINING
                && current.execState == ExecState.IDLE
                && current.queued == 0) {
            tryClose();
        }
    }

    /**
     * LATEST-02 修复：强制关闭（忽略 running/queued）。
     *
     * <p>仍触发 closeBarrier 以避免 unregister 阻塞。
     */
    public void forceClose() {
        Snapshot current = snapshot.get();
        if (snapshot.compareAndSet(current,
                new Snapshot(Phase.CLOSED, ExecState.IDLE, -1, 0, current.generation))) {
            fireCloseCallback(); // LATEST-02: 触发 barrier
        }
    }

    /**
     * 获取当前正在执行的 worker ID（-1 表示空闲）。
     */
    public int getExecutingWorker() {
        Snapshot s = snapshot.get();
        return s.execState == ExecState.RUNNING ? s.owner : -1;
    }

    /**
     * 获取队列中的任务数。
     */
    public int getQueuedCount() {
        return snapshot.get().queued;
    }

    /**
     * 检查是否有任务在执行。
     */
    public boolean isRunning() {
        return snapshot.get().execState == ExecState.RUNNING;
    }

    /**
     * RISK-12 修复：返回当前 regionState 的 generation。
     *
     * <p>用于 PollResult 校验 token.generation == region.generation —— 如果不相等，
     * 说明 region 在 token 创建后被 unregister/reregister，token 已 stale。</p>
     */
    public long getGeneration() {
        return snapshot.get().generation;
    }

    @Override
    public String toString() {
        Snapshot s = snapshot.get();
        return String.format("RegionState{%s, %s, owner=%d, queued=%d}",
                s.phase, s.execState, s.owner, s.queued);
    }
}
