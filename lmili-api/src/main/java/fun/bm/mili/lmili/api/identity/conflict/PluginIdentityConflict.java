package fun.bm.mili.lmili.api.identity.conflict;

import fun.bm.mili.lmili.api.identity.PluginId;
import fun.bm.mili.lmili.api.identity.PluginIdentity;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of a detected identity conflict.
 *
 * <p>Created by {@code PluginIdentityManager.register(...)} when an incoming
 * registration cannot be accepted. Stored by the manager for diagnostics and
 * surfaced through {@code /plugins conflicts}.</p>
 *
 * <p>The {@link #existing()} identity is the one currently registered under
 * {@link #id()}; the {@link #incoming()} identity is the one that tried to
 * take the same slot. Neither is mutated by this record.</p>
 */
public final class PluginIdentityConflict {

    private final PluginId id;
    private final PluginIdentity existing;
    private final PluginIdentity incoming;
    private final ConflictReason reason;
    private final Instant detectedAt;

    public PluginIdentityConflict(@NotNull final PluginId id,
                                  @NotNull final PluginIdentity existing,
                                  @NotNull final PluginIdentity incoming,
                                  @NotNull final ConflictReason reason) {
        this.id = Objects.requireNonNull(id, "id");
        this.existing = Objects.requireNonNull(existing, "existing");
        this.incoming = Objects.requireNonNull(incoming, "incoming");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.detectedAt = Instant.now();
    }

    @NotNull public PluginId id() { return id; }
    @NotNull public PluginIdentity existing() { return existing; }
    @NotNull public PluginIdentity incoming() { return incoming; }
    @NotNull public ConflictReason reason() { return reason; }
    @NotNull public Instant detectedAt() { return detectedAt; }

    @Override
    public String toString() {
        return "PluginIdentityConflict{id=" + id.value()
                + ", reason=" + reason
                + ", existing=" + existing.name() + " " + existing.version()
                + ", incoming=" + incoming.name() + " " + incoming.version()
                + ", detectedAt=" + detectedAt + "}";
    }
}