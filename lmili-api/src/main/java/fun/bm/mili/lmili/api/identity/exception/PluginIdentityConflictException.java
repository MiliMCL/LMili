package fun.bm.mili.lmili.api.identity.exception;

import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.conflict.ConflictReason;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a plugin identity registration is rejected because the
 * id is already owned by another plugin. Per the V2 spec, conflict is
 * the normal outcome and never fatal — this exception exists so the
 * bootstrap can log it without catching {@link RuntimeException}.
 */
public class PluginIdentityConflictException extends RuntimeException {

    private final PluginId id;
    private final ConflictReason reason;

    public PluginIdentityConflictException(@NotNull final PluginId id,
                                          @NotNull final ConflictReason reason) {
        super("Plugin identity conflict for id '" + id.value() + "': " + reason);
        this.id = id;
        this.reason = reason;
    }

    public PluginIdentityConflictException(@NotNull final PluginId id,
                                          @NotNull final ConflictReason reason,
                                          @NotNull final String detail) {
        super("Plugin identity conflict for id '" + id.value() + "': " + reason + " (" + detail + ")");
        this.id = id;
        this.reason = reason;
    }

    @NotNull public PluginId id() { return id; }
    @NotNull public ConflictReason reason() { return reason; }
}