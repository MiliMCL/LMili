package fun.bm.mili.lmili.thread.scheduler.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 实体孤儿异常 —— 当任务尝试访问已移除或转移的实体时抛出。
 *
 * <p>这是调度器中的正常流程（entity 可能在 tick 之间被移除），
 * 调用代码应优雅处理此异常，通常只需跳过任务执行。
 */
public final class EntityOrphanedException extends Exception {

    private final int entityId;
    @Nullable
    private final String entityName;
    @NotNull
    private final Reason reason;

    public EntityOrphanedException(int entityId, @Nullable String entityName, @NotNull Reason reason) {
        super("Entity #" + entityId + (entityName != null ? " (" + entityName + ")" : "") +
                " is orphaned: " + reason);
        this.entityId = entityId;
        this.entityName = entityName;
        this.reason = reason;
    }

    public int entityId() { return entityId; }

    @Nullable
    public String entityName() { return entityName; }

    @NotNull
    public Reason reason() { return reason; }

    /**
     * 实体变成孤儿的原因。
     */
    public enum Reason {
        /** 实体已被移除（击杀、自然消失等）。 */
        REMOVED,
        /** 实体传送到了其他 region。 */
        TELEPORTED_REGION,
        /** 实体所在的世界被卸载。 */
        WORLD_UNLOADED
    }
}
