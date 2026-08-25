package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Observability counters per plugin identity. Per the V2 spec, when a plugin
 * is in {@link PluginStatus#OBSERVE} or {@link PluginStatus#CONFLICT} the
 * runtime records:
 *
 * <ul>
 *   <li>API requests</li>
 *   <li>Scheduler requests</li>
 *   <li>Task creation count</li>
 *   <li>Task execution failures</li>
 *   <li>Timeouts</li>
 *   <li>Resource use (kept as atomic counters; full histograms later)</li>
 *   <li>Permission denials</li>
 * </ul>
 *
 * <p>Identity stays lightweight (V2 §31): these counters live in
 * {@link PluginRuntimeContext}, not in {@link PluginIdentity}.</p>
 */
public final class ObservabilityContext {

    private final AtomicLong apiRequests = new AtomicLong();
    private final AtomicLong schedulerRequests = new AtomicLong();
    private final AtomicLong taskCreated = new AtomicLong();
    private final AtomicLong taskFailed = new AtomicLong();
    private final AtomicLong taskTimedOut = new AtomicLong();
    private final AtomicLong permissionDenied = new AtomicLong();
    private final AtomicLong totalExecutionNanos = new AtomicLong();
    /** §C plugin 直接用 BukkitScheduler（绕过 LMili）的次数 */
    private final AtomicLong directBukkitCalls = new AtomicLong();

    public ObservabilityContext() {}

    public void recordApiRequest() { apiRequests.incrementAndGet(); }
    public void recordSchedulerRequest() { schedulerRequests.incrementAndGet(); }
    public void recordTaskCreated() { taskCreated.incrementAndGet(); }
    public void recordTaskFailed() { taskFailed.incrementAndGet(); }
    public void recordTaskTimedOut() { taskTimedOut.incrementAndGet(); }
    public void recordPermissionDenied() { permissionDenied.incrementAndGet(); }
    public void recordExecutionNanos(final long nanos) {
        totalExecutionNanos.addAndGet(Math.max(0, nanos));
    }
    /** §C LMili Required: 记录 plugin 直接调 BukkitScheduler（绕过 LMili） */
    public void recordDirectBukkitCall() { directBukkitCalls.incrementAndGet(); }

    /**
     * 记录安全违规事件。
     *
     * @param type    违规类型
     * @param message 违规消息
     */
    public void recordSecurityViolation(@NotNull String type, @NotNull String message) {
        // 记录安全违规，可用于审计
        permissionDenied.incrementAndGet();
    }

    /**
     * 记录同步任务执行。
     *
     * @param description   任务描述
     * @param elapsedNanos  执行时间（纳秒）
     * @param success       是否成功
     * @param exception     异常（可为 null）
     */
    public void recordSyncTask(@NotNull String description, long elapsedNanos, boolean success, @org.jetbrains.annotations.Nullable Throwable exception) {
        if (success) {
            recordExecutionNanos(elapsedNanos);
        } else {
            recordTaskFailed();
            if (exception instanceof java.util.concurrent.TimeoutException) {
                recordTaskTimedOut();
            }
        }
    }

    public long apiRequests() { return apiRequests.get(); }
    public long schedulerRequests() { return schedulerRequests.get(); }
    public long tasksCreated() { return taskCreated.get(); }
    public long tasksFailed() { return taskFailed.get(); }
    public long tasksTimedOut() { return taskTimedOut.get(); }
    public long permissionDenials() { return permissionDenied.get(); }
    public long totalExecutionNanos() { return totalExecutionNanos.get(); }
    public long directBukkitCalls() { return directBukkitCalls.get(); }

    /** Snapshot for {@code /plugins info}. */
    @NotNull
    public Snapshot snapshot() {
        return new Snapshot(apiRequests.get(), schedulerRequests.get(),
                taskCreated.get(), taskFailed.get(), taskTimedOut.get(),
                permissionDenied.get(), totalExecutionNanos.get(),
                directBukkitCalls.get());
    }

    public record Snapshot(
            long apiRequests,
            long schedulerRequests,
            long tasksCreated,
            long tasksFailed,
            long tasksTimedOut,
            long permissionDenials,
            long totalExecutionNanos,
            long directBukkitCalls
    ) { }
}