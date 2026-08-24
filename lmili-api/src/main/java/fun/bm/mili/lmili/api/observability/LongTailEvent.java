package fun.bm.mili.lmili.api.observability;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * LMili 长尾 Tick 事件公开接口（§12 P2-3 面向插件）。
 *
 * <p>允许插件注册监听器、查询最近事件、触发 drain。
 *
 * @see fun.bm.mili.lmili.api.LMili#longTailEvents()
 */
public interface LongTailEvent {

    /** 事件等级 */
    enum Level {
        NORMAL,
        SOFT,
        HARD
    }

    /** 阻塞/等待原因分类 */
    enum BlockingReason {
        NONE,
        CHUNK_LOAD,
        POI_QUERY,
        DEPENDENCY,
        PLUGIN_GLOBAL_LOCK,
        GC,
        IO,
        UNKNOWN
    }

    long tickId();
    long regionId();
    @NotNull String thread();
    @NotNull String task();
    Level level();
    double durationMs();
    BlockingReason reason();
    double chunkWaitMs();
    int chunkWaitCount();
    double dependencyWaitMs();
    int dependencyWaitCount();
    boolean pluginTask();
    int entityCount();
    long timestampMs();

    /**
     * 长尾事件监听器（异步；插件可注册多个）。
     */
    @FunctionalInterface
    interface Listener {
        void onEvent(@NotNull LongTailEvent event);
    }

    /**
     * 注册监听器（幂等）；返回 false 表示 listener 已存在。
     */
    boolean register(@NotNull Listener listener);

    /**
     * 反注册。
     */
    boolean unregister(@NotNull Listener listener);

    /**
     * 最近 N 条 HARD 事件（最新在前）。size 最多 64。
     */
    @NotNull List<LongTailEvent> recentHardEvents(int max);

    /**
     * 最近 N 条 SOFT 事件（最新在前）。size 最多 128。
     */
    @NotNull List<LongTailEvent> recentSoftEvents(int max);

    /** 软/硬阈值（ms） */
    double softBudgetMs();
    double hardBudgetMs();

    /** 触发次数累计 */
    long softTripCount();
    long hardTripCount();
}
