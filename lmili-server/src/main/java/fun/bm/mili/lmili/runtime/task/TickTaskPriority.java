package fun.bm.mili.lmili.runtime.task;

/**
 * region 内相对优先级（与全局 FlushPriority 语义分离）（ARCHITECTURE_AdaptiveRuntime.md §3.13）。
 */
public enum TickTaskPriority {
    /** Entity：玩家附近、战斗 */
    HIGH,
    /** Block/Chunk 常规 */
    MEDIUM,
    /** Plugin/background */
    LOW
}
