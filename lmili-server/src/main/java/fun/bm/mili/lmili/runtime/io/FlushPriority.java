package fun.bm.mili.lmili.runtime.io;

/**
 * flush 优先级（需求 #5 / #6，ARCHITECTURE_AdaptiveRuntime.md §3.11）。
 * 数值越大优先级越高；DEFERRED 表示"在压力态下可被跳过"。
 *
 * <p>注意：本枚举只是<strong>最终等级</strong>，如何得出见 {@link PersistencePriority}
 * （合成公式，D-22），<strong>禁止</strong>在业务代码里写死"有玩家→HIGH"。
 */
public enum FlushPriority {
    /** 压力态下延后（可能被跳过；数据仍在内存，不丢失） */
    DEFERRED(0),
    /** 无人 idle region 的背景 flush */
    LOW(1),
    /** 常规 */
    NORMAL(2),
    /** 活跃玩家 region / 近期写入密集 / 插件申请获准 */
    HIGH(3),
    /** shutdown / 紧急保存 */
    CRITICAL(4);

    private final int rank;

    FlushPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean atLeast(FlushPriority other) {
        return rank >= other.rank;
    }

    /** 向下调一档（IO_PRESSURE 降级用；DEFERRED 不再降） */
    public FlushPriority downgrade() {
        return switch (this) {
            case CRITICAL -> HIGH;
            case HIGH -> NORMAL;
            case NORMAL -> LOW;
            case LOW -> DEFERRED;
            case DEFERRED -> DEFERRED;
        };
    }

    /** 向上调一档（紧急保存用；CRITICAL 封顶） */
    public FlushPriority upgrade() {
        return switch (this) {
            case DEFERRED -> LOW;
            case LOW -> NORMAL;
            case NORMAL -> HIGH;
            case HIGH, CRITICAL -> CRITICAL;
        };
    }
}
