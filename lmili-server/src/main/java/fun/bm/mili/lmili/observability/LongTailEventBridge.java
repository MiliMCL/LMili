package fun.bm.mili.lmili.observability;

import fun.bm.mili.lmili.api.LMili;
import fun.bm.mili.lmili.api.observability.LongTailEvent;
import fun.bm.mili.lmili.thread.runtime.diagnostics.LongTailTickDiagnostics;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 把内部 {@link LongTailTickDiagnostics} 适配为插件 API 的 {@link LongTailEvent}。
 *
 * <p>同时挂到 {@link LMili#installLongTailEvents(LongTailEvent)}，插件作者可通过
 * {@link LMili#longTailEvents()} 直接注册监听器 / 查最近事件。
 */
public final class LongTailEventBridge implements LongTailEvent {

    private final LongTailTickDiagnostics source;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public LongTailEventBridge(LongTailTickDiagnostics source) {
        this.source = source;
        if (source != null) {
            LMili.installLongTailEvents(this);
        }
    }

    // ---- LongTailEvent API ----

    @Override public long tickId() { return 0; }
    @Override public long regionId() { return 0; }
    @Override public @NotNull String thread() { return Thread.currentThread().getName(); }
    @Override public @NotNull String task() { return ""; }
    @Override public @NotNull Level level() { return Level.NORMAL; }
    @Override public double durationMs() { return 0; }
    @Override public @NotNull BlockingReason reason() { return BlockingReason.NONE; }
    @Override public double chunkWaitMs() { return 0; }
    @Override public int chunkWaitCount() { return 0; }
    @Override public double dependencyWaitMs() { return 0; }
    @Override public int dependencyWaitCount() { return 0; }
    @Override public boolean pluginTask() { return false; }
    @Override public int entityCount() { return 0; }
    @Override public long timestampMs() { return System.currentTimeMillis(); }

    @Override
    public boolean register(@NotNull Listener listener) {
        return listeners.add(listener);
    }

    @Override
    public boolean unregister(@NotNull Listener listener) {
        return listeners.remove(listener);
    }

    @Override
    public @NotNull List<LongTailEvent> recentHardEvents(int max) {
        if (source == null) return Collections.emptyList();
        LongTailTickDiagnostics.Snapshot snap = source.snapshot();
        List<LongTailEvent> out = new ArrayList<>(Math.min(max, snap.hardEvents().size()));
        int n = 0;
        for (LongTailTickDiagnostics.HardTickEvent e : snap.hardEvents()) {
            if (n++ >= max) break;
            out.add(adapt(e));
        }
        return out;
    }

    @Override
    public @NotNull List<LongTailEvent> recentSoftEvents(int max) {
        if (source == null) return Collections.emptyList();
        LongTailTickDiagnostics.Snapshot snap = source.snapshot();
        List<LongTailEvent> out = new ArrayList<>(Math.min(max, snap.softEvents().size()));
        int n = 0;
        for (LongTailTickDiagnostics.SoftTickEvent e : snap.softEvents()) {
            if (n++ >= max) break;
            out.add(adapt(e));
        }
        return out;
    }

    @Override
    public double softBudgetMs() {
        return source != null ? source.softBudgetNanos() / 1_000_000.0 : 0;
    }

    @Override
    public double hardBudgetMs() {
        return source != null ? source.hardBudgetNanos() / 1_000_000.0 : 0;
    }

    @Override
    public long softTripCount() {
        return source != null ? source.softTripCount() : 0;
    }

    @Override
    public long hardTripCount() {
        return source != null ? source.hardTripCount() : 0;
    }

    /** 触发 listener（内部调用） */
    public void dispatch(LongTailEvent event) {
        for (Listener l : listeners) {
            try {
                l.onEvent(event);
            } catch (Throwable ignored) {
                // listener 错误不允许反向影响
            }
        }
    }

    public static final class BridgeAdapter implements LongTailEvent {
        private final long tickId;
        private final long regionId;
        private final String thread;
        private final String task;
        private final Level level;
        private final double durationMs;
        private final BlockingReason reason;
        private final double chunkWaitMs;
        private final int chunkWaitCount;
        private final double dependencyWaitMs;
        private final int dependencyWaitCount;
        private final boolean pluginTask;
        private final int entityCount;
        private final long timestampMs;

        BridgeAdapter(long tickId, long regionId, String thread, String task,
                      Level level, double durationMs, BlockingReason reason,
                      double chunkWaitMs, int chunkWaitCount,
                      double dependencyWaitMs, int dependencyWaitCount,
                      boolean pluginTask, int entityCount, long timestampMs) {
            this.tickId = tickId; this.regionId = regionId; this.thread = thread; this.task = task;
            this.level = level; this.durationMs = durationMs; this.reason = reason;
            this.chunkWaitMs = chunkWaitMs; this.chunkWaitCount = chunkWaitCount;
            this.dependencyWaitMs = dependencyWaitMs; this.dependencyWaitCount = dependencyWaitCount;
            this.pluginTask = pluginTask; this.entityCount = entityCount; this.timestampMs = timestampMs;
        }

        @Override public long tickId() { return tickId; }
        @Override public long regionId() { return regionId; }
        @Override public @NotNull String thread() { return thread; }
        @Override public @NotNull String task() { return task; }
        @Override public @NotNull Level level() { return level; }
        @Override public double durationMs() { return durationMs; }
        @Override public @NotNull BlockingReason reason() { return reason; }
        @Override public double chunkWaitMs() { return chunkWaitMs; }
        @Override public int chunkWaitCount() { return chunkWaitCount; }
        @Override public double dependencyWaitMs() { return dependencyWaitMs; }
        @Override public int dependencyWaitCount() { return dependencyWaitCount; }
        @Override public boolean pluginTask() { return pluginTask; }
        @Override public int entityCount() { return entityCount; }
        @Override public long timestampMs() { return timestampMs; }

        // not used as an event source (the bridge holds its own listener list)
        @Override public boolean register(@NotNull Listener listener) { return false; }
        @Override public boolean unregister(@NotNull Listener listener) { return false; }
        @Override public @NotNull List<LongTailEvent> recentHardEvents(int max) { return Collections.emptyList(); }
        @Override public @NotNull List<LongTailEvent> recentSoftEvents(int max) { return Collections.emptyList(); }
        @Override public double softBudgetMs() { return 0; }
        @Override public double hardBudgetMs() { return 0; }
        @Override public long softTripCount() { return 0; }
        @Override public long hardTripCount() { return 0; }
    }

    private static LongTailEvent adapt(LongTailTickDiagnostics.HardTickEvent e) {
        return new BridgeAdapter(
                e.tickId(), e.regionId(), e.thread(), e.task(),
                Level.HARD, e.durationMs(), mapReason(e.reason()),
                e.chunkWaitMs(), e.chunkWaitCount(),
                e.dependencyWaitMs(), e.dependencyWaitCount(),
                e.pluginTask(), e.entityCount(), e.timestampMs());
    }

    private static LongTailEvent adapt(LongTailTickDiagnostics.SoftTickEvent e) {
        return new BridgeAdapter(
                e.tickId(), e.regionId(), e.thread(), e.task(),
                Level.SOFT, e.durationMs(), mapReason(e.reason()),
                e.chunkWaitMs(), e.chunkWaitCount(),
                e.dependencyWaitMs(), e.dependencyWaitCount(),
                e.pluginTask(), e.entityCount(), e.timestampMs());
    }

    private static BlockingReason mapReason(LongTailTickDiagnostics.BlockingReason r) {
        if (r == null) return BlockingReason.NONE;
        return switch (r) {
            case NONE -> BlockingReason.NONE;
            case CHUNK_LOAD -> BlockingReason.CHUNK_LOAD;
            case POI_QUERY -> BlockingReason.POI_QUERY;
            case DEPENDENCY -> BlockingReason.DEPENDENCY;
            case PLUGIN_GLOBAL_LOCK -> BlockingReason.PLUGIN_GLOBAL_LOCK;
            case GC -> BlockingReason.GC;
            case IO -> BlockingReason.IO;
            case UNKNOWN -> BlockingReason.UNKNOWN;
        };
    }
}
