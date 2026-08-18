package fun.bm.mili.lmili.thread.regiontick.executor;

import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.RegionTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.RegionTickSlice;
import fun.bm.mili.lmili.thread.regiontick.RegionTickWorker;
import fun.bm.mili.lmili.thread.regiontick.dag.CompiledDag;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemGraph;
import fun.bm.mili.lmili.thread.scheduler.tick.TickContext;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * 现代 DAG Tick 执行器 — 基于 {@link SystemGraph} + {@link CompiledDag} + {@link DagExecutionEngine} 的新实现。
 *
 * <p>相比 {@link DagBasedTickExecutor} 的优势：
 * <ul>
 *   <li><b>非阻塞执行</b>：使用回调驱动，region tick 线程永不阻塞</li>
 *   <li><b>不可变 DAG</b>：编译后的 DAG 可安全缓存，无并发问题</li>
 *   <li><b>稀疏冲突图</b>：内存效率大幅提升</li>
 *   <li><b>可配置执行器</b>: 支持注入自定义 Executor（如 Virtual Thread 执行器）</li>
 * </ul>
 *
 * <h3>兼容性：</h3>
 * <p>此类实现了 {@link RegionTickExecutor} 接口，可直接替换 {@link DagBasedTickExecutor}。
 * 同时提供了与旧 API 兼容的 {@link #registerSystem} 方法。
 *
 * <h3>使用方式：</h3>
 * <pre>{@code
 * // 创建执行器（通常在 server 启动时）
 * ModernDagTickExecutor executor = new ModernDagTickExecutor(virtualThreadExecutor);
 *
 * // 注册系统
 * executor.registerSystem("entity_tick", profile, scope, (prof, scp) -> {
 *     // tick logic
 * });
 *
 * // 声明依赖
 * executor.addDependency("entity_tick", "ai_tick");
 *
 * // 每 tick 调用
 * executor.executeSlice(worker, slice, context);
 * }</pre>
 */
public final class ModernDagTickExecutor implements RegionTickExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModernDagTickExecutor.class);

    /** 系统依赖图 */
    private final SystemGraph systemGraph;

    /** 执行节点的 Executor（通常是 Virtual Thread 执行器） */
    private final Executor nodeExecutor;

    /** 回退执行器（当没有注册系统时使用） */
    private final RegionTickExecutor fallbackExecutor;

    /** 系统名称到 Scope 的映射（用于兼容旧 API） */
    private final Int2ObjectMap<ScopeWrapper> scopeMap = new Int2ObjectOpenHashMap<>();

    /** 编译后的 DAG 缓存 */
    private volatile CompiledDag cachedDag;

    /** 上次编译时的图版本 */
    private volatile int lastGraphVersion = -1;

    /**
     * Scope 包装器 — 保存系统注册时的 scope 信息。
     */
    private record ScopeWrapper(Object scope, BiConsumer<Object, Object> rawExecutor) {}

    /**
     * 创建现代 DAG 执行器。
     *
     * @param nodeExecutor    用于执行节点的 Executor
     * @param fallbackExecutor 没有注册系统时的回退执行器
     */
    public ModernDagTickExecutor(
            final @NotNull Executor nodeExecutor,
            final @NotNull RegionTickExecutor fallbackExecutor
    ) {
        this.nodeExecutor = Objects.requireNonNull(nodeExecutor, "nodeExecutor");
        this.fallbackExecutor = Objects.requireNonNull(fallbackExecutor, "fallbackExecutor");
        this.systemGraph = new SystemGraph();
    }

    /**
     * 创建现代 DAG 执行器（使用默认的 FoliaTickExecutor 作为回退）。
     *
     * @param nodeExecutor 用于执行节点的 Executor
     */
    public ModernDagTickExecutor(final @NotNull Executor nodeExecutor) {
        this(nodeExecutor, new FoliaTickExecutor());
    }

    /**
     * 注册一个 tick 系统（兼容 {@link DagBasedTickExecutor} 的 API）。
     *
     * @param name     系统名称
     * @param profile  系统声明
     * @param scope    系统作用域
     * @param executor 执行函数
     */
    public void registerSystem(
            final @NotNull String name,
            final @NotNull fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile profile,
            final @NotNull Object scope,
            final @NotNull BiConsumer<fun.bm.mili.lmili.thread.regiontick.dag.SystemProfile, Object> executor
    ) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(profile, "profile");

        systemGraph.register(profile, ctx -> executor.accept(profile, scope));
        // 安全转换：SystemProfile 是 Object 的子类型，BiConsumer 可以向上转型
        BiConsumer<Object, Object> rawExec = (BiConsumer<Object, Object>) (BiConsumer<?, ?>) executor;
        scopeMap.put(systemGraph.getHandle(name).id(), new ScopeWrapper(scope, rawExec));
    }

    /**
     * 注销一个 tick 系统。
     *
     * <p>注意：当前实现中 SystemGraph 不支持注销，此方法会抛出 UnsupportedOperationException。
     * 如需动态注册/注销，请使用 {@link SystemGraph} 直接操作。
     *
     * @param name 系统名称
     * @throws UnsupportedOperationException 当前不支持
     */
    public void unregisterSystem(final @NotNull String name) {
        throw new UnsupportedOperationException(
                "ModernDagTickExecutor does not support unregister. " +
                "Use SystemGraph directly for dynamic registration.");
    }

    /**
     * 声明两个系统之间的执行顺序依赖。
     *
     * @param beforeName 先执行的系统名称
     * @param afterName  后执行的系统名称
     */
    public void addDependency(
            final @NotNull String beforeName,
            final @NotNull String afterName
    ) {
        systemGraph.addDependency(beforeName, afterName);
    }

    @Override
    public void executeSlice(
            final @NotNull RegionTickWorker worker,
            final @NotNull RegionTickSlice slice,
            final @NotNull RegionTickContext context
    ) {
        executeSliceAsync(worker, slice, context, null);
    }

    /**
     * 异步执行 slice 并返回完成阶段。
     *
     * <p>这是 Phase A 的核心修复：DAG 完成必须可观察，不能 fire-and-forget。
     * 调用者通过返回的 {@link CompletionStage} 观察 DAG 完成状态。
     *
     * <p>当提供 {@link TickContext} 时，DAG 完成会自动连接到 tick barrier：
     * <pre>
     * DAG execute()
     *     ↓
     * TaskHandle
     *     ↓
     * completion
     *     ↓
     * TickBarrier.complete()
     * </pre>
     *
     * @param worker  执行 worker
     * @param slice   tick 切片
     * @param context region tick 上下文
     * @param tickCtx tick 上下文（可为 null）
     * @return 完成阶段，在 DAG 所有节点完成时完成
     */
    public @NotNull CompletionStage<Void> executeSliceAsync(
            final @NotNull RegionTickWorker worker,
            final @NotNull RegionTickSlice slice,
            final @NotNull RegionTickContext context,
            final @Nullable TickContext tickCtx
    ) {
        if (systemGraph.systemCount() == 0) {
            fallbackExecutor.executeSlice(worker, slice, context);
            return CompletableFuture.completedFuture(null);
        }

        // 获取编译后的 DAG（带缓存）
        CompiledDag dag = getOrCompileDag();
        if (dag == null) {
            fallbackExecutor.executeSlice(worker, slice, context);
            return CompletableFuture.completedFuture(null);
        }

        // 使用新引擎执行，连接 tick barrier
        DagExecutionEngine engine = new DagExecutionEngine();
        DagExecutionEngine.TaskHandle handle = engine.execute(
                dag, nodeExecutor, context.getCurrentTick(), tickCtx);

        // 返回完成阶段 —— DAG 完成必须可观察
        return handle.completion();
    }

    /**
     * 获取或编译 DAG。
     *
     * <p>仅在图结构发生变化时重新编译。
     */
    private CompiledDag getOrCompileDag() {
        CompiledDag dag = cachedDag;
        if (dag != null && systemGraph.version() == lastGraphVersion) {
            return dag;
        }

        synchronized (this) {
            if (cachedDag != null && systemGraph.version() == lastGraphVersion) {
                return cachedDag;
            }

            try {
                dag = systemGraph.compile();
                cachedDag = dag;
                lastGraphVersion = systemGraph.version();
                return dag;
            } catch (Exception e) {
                LOGGER.error("[ModernDagTickExecutor] DAG compilation failed", e);
                // 返回旧的 DAG（如果有的话）
                return cachedDag;
            }
        }
    }

    /**
     * 获取系统依赖图（用于高级配置）。
     */
    public @NotNull SystemGraph getSystemGraph() {
        return systemGraph;
    }

    /**
     * 获取已注册的 Scope 映射（用于兼容旧代码）。
     */
    public @NotNull Map<String, Object> getScopeMap() {
        // 临时兼容方法：返回空 map
        // 未来可以改为返回实际的 scope 映射
        return java.util.Collections.emptyMap();
    }

    /**
     * 获取已注册系统数量。
     */
    public int getSystemCount() {
        return systemGraph.systemCount();
    }
}
