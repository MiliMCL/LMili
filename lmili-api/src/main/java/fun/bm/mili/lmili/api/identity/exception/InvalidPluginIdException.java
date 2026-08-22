package fun.bm.mili.lmili.api.identity.exception;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a plugin id string fails validation. Carries the offending
 * raw input and a human-readable reason; the runtime logs it and rejects
 * the offending plugin rather than silently rewriting it.
 */
public class InvalidPluginIdException extends RuntimeException {

    private final String rawInput;

    public InvalidPluginIdException(@NotNull final String rawInput,
                                   @NotNull final String reason) {
        super("Invalid PluginId '" + rawInput + "': " + reason);
        this.rawInput = rawInput;
    }

    public InvalidPluginIdException(@NotNull final String rawInput,
                                   @NotNull final String reason,
                                   @NotNull final Throwable cause) {
        super("Invalid PluginId '" + rawInput + "': " + reason, cause);
        this.rawInput = rawInput;
    }

    /** @return the raw input that failed validation. */
    @NotNull
    public String rawInput() { return rawInput; }
}