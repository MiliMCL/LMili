package fun.bm.mili.api;

/**
 * Thrown when the target entity of an operation no longer exists
 * (removed, teleported away, died).
 *
 * <p>This is a checked exception. Users of the suspend-style API must catch or declare it.
 * It is designed to replace Folia retired callback - you no longer need to remember
 * to write a second callback, the framework automatically throws this into your task when the entity disappears.</p>
 */
public class EntityOrphanedException extends Exception {

    private final int entityId;
    private final String entityName;
    private final Reason reason;

    public EntityOrphanedException(final int entityId, final String entityName, final Reason reason) {
        super("Entity #" + entityId + (entityName != null ? " (" + entityName + ")" : "")
                + " is orphaned: " + reason);
        this.entityId = entityId;
        this.entityName = entityName;
        this.reason = reason;
    }

    public EntityOrphanedException(final int entityId, final String entityName,
                                    final Reason reason, final Throwable cause) {
        super("Entity #" + entityId + (entityName != null ? " (" + entityName + ")" : "")
                + " is orphaned: " + reason, cause);
        this.entityId = entityId;
        this.entityName = entityName;
        this.reason = reason;
    }

    /**
     * @return orphaned entity ID
     */
    public int getEntityId() { return entityId; }

    /**
     * @return entity name, may be null
     */
    public String getEntityName() { return entityName; }

    /**
     * @return reason for orphaning
     */
    public Reason getReason() { return reason; }

    /**
     * Reason types for entity orphaning.
     */
    public enum Reason {
        /** Entity fully removed (item picked up, mob despawned, etc) */
        REMOVED,
        /** Entity teleported to another region */
        TELEPORTED_REGION,
        /** Entity died */
        DIED,
        /** Entity unloaded (chunk unloaded) */
        UNLOADED,
        /** Other reason */
        OTHER
    }
}
