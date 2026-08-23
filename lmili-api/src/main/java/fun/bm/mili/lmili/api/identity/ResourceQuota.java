package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-plugin resource quota and live accounting.
 *
 * <p>Tracks (V2 §19):</p>
 * <ul>
 *   <li>Total task count submitted</li>
 *   <li>Running task count (entered, not yet exited)</li>
 *   <li>Queued task count</li>
 *   <li>Execution time (nanos)</li>
 *   <li>Failure count</li>
 *   <li>Timeout count</li>
 *   <li>Rejection count</li>
 * </ul>
 *
 * <p>The runtime, not the plugin, is responsible for calling the
 * {@code onTask*} / {@code onRejected} hooks.</p>
 */
public final class ResourceQuota {

    private final PluginId owner;
    private final int maxConcurrentTasks;
    private final int maxQueuedTasks;

    private final AtomicLong tasksSubmitted = new AtomicLong();
    private final AtomicLong tasksRunning = new AtomicLong();
    private final AtomicLong tasksQueued = new AtomicLong();
    private final AtomicLong tasksCompleted = new AtomicLong();
    private final AtomicLong tasksFailed = new AtomicLong();
    private final AtomicLong tasksTimedOut = new AtomicLong();
    private final AtomicLong tasksCancelled = new AtomicLong();
    private final AtomicLong totalExecutionNanos = new AtomicLong();
    private final AtomicLong maxExecutionNanos = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    private ResourceQuota(@NotNull final PluginId owner,
                          final int maxConcurrentTasks,
                          final int maxQueuedTasks) {
        this.owner = owner;
        this.maxConcurrentTasks = maxConcurrentTasks;
        this.maxQueuedTasks = maxQueuedTasks;
    }

    @NotNull
    public static ResourceQuota unlimited(@NotNull final PluginId owner) {
        return new ResourceQuota(owner, UNLIMITED, UNLIMITED);
    }

    @NotNull
    public static ResourceQuota strict(@NotNull final PluginId owner) {
        return new ResourceQuota(owner, 1, 1);
    }

    @NotNull
    public static ResourceQuota observe(@NotNull final PluginId owner) {
        return new ResourceQuota(owner, 4, 16);
    }

    @NotNull
    public static ResourceQuota denied(@NotNull final PluginId owner) {
        return new ResourceQuota(owner, 0, 0);
    }

    public static final int UNLIMITED = -1;

    @NotNull public PluginId owner() { return owner; }
    public int maxConcurrentTasks() { return maxConcurrentTasks; }
    public int maxQueuedTasks() { return maxQueuedTasks; }

    public long tasksSubmitted() { return tasksSubmitted.get(); }
    public long tasksRunning() { return tasksRunning.get(); }
    public long tasksQueued() { return tasksQueued.get(); }
    public long tasksCompleted() { return tasksCompleted.get(); }
    public long tasksFailed() { return tasksFailed.get(); }
    public long tasksTimedOut() { return tasksTimedOut.get(); }
    public long tasksCancelled() { return tasksCancelled.get(); }
    public long totalExecutionNanos() { return totalExecutionNanos.get(); }
    public long maxExecutionNanos() { return maxExecutionNanos.get(); }
    public long averageExecutionNanos() {
        final long completed = tasksCompleted.get();
        return completed == 0 ? 0 : totalExecutionNanos.get() / completed;
    }
    public long rejectedCount() { return rejected.get(); }

    public boolean canAdmit(final int currentInflight, final int currentQueued) {
        if (maxConcurrentTasks == UNLIMITED && maxQueuedTasks == UNLIMITED) return true;
        if (maxConcurrentTasks != UNLIMITED && currentInflight >= maxConcurrentTasks) return false;
        if (maxQueuedTasks != UNLIMITED && currentQueued >= maxQueuedTasks) return false;
        return true;
    }

    public void onTaskSubmit() {
        tasksSubmitted.incrementAndGet();
        tasksQueued.incrementAndGet();
    }

    public void onTaskStart() {
        tasksQueued.decrementAndGet();
        tasksRunning.incrementAndGet();
    }

    public void onTaskFinish(final long executionNanos, final boolean success) {
        tasksRunning.decrementAndGet();
        tasksCompleted.incrementAndGet();
        if (!success) tasksFailed.incrementAndGet();
        if (executionNanos > 0) {
            totalExecutionNanos.addAndGet(executionNanos);
            maxExecutionNanos.accumulateAndGet(executionNanos, Math::max);
        }
    }

    public void onTaskTimedOut() {
        tasksRunning.decrementAndGet();
        tasksTimedOut.incrementAndGet();
        tasksFailed.incrementAndGet();
    }

    /**
     * Record that a submitted task was cancelled before completion (e.g. the
     * plugin was disabled). Best-effort dequeue: the running/queued split is
     * an estimate, so the queued counter is decremented at most to zero.
     */
    public void onTaskCancelled() {
        tasksCancelled.incrementAndGet();
        while (true) {
            final long cur = tasksQueued.get();
            if (cur <= 0) break;
            if (tasksQueued.compareAndSet(cur, cur - 1)) break;
        }
    }

    public void onRejected() { rejected.incrementAndGet(); }

    @Override
    public String toString() {
        return "ResourceQuota{owner=" + owner.value()
                + ", submitted=" + tasksSubmitted.get()
                + ", running=" + tasksRunning.get()
                + ", queued=" + tasksQueued.get()
                + ", completed=" + tasksCompleted.get()
                + ", failed=" + tasksFailed.get()
                + ", timedOut=" + tasksTimedOut.get()
                + ", cancelled=" + tasksCancelled.get()
                + ", avgExecNanos=" + averageExecutionNanos()
                + ", maxExecNanos=" + maxExecutionNanos.get()
                + ", rejected=" + rejected.get() + "}";
    }
}