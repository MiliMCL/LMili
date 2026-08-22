package fun.bm.mili.lmili.api.identity;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Set;

/**
 * Per-plugin coarse-grained permission flag. The V2 spec keeps this layer
 * simple (see §21 &quot;本阶段不要求完成完整 Permission System&quot;); it reserves
 * a {@code Set<String>} for future per-action permissions.
 */
public final class PermissionContext {

    private final PermissionLevel level;
    private final Set<String> granted;

    public PermissionContext(@NotNull final PermissionLevel level) {
        this(level, Collections.emptySet());
    }

    public PermissionContext(@NotNull final PermissionLevel level,
                             @NotNull final Set<String> granted) {
        this.level = level;
        this.granted = Set.copyOf(granted);
    }

    /**
     * @return the level appropriate for the supplied status.
     */
    @NotNull
    public static PermissionContext forStatus(@NotNull final PluginStatus status) {
        return new PermissionContext(defaultLevel(status));
    }

    @NotNull
    public PermissionLevel level() { return level; }

    @NotNull
    public Set<String> granted() { return granted; }

    /** @return true if {@code required} is covered by the current level. */
    public boolean covers(@NotNull final PermissionLevel required) {
        return level.covers(required);
    }

    @NotNull
    public static PermissionLevel defaultLevel(@NotNull final PluginStatus status) {
        return switch (status) {
            case ACTIVE   -> PermissionLevel.SCHEDULER;
            case OBSERVE  -> PermissionLevel.READ_ONLY;
            case CONFLICT, DISABLED, FAILED -> PermissionLevel.NONE;
            default       -> PermissionLevel.READ_ONLY; // discovered/loading/unloaded
        };
    }

    @Override
    public String toString() {
        return "PermissionContext{level=" + level + ", granted=" + granted + "}";
    }
}