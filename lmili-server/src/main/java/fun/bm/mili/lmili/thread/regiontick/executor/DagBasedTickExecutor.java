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

/**
 * DAG-based tick executor —— 基于冲突检测的并行 tick 调度。
 *
 * <p>核心设计：
 * <ul>
 *   <li>系统被定义为 {@link SystemProfile}，声明其读写的资源类型。</li>
 *   <li>每 tick 根据系统间的资源冲突关系构建 DAG（有向无环图），确定执行顺序。</li>
 *   <li>无冲突的系统可以并行执行（通过 ForkJoinPool）。</li>
 *   <li>DAG 结构在系统列表不变时可缓存复用。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>本类方法设计为只在 Folia region tick 线程上调用。系统注册/注销通过 {@link #registerSystem} /
 * {@link #unregisterSystem} 操作，需在 server 启动阶段完成（非热路径）。
 */
public final class DagBasedTickExecutor implements RegionTickExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    // 共享的 ForkJoinPool —— 长生命周期，覆盖整个 server 运行周期。
    private static final ForkJoinPool SHARED_DAG_POOL = new ForkJoinPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            (t, e) -> LOGGER.error("[DagBasedTickExecutor] Uncaught exception in worker", e),
            true // asyncMode for better throughput with independent tasks
    );

    private final FoliaTickExecutor foliaExecutor = new FoliaTickExecutor();

    // 系统执行器注册表 —— 启动后基本不变，tick 期间只读
    private final Map<String, RegisteredSystem> registeredSystems = new LinkedHashMap<>();

    // DAG 缓存
    private volatile RegionDag cachedDag;
    private volatile DagCacheKey cacheKey;
    private volatile long dagBuildNanos;

    /**
     * 注册的系统信息。
     */
    private record RegisteredSystem(String name, SystemProfile profile, Scope scope,
                                     BiConsumer<SystemProfile, Scope> executor) {}

    /**
     * DAG 缓存键 —— 基于系统名称排序后的列表。如果系统集合不变，DAG 结构不变。
     */
    private record DagCacheKey(String[] systemNames) {
        static DagCacheKey from(Map<String, RegisteredSystem> systems) {
            String[] names = systems.keySet().toArray(new String[0]);
            Arrays.sort(names);
            return new DagCacheKey(names);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DagCacheKey that)) return false;
            return Arrays.equals(systemNames, that.systemNames);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(systemNames);
        }
    }

    public void registerSystem(@NotNull final String name,
                                @NotNull final SystemProfile profile,
                                @NotNull final Scope scope,
                                @NotNull final BiConsumer<SystemProfile, Scope> executor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(executor, "executor");

        registeredSystems.put(name, new RegisteredSystem(name, profile, scope, executor));
        // 清空缓存 —— 系统集合变化后 DAG 需重建
        this.cachedDag = null;
        this.cacheKey = null;
        LOGGER.info("[DagBasedTickExecutor] Registered system '{}' (systems={})", name, registeredSystems.size());
    }

    public void unregisterSystem(@NotNull final String name) {
        if (registeredSystems.remove(name) != null) {
            this.cachedDag = null;
            this.cacheKey = null;
            LOGGER.info("[DagBasedTickExecutor] Unregistered system '{}' (systems={})", name, registeredSystems.size());
        }
    }

    @Override
    public void executeSlice(@NotNull final RegionTickWorker worker,
                              @NotNull final RegionTickSlice slice,
                              @NotNull final RegionTickContext context) {
        // 当注册的系统 > 0 时走 DAG 路径，否则回退到 FoliaTickExecutor
        if (registeredSystems.isEmpty()) {
            foliaExecutor.executeSlice(worker, slice, context);
            return;
        }
        executeSystemsInternal(context.regionId, context, null, null);
    }

    /**
     * 执行系统 DAG（无 level-aware executors 的简单路径）。
     * 只在 region tick 线程上调用。
     */
    public void executeSystems(final long regionId,
                                @NotNull final RegionTickContext context,
                                @NotNull final List<Map.Entry<SystemProfile, Scope>> systemScopePairs) {
        executeSystemsInternal(regionId, context, null, null);
    }

    /**
     * 执行系统 DAG（level-aware executors 路径）。
     * 只在 region tick 线程上调用。
     */
    public void executeSystems(final long regionId,
                                @NotNull final RegionTickContext context,
                                @NotNull final List<Map.Entry<SystemProfile, Scope>> systemScopePairs,
                                @NotNull final ServerLevel level,
                                @NotNull final Map<String, BiConsumer<Scope, ServerLevel>> executors) {
        executeSystemsInternal(regionId, context, level, executors);
    }

    /**
     * DAG 执行主逻辑。
     *
     * <p>核心改进：
     * <ol>
     *   <li>使用构建时一次性注册所有执行器，tick 期间不变。</li>
     *   <li>DAG 结构基于系统名称集合缓存，只在系统注册/注销时重建。</li>
     *   <li>每个 RegionDagExecutor 实例为局部变量，不跨 tick 共用。</li>
     * </ol>
     */
    private void executeSystemsInternal(final long regionId,
                                         @NotNull final RegionTickContext context,
                                         @Nullable final ServerLevel level,
                                         @Nullable final Map<String, BiConsumer<Scope, ServerLevel>> levelExecutors) {
        // 构建当前 tick 的系统-scope 列表
        List<Map.Entry<SystemProfile, Scope>> pairs = new ArrayList<>(registeredSystems.size());
        for (RegisteredSystem rs : registeredSystems.values()) {
            pairs.add(Map.entry(rs.profile, rs.scope));
        }
        if (pairs.isEmpty()) return;

        // 构建或获取缓存的 DAG
        RegionDag dag = getOrBuildDag(regionId, pairs);
        if (dag == null) return;

        // 为本次 tick 构建节点级别的执行器映射
        Map<String, BiConsumer<DagNode, RegionTickContext>> tickExecutors = new HashMap<>(pairs.size());
        for (RegisteredSystem rs : registeredSystems.values()) {
            if (level != null && levelExecutors != null) {
                BiConsumer<Scope, ServerLevel> sysExec = levelExecutors.get(rs.name);
                if (sysExec != null) {
                    Scope scope = rs.scope;
                    tickExecutors.put(rs.name, (node, ctx) -> sysExec.accept(scope, level));
                    continue;
                }
            }
            // 回退到注册时的 executor
            tickExecutors.put(rs.name, (node, ctx) -> rs.executor.accept(node.profile(), node.scope()));
        }

        // 创建本次 tick 专用的 executor 并执行
        RegionDagExecutor tickExecutor = new RegionDagExecutor(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2), SHARED_DAG_POOL);
        tickExecutor.registerAll(tickExecutors);

        try {
            tickExecutor.executeDag(dag, context);
        } catch (Throwable throwable) {
            LOGGER.error("[DagBasedTickExecutor] DAG execution failed for region #{}", regionId, throwable);
        }
    }

    private RegionDag getOrBuildDag(final long regionId, List<Map.Entry<SystemProfile, Scope>> pairs) {
        DagCacheKey newKey = DagCacheKey.from(registeredSystems);
        if (this.cachedDag != null && newKey.equals(this.cacheKey)) return this.cachedDag;

        long start = System.nanoTime();
        try {
            this.cachedDag = RegionDag.build(regionId, pairs);
            this.cacheKey = newKey;
            this.dagBuildNanos = System.nanoTime() - start;
            return this.cachedDag;
        } catch (Throwable throwable) {
            LOGGER.error("[DagBasedTickExecutor] DAG build failed for region #{}", regionId, throwable);
            return null;
        }
    }

    @SuppressWarnings("unused")
    private RegionDag getOrBuildDagFromPairs(final long regionId, final List<Map.Entry<SystemProfile, Scope>> pairs) {
        // 保留用于可能需要的外部调用
        return getOrBuildDag(regionId, pairs);
    }

    public int getSystemCount() { return registeredSystems.size(); }
    public long getDagBuildNanos() { return this.dagBuildNanos; }
    public RegionDag getCachedDag() { return this.cachedDag; }
}
