package fun.bm.mili.lmili.thread.regiontick;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P0-4：跨 owner 跨 tick 的 chunk 操作 deferred queue（§4.3）。
 *
 * <p>当一个 slice 检测到自身无法直接写入某个 chunkPos（既不在 owned、也不是 read-only 转发
 * 目标），且确实需要触发跨 owner 修改时，可以调用
 * {@link RegionTickContext#scheduleCrossChunkForward(long, long, SliceOwnershipContract.MutableExternalCategory, Runnable)}
 * 投递一个 deferred op 到该 chunkPos 真正归属 region 的 context（由 caller 提供 owner regionId）。
 *
 * <p>本类仅作为"已接受的 deferred ops"容器；每次 tick 入口处由 caller 调用
 * {@link #drainAll()} 把队列搬到 caller 提供的 drain target。
 *
 * <p>线程模型：{@link #enqueue} 任何线程可调用（写入 deque 本身是 synchronized）。</p>
 */
public final class DeferredChunkOps {

    /** 单条 deferred op —— chunkPos + ownerRegionId + category + runnable body。 */
    public static final class DeferredChunkOp {
        /** 目标 chunkPos（如 {@code LevelChunk.asLong(x, z)}）。 */
        public final long chunkPos;
        /** 该 chunk 真实 owner 的 regionId（caller 须事先解析）。 */
        public final long ownerRegionId;
        /** 类别（区分路由策略，例如 neighbor update 必须 forward 到 chunkPos 的 owner）。 */
        public final SliceOwnershipContract.MutableExternalCategory category;
        /** 待执行操作。 */
        public final Runnable op;

        public DeferredChunkOp(long chunkPos,
                               long ownerRegionId,
                               SliceOwnershipContract.MutableExternalCategory category,
                               Runnable op) {
            this.chunkPos = chunkPos;
            this.ownerRegionId = ownerRegionId;
            this.category = Objects.requireNonNull(category, "category");
            this.op = Objects.requireNonNull(op, "op");
        }

        @Override
        public String toString() {
            return "DeferredChunkOp{chunkPos=" + chunkPos + ", ownerRegion=" + ownerRegionId
                    + ", category=" + category + "}";
        }
    }

    private final Deque<DeferredChunkOp> queue = new ArrayDeque<>();
    private final AtomicLong totalEnqueued = new AtomicLong();
    private final AtomicLong totalDrained = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();

    public DeferredChunkOps() {}

    /**
     * 入队一个 deferred op。
     */
    public synchronized void enqueue(@NotNull DeferredChunkOp op) {
        Objects.requireNonNull(op, "op");
        queue.addLast(op);
        totalEnqueued.incrementAndGet();
    }

    /**
     * 出队并执行所有 pending ops。执行成功累计到 totalDrained；异常累计到 totalRejected。
     *
     * <p>调用方负责：在 region tick 主线程或独立线程中调用本方法把 ops 真正应用到目标 owner。
     * 典型路径：调用方先把目标 ops 按 ownerRegionId 分组，再调用
     * {@code MiliScheduler.schedule(RegionTask.withRegionId(ownerId))} 把 op 转发到该 owner 的
     * tick context 中执行。</p>
     */
    public synchronized void drainAll(@NotNull Runnable exceptionSink) {
        Objects.requireNonNull(exceptionSink, "exceptionSink");
        DeferredChunkOp op;
        while ((op = queue.pollFirst()) != null) {
            try {
                op.op.run();
                totalDrained.incrementAndGet();
            } catch (Throwable t) {
                totalRejected.incrementAndGet();
                try {
                    exceptionSink.run();
                } catch (Throwable inner) {
                    // ignore
                }
            }
        }
    }

    /**
     * 把所有 pending ops 移到 caller 提供的 drain target（不执行 ops）。
     *
     * <p>用于 caller 想要按 ownerRegionId 路由的场景 —— 一次取出，逐个调度到对应 owner。</p>
     */
    public synchronized void moveToAndClear(@NotNull Deque<DeferredChunkOp> target) {
        Objects.requireNonNull(target, "target");
        target.addAll(queue);
        queue.clear();
    }

    public synchronized int pendingSize() { return queue.size(); }

    public long getTotalEnqueued() { return totalEnqueued.get(); }
    public long getTotalDrained() { return totalDrained.get(); }
    public long getTotalRejected() { return totalRejected.get(); }
}