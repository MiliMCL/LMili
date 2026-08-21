package fun.bm.mili.lmili.thread.runtime.message;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨 Region 消息 —— 通过消息队列实现 Region 间通信。
 *
 * <p>禁止：
 * <pre>
 * Region A → write → Region B
 * </pre>
 *
 * <p>改为：
 * <pre>
 * Region A
 *    ↓
 * CrossRegionMessage
 *    ↓
 * Region B mailbox
 *    ↓
 * Region B commit
 * </pre>
 *
 * <h3>线程安全</h3>
 * <p>消息是不可变的，可以安全跨线程传递。
 */
public final class CrossRegionMessage {

    /** 消息 ID 生成器 */
    private static final AtomicLong MESSAGE_ID_GENERATOR = new AtomicLong(0);

    /** 消息唯一 ID */
    private final long messageId;

    /** 源 Region ID */
    private final long sourceRegionId;

    /** 目标 Region ID */
    private final long targetRegionId;

    /** 源 Generation ID */
    private final long generationId;

    /** 消息操作 */
    private final Runnable operation;

    /** 消息创建时间 */
    private final long createdAtNanos;

    /** 消息优先级 */
    private final MessagePriority priority;

    /**
     * 消息优先级。
     */
    public enum MessagePriority {
        /** 高优先级（紧急操作） */
        HIGH,
        /** 普通优先级（常规操作） */
        NORMAL,
        /** 低优先级（可延迟操作） */
        LOW
    }

    /**
     * 创建跨 Region 消息。
     *
     * @param sourceRegionId  源 Region ID
     * @param targetRegionId  目标 Region ID
     * @param generationId    源 Generation ID
     * @param operation       要执行的操作
     * @param priority        消息优先级
     */
    public CrossRegionMessage(long sourceRegionId, long targetRegionId,
                               long generationId, Runnable operation,
                               MessagePriority priority) {
        this.messageId = MESSAGE_ID_GENERATOR.incrementAndGet();
        this.sourceRegionId = sourceRegionId;
        this.targetRegionId = targetRegionId;
        this.generationId = generationId;
        this.operation = operation;
        this.priority = priority;
        this.createdAtNanos = System.nanoTime();
    }

    /**
     * 创建普通优先级的跨 Region 消息。
     */
    public CrossRegionMessage(long sourceRegionId, long targetRegionId,
                               long generationId, Runnable operation) {
        this(sourceRegionId, targetRegionId, generationId, operation, MessagePriority.NORMAL);
    }

    // ---- 访问 ----

    public long messageId() { return messageId; }
    public long sourceRegionId() { return sourceRegionId; }
    public long targetRegionId() { return targetRegionId; }
    public long generationId() { return generationId; }
    public Runnable operation() { return operation; }
    public long createdAtNanos() { return createdAtNanos; }
    public MessagePriority priority() { return priority; }

    /**
     * 执行此消息。
     *
     * <p>此方法应该在目标 Region 的 Owner 线程上调用。
     */
    public void execute() {
        if (operation != null) {
            operation.run();
        }
    }

    @Override
    public String toString() {
        return "CrossRegionMessage{id=" + messageId +
                ", source=" + sourceRegionId +
                ", target=" + targetRegionId +
                ", gen=" + generationId +
                ", priority=" + priority + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CrossRegionMessage that = (CrossRegionMessage) o;
        return messageId == that.messageId;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(messageId);
    }

    /**
     * 创建构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 跨 Region 消息构建器。
     */
    public static final class Builder {
        private long sourceRegionId;
        private long targetRegionId;
        private long generationId;
        private Runnable operation;
        private MessagePriority priority = MessagePriority.NORMAL;

        Builder() {}

        public Builder sourceRegionId(long sourceRegionId) {
            this.sourceRegionId = sourceRegionId;
            return this;
        }

        public Builder targetRegionId(long targetRegionId) {
            this.targetRegionId = targetRegionId;
            return this;
        }

        public Builder generationId(long generationId) {
            this.generationId = generationId;
            return this;
        }

        public Builder operation(Runnable operation) {
            this.operation = operation;
            return this;
        }

        public Builder priority(MessagePriority priority) {
            this.priority = priority;
            return this;
        }

        public CrossRegionMessage build() {
            if (operation == null) {
                throw new IllegalStateException("Operation must not be null");
            }
            return new CrossRegionMessage(sourceRegionId, targetRegionId, generationId, operation, priority);
        }
    }
}
