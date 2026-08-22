package fun.bm.mili.lmili.api.identity;

import fun.bm.mili.lmili.api.identity.exception.InvalidPluginIdException;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable value object representing an LMili plugin identity.
 *
 * <h2>Format</h2>
 * <pre>
 *   publisher.plugin
 *   publisher.plugin.addon
 *   publisher.plugin.addon.sub
 * </pre>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Each segment matches {@code [a-z0-9][a-z0-9._-]*}.</li>
 *   <li>At least two segments (publisher + plugin).</li>
 *   <li>Total length at most {@link #MAX_LENGTH_BYTES} bytes (UTF-8).</li>
 *   <li>No empty segments, no leading/trailing dots.</li>
 *   <li>Strictly lowercase: uppercase letters are rejected by {@link #parse(String)};
 *       use {@link #normalize(String)} for legacy names with mixed case.</li>
 *   <li>Disallowed characters: space, '/', '\\', ':', ';', '@', '#', '$' and any non-ASCII.</li>
 * </ul>
 *
 * <p>Per the V2 spec, {@link #parse(String)} does <em>not</em> silently rewrite
 * "Xucy.Mili" into "xucy.mili" — every distinct string must represent a distinct
 * identity. Callers that need case-insensitive behavior use
 * {@link #normalize(String)} which lowercases ASCII first and then validates;
 * {@link #tryNormalize(String)} returns {@code null} on unrecoverable input.</p>
 */
public final class PluginId implements Comparable<PluginId> {

    /** Maximum id length in bytes (UTF-8). */
    public static final int MAX_LENGTH_BYTES = 255;

    private static final java.util.regex.Pattern SEGMENT_PATTERN =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final String value;
    private final Optional<PluginId> parentCache;

    private PluginId(@NotNull final String canonical) {
        this.value = canonical;
        this.parentCache = computeParent(canonical);
    }

    private static Optional<PluginId> computeParent(@NotNull final String v) {
        final int lastDot = v.lastIndexOf('.');
        if (lastDot < 0) return Optional.empty();
        return Optional.of(new PluginId(v.substring(0, lastDot)));
    }

    /**
     * Parse and validate a plugin id. Throws {@link InvalidPluginIdException} on
     * any rule violation. Does not rewrite uppercase characters.
     *
     * @throws InvalidPluginIdException if the input does not satisfy the rules
     */
    @NotNull
    public static PluginId parse(@NotNull final String raw) {
        Objects.requireNonNull(raw, "raw");
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidPluginIdException(raw, "empty");
        }
        final int byteLen = utf8Length(trimmed);
        if (byteLen > MAX_LENGTH_BYTES) {
            throw new InvalidPluginIdException(raw,
                    "exceeds " + MAX_LENGTH_BYTES + " bytes (got " + byteLen + ")");
        }
        if (trimmed.startsWith(".") || trimmed.endsWith(".")) {
            throw new InvalidPluginIdException(raw, "leading or trailing '.'");
        }
        if (trimmed.contains("..")) {
            throw new InvalidPluginIdException(raw, "empty segment '..'");
        }
        final String[] segments = trimmed.split("\\.", -1);
        if (segments.length < 2) {
            throw new InvalidPluginIdException(raw,
                    "must contain at least publisher.plugin (got " + segments.length + " segment(s))");
        }
        for (int i = 0; i < segments.length; i++) {
            final String seg = segments[i];
            if (!SEGMENT_PATTERN.matcher(seg).matches()) {
                throw new InvalidPluginIdException(raw,
                        "segment #" + i + " '" + seg + "' does not match [a-z0-9][a-z0-9._-]*");
            }
        }
        return new PluginId(trimmed);
    }

    /** Lenient variant of {@link #parse(String)}. */
    public static PluginId parseNullable(final String raw) {
        if (raw == null) return null;
        try {
            return parse(raw.trim());
        } catch (final InvalidPluginIdException ignored) {
            return null;
        }
    }

    /**
     * Normalize a string into canonical form: trim, lowercase ASCII, then validate.
     * Returns the canonical id on success; throws on unrecoverable input.
     *
     * <p>Bridge for legacy Bukkit plugins whose {@code plugin.yml} names contain
     * uppercase or underscores. Used by the runtime when no {@code lmili.json}
     * is present, never on user-supplied ids.</p>
     */
    @NotNull
    public static PluginId normalize(@NotNull final String raw) {
        Objects.requireNonNull(raw, "raw");
        return parse(asciiLower(raw.trim()));
    }

    /** Lenient variant of {@link #normalize(String)}. */
    public static PluginId tryNormalize(final String raw) {
        if (raw == null) return null;
        try { return normalize(raw); }
        catch (final InvalidPluginIdException ignored) { return null; }
    }

    private static String asciiLower(@NotNull final String s) {
        final char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            final char c = chars[i];
            if (c >= 'A' && c <= 'Z') chars[i] = (char) (c + 32);
        }
        return new String(chars);
    }

    private static int utf8Length(@NotNull final String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** @return the canonical id string. */
    @NotNull
    public String value() { return value; }

    /** @return true if this id has one or more addon segments (i.e. &gt;= 3 total). */
    public boolean isAddon() {
        return value.indexOf('.', value.indexOf('.') + 1) >= 0;
    }

    /**
     * @return the parent id (one segment shorter), or {@code Optional.empty()} if
     *         this id has no parent (i.e. it is a two-segment {@code publisher.plugin}).
     */
    @NotNull
    public Optional<PluginId> parentId() { return parentCache; }

    /**
     * @return true if {@code parent} is a strict prefix of this id
     *         (i.e. {@code this.value().startsWith(parent.value() + ".")}).
     */
    public boolean isChildOf(@NotNull final PluginId parent) {
        final String prefix = parent.value + ".";
        return value.startsWith(prefix);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() { return value.hashCode(); }

    @Override
    public int compareTo(@NotNull final PluginId o) { return value.compareTo(o.value); }

    @Override
    public String toString() { return value; }
}