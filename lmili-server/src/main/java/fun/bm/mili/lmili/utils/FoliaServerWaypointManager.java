package fun.bm.mili.lmili.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.waypoints.ServerWaypointManager;

/**
 * Folia 路点管理器 —— 兼容性保留类。
 *
 * <p>此类为兼容性保留，继承自 {@link ServerWaypointManager}。
 *
 * @since 2.0.0
 */
public final class FoliaServerWaypointManager extends ServerWaypointManager {

    public FoliaServerWaypointManager(ServerLevel level) {
        super(level);
    }
}
