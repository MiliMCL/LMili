package fun.bm.mili.api;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Per-entity scheduler - tasks bind to the entity and execute in its home region.
 *
 * <p>Execution model:</p>
 * <ul>
 *   <li>Each run executes on a virtual thread, looks like synchronous code</li>
 *   <li>If entity is gone (dead, removed, teleported away), Consumer receives EntityOrphanedException</li>
 *   <li>Supports EntityTaskContext.awaitCrossRegion for cross-region suspend (yields the carrier, non-blocking)</li>
 * </ul>
 */
public interface EntityScheduler {

    /**
     * Execute a tick task bound to this entity.
     *
     * <p>If the entity is still alive, the task will be executed in the entity region tick
     * (assigned to a virtual thread). If the entity has disappeared, the task is skipped
     * and EntityOrphanedException is thrown.</p>
     *
     * <p>Exception handling:</p>
     * <ul>
     *   <li>EntityOrphanedException - entity is gone, checked exception, must handle</li>
     *   <li>Other RuntimeException - task execution error, logged</li>
     * </ul>
     *
     * @param task the task to execute, receives EntityTaskContext
     */
    void run(@NotNull Consumer<EntityTaskContext> task);

    /**
     * Execute a task after a specified number of ticks.
     *
     * <p>Delay is based on server ticks (20 TPS = 50ms/tick), but affected by region tick frequency.
     * If the entity disappears during the delay, EntityOrphanedException is thrown at execution time.</p>
     *
     * @param task the task to execute
     * @param delayTicks number of ticks to delay (must be >= 0)
     */
    void runDelayed(@NotNull Consumer<EntityTaskContext> task, long delayTicks);
}
