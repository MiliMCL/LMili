package fun.bm.mili.lmili.thread.regiontick.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EntityOrphanedException extends Exception {

    private final int entityId;
    @Nullable private final String entityName;
    private final Reason reason;

    public EntityOrphanedException(final int entityId, final @Nullable String entityName, final Reason reason) {
        super("Entity #" + entityId + (entityName != null ? " (" + entityName + ")" : "") + " is no longer valid: " + reason);
        this.entityId = entityId;
        this.entityName = entityName;
        this.reason = reason;
    }

    public int entityId() { return this.entityId; }
    public @Nullable String entityName() { return this.entityName; }
    public Reason reason() { return this.reason; }

    public enum Reason {
        REMOVED,
        TELEPORTED_REGION,
        TELEPORTED_WORLD,
        CHUNK_UNLOADED,
        REGION_DESTROYED
    }
}
