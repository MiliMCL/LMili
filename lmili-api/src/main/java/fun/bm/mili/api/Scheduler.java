package fun.bm.mili.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * Mili scheduler - suspend-style API for plugin developers.
 *
 * <p>Compare with Folia dual-callback pattern:</p>
 *
 * <p>Old Folia: dual callbacks, easy to forget the retired branch.
 * Mili: suspend-style, virtual thread backed, looks sync but non-blocking.</p>
 *
 * <p>Get the instance via {@link Mili}.</p>
 */
public interface Scheduler {

    /**
     * Get the scheduler bound to a specific entity.
     *
     * <p>Returned scheduler will bind tasks to this entity, executing them
     * in the entity current region. If the entity has died or been removed,
     * subsequent run calls will throw EntityOrphanedException.</p>
     *
     * @param entity the entity to bind to
     * @return the entity scheduler
     * @throws IllegalArgumentException if entity is null
     */
    @NotNull EntityScheduler forEntity(@NotNull Entity entity);

    /**
     * Run a task at the given location.
     *
     * <p>If the location is loaded and the region is being ticked,
     * the task will execute in the current or next tick.
     * Tasks run on virtual threads and can use EntityTaskContext suspend features.</p>
     *
     * @param location the location where the task should run (must be loaded)
     * @param task the task to execute
     */
    void runAt(@NotNull Location location, @NotNull java.util.function.Consumer<EntityTaskContext> task);

    /**
     * Run a task asynchronously, unrelated to any entity.
     *
     * <p>Tasks run on virtual threads, suitable for IO-heavy or blocking operations
     * (database queries, HTTP requests, etc). These will not block the main tick thread
     * because virtual threads yield the carrier automatically on blocking.</p>
     *
     * @param task the task to run
     */
    void runAsync(@NotNull Runnable task);

    /**
     * @return Mili scheduler version identifier
     */
    @NotNull String version();
}
