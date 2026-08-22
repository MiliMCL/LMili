package fun.bm.mili.lmili.api.identity.exception;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when {@code lmili.json} fails validation: missing required fields,
 * malformed JSON, addon without parent, etc.
 */
public class InvalidPluginMetadataException extends RuntimeException {

    private final String source;

    public InvalidPluginMetadataException(@NotNull final String source,
                                         @NotNull final String reason) {
        super("Invalid plugin metadata (" + source + "): " + reason);
        this.source = source;
    }

    public InvalidPluginMetadataException(@NotNull final String source,
                                         @NotNull final String reason,
                                         @Nullable final Throwable cause) {
        super("Invalid plugin metadata (" + source + "): " + reason, cause);
        this.source = source;
    }

    /** @return a marker for where the bad metadata came from (e.g. {@code "lmili.json"}). */
    @NotNull
    public String source() { return source; }
}