package fun.bm.mili.lmili.thread.runtime.conflict;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.List;
import java.util.ArrayList;

/**
 * 并行冲突检测器 —— 检测并发访问中的不安全操作。
 *
 * <p>需启用：{@code -Dmili.debug.parallel=true}
 *
 * <p>检测类型：
 * <ul>
 *   <li>{@link ConflictType#CROSS_REGION_ACCESS} —— 跨 Region 访问</li>
 *   <li>{@link ConflictType#SHARED_OBJECT_ACCESS} —— 共享对象访问</li>
 *   <li>{@link ConflictType#UNSAFE_ENTITY_ACCESS} —— 不安全实体访问</li>
 *   <li>{@link ConflictType#UNSAFE_BLOCK_ENTITY_ACCESS} —— 不安全方块实体访问</li>
 *   <li>{@link ConflictType#DOUBLE_TICK} —— 重复 Tick</li>
 *   <li>{@link ConflictType#LATE_GENERATION} —— 迟到 Generation</li>
 *   <li>{@link ConflictType#OWNER_VIOLATION} —— 所有者违规</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有方法都是线程安全的。
 */
public final class ParallelConflictDetector {

    /** 是否启用检测 */
    private final boolean enabled;

    /** 冲突计数器 */
    private final ConcurrentHashMap<ConflictType, LongAdder> conflictCounters = new ConcurrentHashMap<>();

    /** 详细冲突记录（最多保存 N 条） */
    private final ConcurrentHashMap<ConflictType, List<ConflictEvent>> recentEvents = new ConcurrentHashMap<>();

    /** 最大保留的事件数 */
    private static final int MAX_RECENT_EVENTS = 100;

    /**
     * 冲突类型。
     */
    public enum ConflictType {
        /** 跨 Region 访问 */
        CROSS_REGION_ACCESS("Cross Region Access"),
        /** 共享对象访问 */
        SHARED_OBJECT_ACCESS("Shared Object Access"),
        /** 不安全实体访问 */
        UNSAFE_ENTITY_ACCESS("Unsafe Entity Access"),
        /** 不安全方块实体访问 */
        UNSAFE_BLOCK_ENTITY_ACCESS("Unsafe Block Entity Access"),
        /** 重复 Tick */
        DOUBLE_TICK("Double Tick"),
        /** 迟到 Generation */
        LATE_GENERATION("Late Generation"),
        /** 所有者违规 */
        OWNER_VIOLATION("Owner Violation");

        private final String displayName;

        ConflictType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * 冲突事件。
     */
    public record ConflictEvent(
            ConflictType type,
            long threadId,
            long regionId,
            long generationId,
            long timestampNanos,
            String details
    ) {
        @Override
        public String toString() {
            return String.format("[%s] %s region=%d gen=%d thread=%d - %s",
                    ConflictTypeNames.get(type), type.displayName(),
                    regionId, generationId, threadId, details);
        }
    }

    /**
     * 创建并行冲突检测器。
     *
     * @param enabled 是否启用检测
     */
    public ParallelConflictDetector(boolean enabled) {
        this.enabled = enabled;
        // 初始化计数器
        for (ConflictType type : ConflictType.values()) {
            conflictCounters.put(type, new LongAdder());
            recentEvents.put(type, new ArrayList<>());
        }
    }

    /**
     * 从系统属性创建检测器。
     */
    public static ParallelConflictDetector fromSystemProperty() {
        boolean enabled = Boolean.getBoolean("mili.debug.parallel");
        return new ParallelConflictDetector(enabled);
    }

    /**
     * 是否启用。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 记录一次冲突。
     *
     * @param type      冲突类型
     * @param regionId  Region ID
     * @param generationId Generation ID
     * @param details   详细信息
     */
    public void recordConflict(ConflictType type, long regionId, long generationId, String details) {
        if (!enabled) return;

        conflictCounters.get(type).increment();

        // 记录事件（限制数量）
        List<ConflictEvent> events = recentEvents.get(type);
        if (events.size() < MAX_RECENT_EVENTS) {
            events.add(new ConflictEvent(
                    type,
                    Thread.currentThread().threadId(),
                    regionId,
                    generationId,
                    System.nanoTime(),
                    details
            ));
        }
    }

    /**
     * 获取指定类型的冲突次数。
     */
    public long getConflictCount(ConflictType type) {
        LongAdder counter = conflictCounters.get(type);
        return counter != null ? counter.sum() : 0;
    }

    /**
     * 获取总冲突次数。
     */
    public long getTotalConflicts() {
        long total = 0;
        for (LongAdder counter : conflictCounters.values()) {
            total += counter.sum();
        }
        return total;
    }

    /**
     * 获取指定类型的最近事件。
     */
    public List<ConflictEvent> getRecentEvents(ConflictType type) {
        List<ConflictEvent> events = recentEvents.get(type);
        return events != null ? new ArrayList<>(events) : new ArrayList<>();
    }

    /**
     * 生成报告。
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("LMili Parallel Safety Report\n");
        sb.append("============================\n\n");

        for (ConflictType type : ConflictType.values()) {
            long count = getConflictCount(type);
            sb.append(String.format("%s: %d\n", type.displayName(), count));
        }

        sb.append(String.format("\nTotal: %d\n", getTotalConflicts()));

        return sb.toString();
    }

    /**
     * 重置所有计数器。
     */
    public void reset() {
        for (LongAdder counter : conflictCounters.values()) {
            counter.reset();
        }
        for (List<ConflictEvent> events : recentEvents.values()) {
            events.clear();
        }
    }

    /**
     * 获取快照。
     */
    public Snapshot snapshot() {
        java.util.Map<ConflictType, Long> counts = new java.util.EnumMap<>(ConflictType.class);
        for (ConflictType type : ConflictType.values()) {
            counts.put(type, getConflictCount(type));
        }
        return new Snapshot(getTotalConflicts(), counts);
    }

    /**
     * 检测器快照。
     */
    public record Snapshot(
            long totalConflicts,
            java.util.Map<ConflictType, Long> conflictCounts
    ) {
        @Override
        public String toString() {
            return "ConflictDetector{total=" + totalConflicts + ", counts=" + conflictCounts + "}";
        }
    }

    /**
     * 辅助类：获取冲突类型名称。
     */
    private static final class ConflictTypeNames {
        static String get(ConflictType type) {
            return switch (type) {
                case CROSS_REGION_ACCESS -> "CROSS";
                case SHARED_OBJECT_ACCESS -> "SHARED";
                case UNSAFE_ENTITY_ACCESS -> "ENTITY";
                case UNSAFE_BLOCK_ENTITY_ACCESS -> "BLOCK_E";
                case DOUBLE_TICK -> "DOUBLE";
                case LATE_GENERATION -> "LATE";
                case OWNER_VIOLATION -> "OWNER";
            };
        }
    }
}
