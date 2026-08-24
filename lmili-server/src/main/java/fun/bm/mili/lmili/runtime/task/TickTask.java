package fun.bm.mili.lmili.runtime.task;

import fun.bm.mili.lmili.runtime.io.FlushPriority;
import fun.bm.mili.lmili.thread.scheduler.api.RegionTask;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Tick 任务 —— 实现现有 RegionTask 接口，附加预算/IO 元数据（需求 #7）（ARCHITECTURE_AdaptiveRuntime.md §3.13，D-11）。
 *
 * <p>数据流：Scheduler 提交 TickTask → 调度器路由 → ParallelExecutor 并行化执行。
 * "并行 Tick"只是 Scheduler 的一种执行策略，不是另一套调度器。
 */
public final class TickTask implements RegionTask {

    private final long regionId;
    private final TickTaskType type;
    private final TickTaskPriority priority;
    private final FlushPriority ioPriority;
    private final long estimatedNanos;
    private final ThrowingRunnable action;

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private TickTask(long regionId, TickTaskType type, TickTaskPriority priority,
                     FlushPriority ioPriority, long estimatedNanos, ThrowingRunnable action) {
        this.regionId = regionId;
        this.type = type;
        this.priority = priority;
        this.ioPriority = ioPriority;
        this.estimatedNanos = estimatedNanos;
        this.action = Objects.requireNonNull(action, "action");
    }

    @Override
    public void execute() throws Exception {
        action.run();
    }

    @Override
    public long regionId() {
        return regionId;
    }

    @Override
    public boolean isBlocking() {
        return type == TickTaskType.SAVE; // save 走阻塞隔离
    }

    @Override
    public long timeoutMillis() {
        return 0; // 默认无超时（tick 任务由预算约束）
    }

    @Override
    public @NotNull String name() {
        return "TickTask[" + type + "@" + regionId + "]";
    }

    public TickTaskType type() {
        return type;
    }

    public TickTaskPriority priority() {
        return priority;
    }

    public FlushPriority ioPriority() {
        return ioPriority;
    }

    public long estimatedNanos() {
        return estimatedNanos;
    }

    public static Builder builder(long regionId) {
        return new Builder(regionId);
    }

    public static final class Builder {
        private final long regionId;
        private TickTaskType type = TickTaskType.ENTITY;
        private TickTaskPriority priority = TickTaskPriority.MEDIUM;
        private FlushPriority ioPriority = FlushPriority.NORMAL;
        private long estimatedNanos = 0;
        private ThrowingRunnable action = () -> {
        };

        Builder(long regionId) {
            this.regionId = regionId;
        }

        public Builder type(TickTaskType t) { this.type = t; return this; }
        public Builder priority(TickTaskPriority p) { this.priority = p; return this; }
        public Builder ioPriority(FlushPriority p) { this.ioPriority = p; return this; }
        public Builder estimatedNanos(long n) { this.estimatedNanos = n; return this; }
        public Builder action(ThrowingRunnable a) { this.action = a; return this; }
        public Builder action(Runnable a) { this.action = a::run; return this; }

        public TickTask build() {
            return new TickTask(regionId, type, priority, ioPriority, estimatedNanos, action);
        }
    }
}
