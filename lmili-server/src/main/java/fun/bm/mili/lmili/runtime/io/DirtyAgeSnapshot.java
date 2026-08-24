package fun.bm.mili.lmili.runtime.io;

/**
 * 脏数据年龄快照 —— PersistencePriority 合成输入之一（DirtyAge 分量）。
 * （文档提到但代码中不存在的类型，由本实施创建合理最小版本，语义与 ARCHITECTURE_AdaptiveRuntime.md §4.4 一致。）
 *
 * @param dirtyBytes     未同步脏数据量（字节，近似）
 * @param ageSinceSyncNanos 距上次 sync 的时间
 * @param dirtyChunkCount 未同步区块数
 */
public record DirtyAgeSnapshot(
        long dirtyBytes,
        long ageSinceSyncNanos,
        int dirtyChunkCount
) {

    public static DirtyAgeSnapshot neutral() {
        return new DirtyAgeSnapshot(0, 0, 0);
    }

    /** 归一化脏度 0~1：按 64MB 封顶的脏字节占比 */
    public double dirtyFactor() {
        final double capBytes = 64L * 1024 * 1024;
        return Math.min(1.0, dirtyBytes / capBytes);
    }
}
