package fun.bm.mili.lmili.thread.runtime.message;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region 邮箱 —— 接收跨 Region 消息的队列。
 *
 * <p>每个 Region 有一个邮箱，其他 Region 发送到此 Region 的消息会进入队列。
 * 目标 Region 的 Owner 线程在合适的时机处理队列中的消息。
 *
 * <h3>线程安全</h3>
 * <p>邮箱是无锁的并发队列，支持多 Producer 单 Consumer 模型。
 */
public final class RegionMailbox {

    /** Region ID */
    private final long regionId;

    /** 消息队列 */
    private final ConcurrentLinkedQueue<CrossRegionMessage> messageQueue = new ConcurrentLinkedQueue<>();

    /** 已接收消息计数 */
    private final AtomicLong receivedCount = new AtomicLong(0);

    /** 已处理消息计数 */
    private final AtomicLong processedCount = new AtomicLong(0);

    /** 队列最大深度追踪 */
    private final AtomicInteger maxDepth = new AtomicInteger(0);

    /**
     * 创建 Region 邮箱。
     *
     * @param regionId Region ID
     */
    public RegionMailbox(long regionId) {
        this.regionId = regionId;
    }

    /**
     * 接收消息。
     *
     * <p>由其他 Region 的线程调用（Producer）。
     *
     * @param message 要接收的消息
     */
    public void receive(CrossRegionMessage message) {
        messageQueue.offer(message);
        receivedCount.incrementAndGet();

        // 更新最大深度
        int currentSize = messageQueue.size();
        int currentMax;
        while ((currentMax = maxDepth.get()) < currentSize) {
            if (maxDepth.compareAndSet(currentMax, currentSize)) {
                break;
            }
        }
    }

    /**
     * 处理所有待处理的消息。
     *
     * <p>应由目标 Region 的 Owner 线程调用（Consumer）。
     *
     * @return 处理的消息数量
     */
    public int processAll() {
        int count = 0;
        CrossRegionMessage message;
        while ((message = messageQueue.poll()) != null) {
            try {
                message.execute();
                processedCount.incrementAndGet();
                count++;
            } catch (Exception e) {
                // 记录错误但不中断处理
                processedCount.incrementAndGet();
            }
        }
        return count;
    }

    /**
     * 处理指定数量的消息。
     *
     * @param maxMessages 最大处理数量
     * @return 实际处理的消息数量
     */
    public int process(int maxMessages) {
        int count = 0;
        CrossRegionMessage message;
        while (count < maxMessages && (message = messageQueue.poll()) != null) {
            try {
                message.execute();
                processedCount.incrementAndGet();
                count++;
            } catch (Exception e) {
                processedCount.incrementAndGet();
            }
        }
        return count;
    }

    /**
     * 获取待处理消息数量。
     */
    public int pendingCount() {
        return messageQueue.size();
    }

    /**
     * 检查是否有待处理消息。
     */
    public boolean hasPending() {
        return !messageQueue.isEmpty();
    }

    // ---- 统计 ----

    public long regionId() { return regionId; }
    public long getReceivedCount() { return receivedCount.get(); }
    public long getProcessedCount() { return processedCount.get(); }
    public int getMaxDepth() { return maxDepth.get(); }

    /**
     * 获取快照。
     */
    public Snapshot snapshot() {
        return new Snapshot(regionId, pendingCount(), receivedCount.get(),
                processedCount.get(), maxDepth.get());
    }

    /**
     * 邮箱快照。
     */
    public record Snapshot(
            long regionId,
            int pendingMessages,
            long totalReceived,
            long totalProcessed,
            int maxDepth
    ) {
        @Override
        public String toString() {
            return String.format("Mailbox{#%d, pending=%d, received=%d, processed=%d, maxDepth=%d}",
                    regionId, pendingMessages, totalReceived, totalProcessed, maxDepth);
        }
    }
}
