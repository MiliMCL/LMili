package fun.bm.mili.lmili.api.identity;

import fun.bm.mili.lmili.api.identity.exception.InvalidPluginMetadataException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Loader for the {@code lmili.json} metadata file.
 *
 * <h2>Location</h2>
 * <p>The file lives at the <em>JAR root</em>, side by side with
 * {@code plugin.yml}:</p>
 * <pre>
 * my-plugin.jar
 * ├── plugin.yml
 * ├── lmili.json
 * └── fun/xucy/myplugin/...
 * </pre>
 *
 * <h2>Schema</h2>
 * <p>Required:</p>
 * <ul>
 *   <li>{@code id}          &mdash; canonical plugin id (see {@link PluginId})</li>
 *   <li>{@code name}        &mdash; human-readable plugin name</li>
 *   <li>{@code version}     &mdash; SemVer recommended</li>
 *   <li>{@code publisher}   &mdash; publisher id (this stage: metadata only)</li>
 * </ul>
 * <p>Optional:</p>
 * <ul>
 *   <li>{@code type}        &mdash; {@code "plugin"} (default) or {@code "addon"}</li>
 *   <li>{@code parent}      &mdash; required when {@code type == "addon"}; must equal id.parentId()</li>
 *   <li>{@code dependencies} &mdash; free-form list of addon ids this plugin needs</li>
 *   <li>{@code trust}       &mdash; declared trust level (still defaults to UNKNOWN at runtime)</li>
 * </ul>
 *
 * <p>This loader never throws on malformed input &mdash; it returns
 * {@link Loaded} on success or {@code null} on missing/malformed file. The
 * caller (runtime bootstrap) decides what to do with each outcome.</p>
 */
public final class LmiliJsonLoader {

    /** Resource path of the metadata file (JAR root, alongside {@code plugin.yml}). */
    public static final String RESOURCE_PATH = "lmili.json";

    private LmiliJsonLoader() {}

    @Nullable
    public static Loaded load(@Nullable final InputStream stream) {
        if (stream == null) return null;
        try (InputStream in = stream) {
            final byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) return null;
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (final Exception ignored) {
            return null;
        }
    }

    @Nullable
    public static Loaded load(@Nullable final ClassLoader loader) {
        if (loader == null) return null;
        try (InputStream in = loader.getResourceAsStream(RESOURCE_PATH)) {
            return load(in);
        } catch (final Exception ignored) {
            return null;
        }
    }

    /**
     * Strict parse: throws {@link InvalidPluginMetadataException} on any rule
     * violation. The runtime bootstrap may call this; the legacy fallback
     * path prefers {@link #parseLenient(String)} which returns {@code null}.
     */
    @NotNull
    public static Loaded parse(@NotNull final String json) {
        final MiniJson.Obj obj = MiniJson.parseObject(json);
        if (obj == null) throw new InvalidPluginMetadataException(RESOURCE_PATH, "malformed JSON");

        final String idStr = obj.string("id");
        final String name = obj.string("name");
        final String version = obj.string("version");
        final String publisher = obj.string("publisher");
        if (idStr == null || name == null || version == null || publisher == null) {
            throw new InvalidPluginMetadataException(RESOURCE_PATH,
                    "missing required field (id/name/version/publisher)");
        }
        final PluginId id;
        try {
            id = PluginId.parse(idStr);
        } catch (final RuntimeException e) {
            throw new InvalidPluginMetadataException(RESOURCE_PATH,
                    "invalid id '" + idStr + "': " + e.getMessage(), e);
        }

        final String typeStr = obj.string("type");
        final PluginType type;
        if (typeStr == null || typeStr.equalsIgnoreCase("plugin")) {
            type = PluginType.PLUGIN;
        } else if (typeStr.equalsIgnoreCase("addon")) {
            type = PluginType.ADDON;
        } else {
            throw new InvalidPluginMetadataException(RESOURCE_PATH,
                    "unknown type '" + typeStr + "'");
        }

        Optional<PluginId> parent = Optional.empty();
        if (type == PluginType.ADDON) {
            final String parentStr = obj.string("parent");
            if (parentStr == null) {
                throw new InvalidPluginMetadataException(RESOURCE_PATH,
                        "addon '" + id.value() + "' must declare 'parent'");
            }
            try {
                parent = Optional.of(PluginId.parse(parentStr));
            } catch (final RuntimeException e) {
                throw new InvalidPluginMetadataException(RESOURCE_PATH,
                        "invalid parent id '" + parentStr + "': " + e.getMessage(), e);
            }
        }

        final List<String> deps = obj.stringList("dependencies");
        final String trustStr = obj.string("trust");
        PluginTrustLevel trust = PluginTrustLevel.UNKNOWN;
        if (trustStr != null) {
            try {
                final PluginTrustLevel declared = PluginTrustLevel.valueOf(trustStr.toUpperCase());
                // Per V2 spec: don't auto-promote to VERIFIED based on metadata.
                if (declared != PluginTrustLevel.VERIFIED) {
                    trust = declared;
                }
            } catch (final IllegalArgumentException ignored) { }
        }

        return new Loaded(id, name, version, publisher, type, parent,
                deps == null ? List.of() : List.copyOf(deps), trust);
    }

    /** Lenient variant: returns {@code null} instead of throwing. */
    @Nullable
    public static Loaded parseLenient(@Nullable final String json) {
        if (json == null || json.isBlank()) return null;
        try { return parse(json); }
        catch (final RuntimeException ignored) { return null; }
    }

    /**
     * Parsed {@code lmili.json} payload.
     */
    public static final class Loaded {
        private final PluginId id;
        private final String name;
        private final String version;
        private final String publisher;
        private final PluginType type;
        private final Optional<PluginId> parent;
        private final List<String> dependencies;
        private final PluginTrustLevel trust;

        Loaded(@NotNull final PluginId id,
               @NotNull final String name,
               @NotNull final String version,
               @NotNull final String publisher,
               @NotNull final PluginType type,
               @NotNull final Optional<PluginId> parent,
               @NotNull final List<String> dependencies,
               @NotNull final PluginTrustLevel trust) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.publisher = publisher;
            this.type = type;
            this.parent = parent;
            this.dependencies = dependencies;
            this.trust = trust;
        }

        @NotNull public PluginId id() { return id; }
        @NotNull public String name() { return name; }
        @NotNull public String version() { return version; }
        @NotNull public String publisher() { return publisher; }
        @NotNull public PluginType type() { return type; }
        @NotNull public Optional<PluginId> parentId() { return parent; }
        @NotNull public List<String> dependencies() { return dependencies; }
        @NotNull public PluginTrustLevel trust() { return trust; }

        public boolean hasParent() { return parent.isPresent(); }
    }

    // ----------------------------------------------------------------------
    // Minimal JSON parser (LMili metadata only).
    // ----------------------------------------------------------------------

    static final class MiniJson {

        static Obj parseObject(@NotNull final String src) {
            final Parser p = new Parser(src);
            p.skipWhitespace();
            if (p.eof() || p.peek() != '{') return null;
            final Obj obj = p.readObject();
            p.skipWhitespace();
            return p.eof() ? obj : null;
        }

        static final class Obj {
            private final java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();

            @Nullable String string(@NotNull final String key) {
                final Object v = map.get(key);
                return v instanceof String s ? s : null;
            }

            @Nullable java.util.List<String> stringList(@NotNull final String key) {
                final Object v = map.get(key);
                if (!(v instanceof java.util.List<?> list)) return null;
                final java.util.List<String> out = new java.util.ArrayList<>(list.size());
                for (final Object e : list) {
                    if (e instanceof String s) out.add(s);
                }
                return out;
            }
        }

        private static final class Parser {
            private final String src;
            private int pos;

            Parser(@NotNull final String src) { this.src = src; }

            boolean eof() { return pos >= src.length(); }
            char peek() { return src.charAt(pos); }

            void skipWhitespace() {
                while (!eof() && Character.isWhitespace(src.charAt(pos))) pos++;
            }

            Obj readObject() {
                final Obj obj = new Obj();
                pos++; // '{'
                skipWhitespace();
                if (!eof() && peek() == '}') { pos++; return obj; }
                while (!eof()) {
                    skipWhitespace();
                    final String key = readString();
                    if (key == null) return null;
                    skipWhitespace();
                    if (eof() || peek() != ':') return null;
                    pos++;
                    skipWhitespace();
                    final Object value = readValue();
                    if (value == null) return null;
                    obj.map.put(key, value);
                    skipWhitespace();
                    if (eof()) return null;
                    final char c = peek();
                    if (c == ',') { pos++; continue; }
                    if (c == '}') { pos++; return obj; }
                    return null;
                }
                return null;
            }

            private Object readValue() {
                if (eof()) return null;
                final char c = peek();
                if (c == '"') return readString();
                if (c == '{') return readObject();
                if (c == '[') return readArray();
                if (c == 't' || c == 'f') return readBool();
                if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
                return null;
            }

            private java.util.List<Object> readArray() {
                final java.util.List<Object> list = new java.util.ArrayList<>();
                pos++; // '['
                skipWhitespace();
                if (!eof() && peek() == ']') { pos++; return list; }
                while (!eof()) {
                    skipWhitespace();
                    final Object v = readValue();
                    if (v == null) return null;
                    list.add(v);
                    skipWhitespace();
                    if (eof()) return null;
                    final char c = peek();
                    if (c == ',') { pos++; continue; }
                    if (c == ']') { pos++; return list; }
                    return null;
                }
                return null;
            }

            @Nullable private String readString() {
                if (eof() || peek() != '"') return null;
                pos++;
                final StringBuilder sb = new StringBuilder();
                while (!eof()) {
                    final char c = src.charAt(pos++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        if (eof()) return null;
                        final char esc = src.charAt(pos++);
                        switch (esc) {
                            case '"'  -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/'  -> sb.append('/');
                            case 'b'  -> sb.append('\b');
                            case 'f'  -> sb.append('\f');
                            case 'n'  -> sb.append('\n');
                            case 'r'  -> sb.append('\r');
                            case 't'  -> sb.append('\t');
                            case 'u'  -> {
                                if (pos + 4 > src.length()) return null;
                                final String hex = src.substring(pos, pos + 4);
                                pos += 4;
                                try { sb.append((char) Integer.parseInt(hex, 16)); }
                                catch (final NumberFormatException e) { return null; }
                            }
                            default -> { return null; }
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return null;
            }

            private Boolean readBool() {
                if (src.startsWith("true", pos))  { pos += 4; return Boolean.TRUE; }
                if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
                return null;
            }

            private Object readNumber() {
                final int start = pos;
                if (!eof() && peek() == '-') pos++;
                while (!eof() && (Character.isDigit(src.charAt(pos))
                        || src.charAt(pos) == '.'
                        || src.charAt(pos) == 'e'
                        || src.charAt(pos) == 'E'
                        || src.charAt(pos) == '+'
                        || src.charAt(pos) == '-')) {
                    pos++;
                }
                final String num = src.substring(start, pos);
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    try { return Double.parseDouble(num); } catch (final Exception e) { return null; }
                }
                try { return Long.parseLong(num); } catch (final Exception e) { return null; }
            }
        }
    }
}