package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * Scheduler binding for a plugin identity.
 *
 * <p>Per V2 §17-18 every task submitted through {@code Mili.scheduler()}
 * carries the submitting plugin's {@link PluginId} as task owner. The
 * runtime uses the domain to:</p>
 * <ul>
 *   <li>count tasks per plugin;</li>
 *   <li>apply per-plugin parallelism caps and priority hints;</li>
 *   <li>deny submissions from plugins in {@link PluginStatus#DISABLED}.</li>
 * </ul>
 *
 * <p>The owning {@link PluginId} is the domain's owner identity. Two plugins
 * do not share a domain &mdash; the addon model shares the parent's domain
 * (see V2 §20) which is encoded as {@link #sharedWithParent()}.</p>
 */
public final class SchedulerDomain {

    /** Suggested priority (lower = earlier). */
    public static final int DEFAULT_PRIORITY = 50;

    /** Conflict-state plugins are scheduled with this reduced priority. */
    public static final int CONFLICT_PRIORITY = 1000;

    /** Observe-state plugins. Lower than NORMAL but not as degraded as CONFLICT. */
    public static final int OBSERVE_PRIORITY = 200;

    /** Unlimited parallelism. */
    public static final int UNLIMITED = -1;

    private final PluginId owner;
    private final Optional<PluginId> inheritedFrom;  // addon → parent
    private final int priority;
    private final int maxConcurrentTasks;
    private final boolean enforceQuota;
    private final boolean acceptsSubmissions;

    private SchedulerDomain(@NotNull final PluginId owner,
                            @NotNull final Optional<PluginId> inheritedFrom,
                            final int priority,
                            final int maxConcurrentTasks,
                            final boolean enforceQuota,
                            final boolean acceptsSubmissions) {
        this.owner = owner;
        this.inheritedFrom = inheritedFrom;
        this.priority = priority;
        this.maxConcurrentTasks = maxConcurrentTasks;
        this.enforceQuota = enforceQuota;
        this.acceptsSubmissions = acceptsSubmissions;
    }

    /**
     * Default domain for an ACTIVE plugin. No caps, default priority.
     */
    @NotNull
    public static SchedulerDomain forActive(@NotNull final PluginId owner) {
        return new SchedulerDomain(owner, Optional.empty(),
                DEFAULT_PRIORITY, UNLIMITED, false, true);
    }

    /**
     * Domain for an addon sharing its parent's scheduler.
     */
    @NotNull
    public static SchedulerDomain forAddon(@NotNull final PluginId addon,
                                           @NotNull final PluginId parent) {
        return new SchedulerDomain(addon, Optional.of(parent),
                DEFAULT_PRIORITY, UNLIMITED, false, true);
    }

    /**
     * Domain for a CONFLICT plugin: low priority, single concurrent task.
     */
    @NotNull
    public static SchedulerDomain forConflict(@NotNull final PluginId owner) {
        return new SchedulerDomain(owner, Optional.empty(),
                CONFLICT_PRIORITY, 1, true, true);
    }

    /**
     * Domain for an OBSERVE plugin: low priority, modest concurrency cap.
     */
    @NotNull
    public static SchedulerDomain forObserve(@NotNull final PluginId owner) {
        return new SchedulerDomain(owner, Optional.empty(),
                OBSERVE_PRIORITY, 4, true, true);
    }

    /**
     * Domain for a DISABLED / FAILED plugin: rejects all submissions.
     */
    @NotNull
    public static SchedulerDomain forRejected(@NotNull final PluginId owner) {
        return new SchedulerDomain(owner, Optional.empty(),
                Integer.MAX_VALUE, 0, true, false);
    }

    /** @return owning plugin id (== task owner on every submitted task). */
    @NotNull public PluginId owner() { return owner; }

    /** @return parent domain for an addon, or empty if this is a top-level domain. */
    @NotNull public Optional<PluginId> inheritedFrom() { return inheritedFrom; }

    public boolean sharedWithParent() { return inheritedFrom.isPresent(); }

    public int priority() { return priority; }

    public int maxConcurrentTasks() { return maxConcurrentTasks; }

    public boolean enforceQuota() { return enforceQuota; }

    public boolean acceptsSubmissions() { return acceptsSubmissions; }

    /**
     * @return true if a new task may be submitted given the current inflight count.
     */
    public boolean canSubmit(final int currentInflight) {
        if (!acceptsSubmissions) return false;
        if (!enforceQuota) return true;
        if (maxConcurrentTasks == UNLIMITED) return true;
        return currentInflight < maxConcurrentTasks;
    }

    @Override
    public String toString() {
        return "SchedulerDomain{owner=" + owner.value()
                + (sharedWithParent() ? ", sharedWith=" + inheritedFrom.get().value() : "")
                + ", priority=" + priority
                + ", maxConcurrentTasks=" + maxConcurrentTasks
                + ", enforceQuota=" + enforceQuota
                + ", acceptsSubmissions=" + acceptsSubmissions + "}";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof SchedulerDomain other)) return false;
        return priority == other.priority
                && maxConcurrentTasks == other.maxConcurrentTasks
                && enforceQuota == other.enforceQuota
                && acceptsSubmissions == other.acceptsSubmissions
                && owner.equals(other.owner)
                && inheritedFrom.equals(other.inheritedFrom);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, inheritedFrom, priority,
                maxConcurrentTasks, enforceQuota, acceptsSubmissions);
    }
}