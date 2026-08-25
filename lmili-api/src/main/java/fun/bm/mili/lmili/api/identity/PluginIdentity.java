package fun.bm.mili.lmili.api.identity;

import fun.bm.mili.lmili.api.identity.exception.InvalidAddonParentException;
import fun.bm.mili.lmili.api.identity.exception.InvalidPluginMetadataException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable record of a plugin's identity. Created by the loader from
 * {@code lmili.json} (preferred) or by the legacy fallback from
 * {@code plugin.yml}. Once created, the following fields are frozen:
 *
 * <ul>
 *   <li>{@link #id()}</li>
 *   <li>{@link #publisher()}</li>
 *   <li>{@link #type()}</li>
 *   <li>{@link #parentId()}</li>
 * </ul>
 *
 * <p>{@link #status()} and {@link #trustLevel()} are runtime-managed; the
 * plugin never modifies them directly — see {@link #withStatus(PluginStatus)}
 * and {@link #withTrustLevel(PluginTrustLevel)} which produce new instances.</p>
 */
public final class PluginIdentity {

    private final PluginId id;
    private final String name;
    private final String version;
    private final String publisher;
    private final PluginType type;
    private final Optional<PluginId> parent;
    private final PluginTrustLevel trustLevel;
    private final PluginStatus status;
    private final Instant registeredAt;
    private final String source;          // "lmili.json" | "plugin.yml"
    private final @Nullable String bukkitPluginName;
    /** §C LMili Required 调度委托策略（默认 LMILI_REQUIRED） */
    private final SchedulerDelegation delegation;

    private PluginIdentity(@NotNull final PluginId id,
                           @NotNull final String name,
                           @NotNull final String version,
                           @NotNull final String publisher,
                           @NotNull final PluginType type,
                           @NotNull final Optional<PluginId> parent,
                           @NotNull final PluginTrustLevel trustLevel,
                           @NotNull final PluginStatus status,
                           @NotNull final Instant registeredAt,
                           @NotNull final String source,
                           @Nullable final String bukkitPluginName,
                           @NotNull final SchedulerDelegation delegation) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.publisher = publisher;
        this.type = type;
        this.parent = parent;
        this.trustLevel = trustLevel;
        this.delegation = delegation;
        this.status = status;
        this.registeredAt = registeredAt;
        this.source = source;
        this.bukkitPluginName = bukkitPluginName;
    }

    /**
     * Build an identity from validated fields.
     *
     * @param id              canonical plugin id
     * @param name            human-readable name
     * @param version         version string (recommended SemVer)
     * @param publisher       publisher segment
     * @param type            plugin / addon / legacy
     * @param parent          addon parent (must equal id.parentId() when type==ADDON,
     *                        must be empty otherwise)
     * @param source          metadata source marker ({@code "lmili.json"} / {@code "plugin.yml"})
     * @param bukkitPluginName the original Bukkit plugin name, if any
     * @throws InvalidAddonParentException if the addon parent does not match id.parentId()
     */
    @NotNull
    public static PluginIdentity of(@NotNull final PluginId id,
                                    @NotNull final String name,
                                    @NotNull final String version,
                                    @NotNull final String publisher,
                                    @NotNull final PluginType type,
                                    @NotNull final Optional<PluginId> parent,
                                    @NotNull final String source,
                                    @Nullable final String bukkitPluginName) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(source, "source");

        // Validate addon / parent consistency.
        if (type == PluginType.ADDON) {
            final Optional<PluginId> expectedParent = id.parentId();
            if (parent.isEmpty()) {
                throw new InvalidAddonParentException(id,
                        expectedParent.orElseThrow(() ->
                                new InvalidPluginMetadataException(source,
                                        "addon '" + id.value() + "' is a top-level plugin, not an addon")),
                        "addon must declare a parent");
            }
            if (!expectedParent.isPresent()) {
                throw new InvalidAddonParentException(id, parent.get(),
                        "id has no parent segment");
            }
            if (!expectedParent.get().equals(parent.get())) {
                throw new InvalidAddonParentException(id, parent.get(),
                        "expected '" + expectedParent.get().value()
                                + "', got '" + parent.get().value() + "'");
            }
        } else {
            if (parent.isPresent()) {
                throw new InvalidPluginMetadataException(source,
                        "non-addon plugin '" + id.value() + "' must not declare a parent");
            }
        }

        return new PluginIdentity(id, name, version, publisher, type, parent,
                PluginTrustLevel.UNKNOWN, PluginStatus.DISCOVERED,
                Instant.now(), source, bukkitPluginName, SchedulerDelegation.LMILI_REQUIRED);
    }

    /** 重载：含 delegation（lmili.json 解析用） */
    @NotNull
    public static PluginIdentity of(@NotNull final PluginId id,
                                    @NotNull final String name,
                                    @NotNull final String version,
                                    @NotNull final String publisher,
                                    @NotNull final PluginType type,
                                    @NotNull final Optional<PluginId> parent,
                                    @NotNull final String source,
                                    @Nullable final String bukkitPluginName,
                                    @NotNull final SchedulerDelegation delegation) {
        Objects.requireNonNull(delegation, "delegation");
        // Validate addon / parent consistency (同 of() 上面）
        if (type == PluginType.ADDON) {
            final Optional<PluginId> expectedParent = id.parentId();
            if (parent.isEmpty()) {
                throw new InvalidAddonParentException(id,
                        expectedParent.orElseThrow(() ->
                                new InvalidPluginMetadataException(source,
                                        "addon '" + id.value() + "' is a top-level plugin, not an addon")),
                        "addon must declare a parent");
            }
            if (!expectedParent.isPresent()) {
                throw new InvalidAddonParentException(id, parent.get(),
                        "id has no parent segment");
            }
            if (!expectedParent.get().equals(parent.get())) {
                throw new InvalidAddonParentException(id, parent.get(),
                        "expected '" + expectedParent.get().value()
                                + "', got '" + parent.get().value() + "'");
            }
        } else if (parent.isPresent()) {
            throw new InvalidPluginMetadataException(source,
                    "non-addon plugin '" + id.value() + "' must not declare a parent");
        }
        return new PluginIdentity(id, name, version, publisher, type, parent,
                PluginTrustLevel.UNKNOWN, PluginStatus.DISCOVERED,
                Instant.now(), source, bukkitPluginName, delegation);
    }

    // ---- accessors (no setters) ------------------------------------------

    @NotNull public PluginId id() { return id; }
    @NotNull public String name() { return name; }
    @NotNull public String version() { return version; }
    @NotNull public String publisher() { return publisher; }
    @NotNull public PluginType type() { return type; }
    @NotNull public Optional<PluginId> parentId() { return parent; }
    @NotNull public PluginTrustLevel trustLevel() { return trustLevel; }
    @NotNull public PluginStatus status() { return status; }
    @NotNull public Instant registeredAt() { return registeredAt; }
    @NotNull public String source() { return source; }
    @Nullable public String bukkitPluginName() { return bukkitPluginName; }
    /** §C LMili Required 调度委托策略 */
    @NotNull public SchedulerDelegation delegation() { return delegation; }

    // ---- immutable transitions ------------------------------------------

    @NotNull public PluginIdentity withStatus(@NotNull final PluginStatus next) {
        return new PluginIdentity(id, name, version, publisher, type, parent,
                trustLevel, Objects.requireNonNull(next, "next"),
                registeredAt, source, bukkitPluginName, delegation);
    }

    @NotNull public PluginIdentity withTrustLevel(@NotNull final PluginTrustLevel next) {
        return new PluginIdentity(id, name, version, publisher, type, parent,
                Objects.requireNonNull(next, "next"), status,
                registeredAt, source, bukkitPluginName, delegation);
    }

    // ---- equality / representation --------------------------------------

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginIdentity other)) return false;
        return id.equals(other.id) && version.equals(other.version);
    }

    @Override
    public int hashCode() { return Objects.hash(id, version); }

    @Override
    public String toString() {
        return "PluginIdentity{id=" + id.value()
                + ", name=" + name
                + ", version=" + version
                + ", publisher=" + publisher
                + ", type=" + type
                + ", parent=" + parent.map(PluginId::value).orElse("-")
                + ", trust=" + trustLevel
                + ", status=" + status
                + ", source=" + source + "}";
    }
}