package fun.bm.mili.lmili.api.identity.exception;

import fun.bm.mili.lmili.api.identity.PluginId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when an addon plugin declares a parent id that does not match
 * the runtime-known parent derived from its own id, or references an
 * id that is not yet registered.
 */
public class InvalidAddonParentException extends RuntimeException {

    private final PluginId addonId;
    private final PluginId declaredParent;

    public InvalidAddonParentException(@NotNull final PluginId addonId,
                                       @NotNull final PluginId declaredParent,
                                       @NotNull final String reason) {
        super("Invalid parent '" + declaredParent.value() + "' for addon '"
                + addonId.value() + "': " + reason);
        this.addonId = addonId;
        this.declaredParent = declaredParent;
    }

    @NotNull public PluginId addonId() { return addonId; }
    @NotNull public PluginId declaredParent() { return declaredParent; }

    /**
     * @return the runtime-known parent derived from the id, if any.
     */
    @Nullable
    public PluginId runtimeParent() {
        return addonId.parentId().orElse(null);
    }
}