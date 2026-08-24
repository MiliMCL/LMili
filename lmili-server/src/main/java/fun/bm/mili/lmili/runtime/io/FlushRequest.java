package fun.bm.mili.lmili.runtime.io;

/**
 * flush 请求（Scheduler 侧 → IO 池；本设计优先使用现有 flusher 路径 + 优先级元数据）
 * （ARCHITECTURE_AdaptiveRuntime.md §3.11）。
 */
public record FlushRequest(
        long regionId,
        FlushPriority priority,
        long deadlineNanos,
        Object payload
) {

    public static FlushRequest of(long regionId, FlushPriority priority) {
        return new FlushRequest(regionId, priority, 0L, null);
    }
}
