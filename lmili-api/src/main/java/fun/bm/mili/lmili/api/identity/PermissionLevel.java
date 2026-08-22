package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;

/**
 * Coarse-grained permission levels. Higher values include lower ones.
 *
 * <p>The full ACL-style permission system is reserved for a future stage.
 * Per V2 §21, this layer is just the &quot;is this plugin allowed to talk to
 * the scheduler at all&quot; gate.</p>
 */
public enum PermissionLevel {
    /** Cannot submit tasks; cannot read registry. Pure introspection only. */
    NONE(0),
    /** May query the identity registry but cannot schedule. */
    READ_ONLY(1),
    /** May submit tasks to the public scheduler. Default for ACTIVE. */
    SCHEDULER(2),
    /** May pin tasks to a specific region. Future. */
    REGION_PIN(3),
    /** May call admin hooks. Server-only. */
    ADMIN(Integer.MAX_VALUE);

    private final int rank;

    PermissionLevel(final int rank) { this.rank = rank; }

    public boolean covers(@NotNull final PermissionLevel other) {
        return this.rank >= other.rank;
    }
}