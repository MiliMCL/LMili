package fun.bm.mili.lmili.thread.regiontick.api;

import fun.bm.mili.lmili.thread.regiontick.suspend.VirtualThreadScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Callable;

public interface MiliScheduler {

    static @NotNull MiliScheduler getInstance() { return VirtualThreadScheduler.getInstance(); }
    static @NotNull MiliScheduler get() { return getInstance(); }

    @NotNull EntityScheduler forEntity(@NotNull Entity entity);

    void runAt(@NotNull ServerLevel level, @NotNull Vec3 position, @NotNull ThrowingConsumer<EntityTaskContext> task);
    void runAsync(@NotNull Runnable task);

    <T> T computeBlocking(@NotNull Callable<T> computation) throws Exception;

    interface EntityScheduler {
        void run(@NotNull ThrowingConsumer<EntityTaskContext> task);
        void runDelayed(@NotNull ThrowingConsumer<EntityTaskContext> task, long delayTicks);
        boolean isOnOwningThread();
        void ensureOnOwningThread() throws EntityOrphanedException;
    }

    @FunctionalInterface
    interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }
}
