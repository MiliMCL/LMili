package fun.bm.mili.lmili.runtime.io;

/**
 * Region 负载快照 —— PersistencePriority 合成输入之一（RegionPriority / PlayerPresence）。
 * （文档提到但代码中不存在的类型，由本实施创建合理最小版本，语义与 ARCHITECTURE_AdaptiveRuntime.md §4.4 一致。）
 *
 * @param regionType HOT=3 / NORMAL=2 / IDLE=1（RegionLoadMonitor 输入折算）
 * @param loadFactor 0.0~1.0 负载因子
 * @param playerCount 活跃玩家数（非决定性，仅加权）
 * @param dirtyRegions 该 region 的脏区块数（近似）
 */
public record RegionLoadSnapshot(
        RegionType regionType,
        double loadFactor,
        int playerCount,
        int dirtyRegions
) {

    public enum RegionType {
        HOT(3), NORMAL(2), IDLE(1);

        private final int priorityRank;

        RegionType(int priorityRank) {
            this.priorityRank = priorityRank;
        }

        public int priorityRank() {
            return priorityRank;
        }
    }

    public static RegionLoadSnapshot neutral() {
        return new RegionLoadSnapshot(RegionType.NORMAL, 0.0, 0, 0);
    }

    /** 从现有 RegionLoadMonitor 的 loadFactor 折算 region 类型（HOT&gt;0.7 / IDLE&lt;0.3 / 其余 NORMAL） */
    public static RegionLoadSnapshot fromLoadFactor(double loadFactor) {
        RegionType type = loadFactor >= 0.7 ? RegionType.HOT : (loadFactor <= 0.3 ? RegionType.IDLE : RegionType.NORMAL);
        return new RegionLoadSnapshot(type, loadFactor, 0, 0);
    }
}
