package fun.bm.mili.lmili.thread.regiontick.executor;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.RegionTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.RegionTickSlice;
import fun.bm.mili.lmili.thread.regiontick.RegionTickWorker;
import fun.bm.mili.lmili.thread.regiontick.dag.*;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;

public final class DagBasedTickExecutor implements RegionTickExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Mili start - fix: Shared long-lived ForkJoinPool to avoid creating/destroying
    // a new pool on every tick (which wastes threads and causes thread churn).
    private static final ForkJoinPool SHARED_DAG_POOL = new ForkJoinPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            (t, e) -> LOGGER.error("[DagBasedTickExecutor] Uncaught exception in worker", e),
            true // asyncMode for better throughput with independent tasks
    );
    // Mili end

    private final FoliaTickExecutor foliaExecutor = new FoliaTickExecutor();
    private final Map<String, BiConsumer<SystemProfile, Scope>> systemExecutors = new ConcurrentHashMap<>();
    private volatile RegionDag cachedDag;
    private volatile long dagBuildNanos;

    public void registerSystem(@NotNull final String name,
                                @NotNull final SystemProfile profile,
                                @NotNull final Scope scope,
                                @NotNull final BiConsumer<SystemProfile, Scope> executor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(executor, "executor");

        systemExecutors.put(name, executor);
        this.cachedDag = null;
        this.cachedVersion = -1;
        LOGGER.debug("[DagBasedTickExecutor] Registered system '{}' (resource reads={}, writes={})",
                name, profile.readBits().length, profile.writeBits().length);
    }

    public void unregisterSystem(@NotNull final String name) {
        if (systemExecutors.remove(name) != null) {
            this.cachedDag = null;
            this.cachedVersion = -1;
        }
    }

    @Override
    public void executeSlice(@NotNull final RegionTickWorker worker,
                              @NotNull final RegionTickSlice slice,
                              @NotNull final RegionTickContext context) {
        foliaExecutor.executeSlice(worker, slice, context);
    }

    /**
     * 使用给定的 level-aware executors 执行系统 DAG。
     *
     * <p>与 {@link #executeSystems(long, RegionTickContext, List)} 不同，此方法接受一个
     * {@code Map<systemName, executor>} 用于本次 tick 的实际执行逻辑。
     * 这允许调用者在每次 tick 传入基于当前 level 状态的闭包。
     *
     * @param regionId       区域 ID
     * @param context        tick 上下文
     * @param systemScopePairs 系统-Scope 对列表
     * @param level          当前 ServerLevel
     * @param executors      系统名称到执行器的映射（接收 Scope + ServerLevel）
     */
    public void executeSystems(final long regionId,
                                @NotNull final RegionTickContext context,
                                @NotNull final List<Map.Entry<SystemProfile, Scope>> systemScopePairs,
                                @NotNull final ServerLevel level,
                                @NotNull final Map<String, BiConsumer<Scope, ServerLevel>> executors) {
        if (systemScopePairs.isEmpty()) return;

        RegionDag dag = getOrBuildDag(regionId, systemScopePairs);
        if (dag == null) return;

        // Mili start - fix: Use shared pool instead of creating a new one per tick
        int threadCount = Math.min(systemScopePairs.size(), Runtime.getRuntime().availableProcessors());
        RegionDagExecutor executor = new RegionDagExecutor(threadCount, SHARED_DAG_POOL);
        for (Map.Entry<SystemProfile, Scope> entry : systemScopePairs) {
            final SystemProfile profile = entry.getKey();
            final Scope scope = entry.getValue();
            final BiConsumer<Scope, ServerLevel> sysExec = executors.get(profile.name());
            executor.registerSystemExecutor(profile.name(), (node, ctx) -> {
                if (sysExec != null) sysExec.accept(scope, level);
            });
        }

        try {
            executor.executeDag(dag, context);
        } catch (Throwable throwable) {
            LOGGER.error("[DagBasedTickExecutor] DAG execution failed for region #{}", regionId, throwable);
        } finally {
            executor.close();
        }
        // Mili end
    }

    public void executeSystems(final long regionId,
                                @NotNull final RegionTickContext context,
                                @NotNull final List<Map.Entry<SystemProfile, Scope>> systemScopePairs) {
        if (systemScopePairs.isEmpty()) return;

        RegionDag dag = getOrBuildDag(regionId, systemScopePairs);
        if (dag == null) return;

        // Mili start - fix: Use shared pool instead of creating a new one per tick
        int threadCount = Math.min(systemScopePairs.size(), Runtime.getRuntime().availableProcessors());
        RegionDagExecutor executor = new RegionDagExecutor(threadCount, SHARED_DAG_POOL);
        for (Map.Entry<SystemProfile, Scope> entry : systemScopePairs) {
            final SystemProfile profile = entry.getKey();
            final Scope scope = entry.getValue();
            executor.registerSystemExecutor(profile.name(), (node, ctx) -> {
                BiConsumer<SystemProfile, Scope> sysExec = systemExecutors.get(node.profile().name());
                if (sysExec != null) sysExec.accept(profile, scope);
            });
        }

        try {
            executor.executeDag(dag, context);
        } catch (Throwable throwable) {
            LOGGER.error("[DagBasedTickExecutor] DAG execution failed for region #{}", regionId, throwable);
        } finally {
            executor.close();
        }
        // Mili end
    }

    // Mili start - fix: Use content-based cache key instead of reference equality (==)
    // The old implementation used == to compare pairs list, which means a new ArrayList
    // with the same content would invalidate the cache every tick.
    private volatile long cachedVersion = -1;
    // Mili end

    private RegionDag getOrBuildDag(final long regionId, final List<Map.Entry<SystemProfile, Scope>> pairs) {
        // Mili start - fix: content-based cache - compute a simple hash of the pairs
        long version = computeVersion(pairs);
        if (this.cachedDag != null && this.cachedVersion == version) return this.cachedDag;
        // Mili end

        long start = System.nanoTime();
        try {
            this.cachedDag = RegionDag.build(regionId, pairs);
            this.cachedVersion = version;
            this.dagBuildNanos = System.nanoTime() - start;
            return this.cachedDag;
        } catch (Throwable throwable) {
            LOGGER.error("[DagBasedTickExecutor] DAG build failed for region #{}", regionId, throwable);
            return null;
        }
    }

    // Mili start - Compute a content-based version hash for the system-scope pairs list.
    // This avoids the brittle == reference comparison that would invalidate cache on every tick.
    private static long computeVersion(List<Map.Entry<SystemProfile, Scope>> pairs) {
        long hash = 0;
        for (Map.Entry<SystemProfile, Scope> entry : pairs) {
            hash = hash * 31 + (entry.getKey().name().hashCode() ^ System.identityHashCode(entry.getValue()));
        }
        return hash;
    }
    // Mili end

    public int getSystemCount() { return systemExecutors.size(); }
    public long getDagBuildNanos() { return this.dagBuildNanos; }
    public RegionDag getCachedDag() { return this.cachedDag; }
}
