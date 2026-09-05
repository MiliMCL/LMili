package fun.bm.mili.lmili.thread.regiontick.executor;

import fun.bm.mili.lmili.thread.regiontick.RegionTickContext;
import fun.bm.mili.lmili.thread.regiontick.RegionTickExecutor;
import fun.bm.mili.lmili.thread.regiontick.RegionTickSlice;
import fun.bm.mili.lmili.thread.regiontick.RegionTickWorker;
import fun.bm.mili.lmili.thread.regiontick.dag.CompiledDag;
import fun.bm.mili.lmili.thread.regiontick.dag.SystemGraph;
import fun.bm.mili.lmili.thread.scheduler.tick.TickContext;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
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

    /** 节点路由器（按 regionId 路由节点）。 */
    private volatile NodeScheduler nodeScheduler;

    /** 回退执行器（当没有注册系统时使用） */
    private final RegionTickExecutor fallbackExecutor;

    /** 系统名称到 Scope 的映射（用于兼容旧 API） */
    private final Int2ObjectMap<ScopeWrapper> scopeMap = Int2ObjectMaps.synchronize(new Int2ObjectOpenHashMap<>());

    /** 编译后的 DAG 缓存 */
    private volatile CompiledDag cachedDag;

    /** 上次编译时的图版本 */
    private volatile int lastGraphVersion = -1;

    /**
     * Scope 包装器 — 保存系统注册时的 scope 信息。
     */
    private record ScopeWrapper(String name, Object scope, BiConsumer<Object, Object> rawExecutor) {}

    /**
     * 创建现代 DAG 执行器。
     *
     * @param nodeScheduler   用于派发节点的路由器（按 regionId 路由到正确的执行路径）
     * @param fallbackExecutor 没有注册系统时的回退执行器
     */
    public ModernDagTickExecutor(
            final @NotNull NodeScheduler nodeScheduler,
            final @NotNull RegionTickExecutor fallbackExecutor
    ) {
        this.nodeScheduler = Objects.requireNonNull(nodeScheduler, "nodeScheduler");
        this.fallbackExecutor = Objects.requireNonNull(fallbackExecutor, "fallbackExecutor");
        this.systemGraph = new SystemGraph();
    }

    /**
     * 创建现代 DAG 执行器（使用默认的回退执行器）。
     *
     * @param nodeScheduler 节点路由器
     */
    public ModernDagTickExecutor(final @NotNull NodeScheduler nodeScheduler) {
        this(nodeScheduler, new LMiliTickExecutor());
    }

    /**
     * 替换节点路由器（用于在运行时切换到 region-acquire 路由）。
     *
     * <p>典型用法：在 {@code RegionTickBootstrap.init()} 中调用本方法，把 DAG 节点
     * 从裸 {@link Executor} 切换到按 regionId 路由的 {@link NodeScheduler}，确保
     * 跨 region 节点通过 Bukkit 主线程调度器路由。</p>
     *
     * <p>注意：此切换对正在执行的节点无效，仅影响后续 submit 的节点。</p>
     *
     * @param newScheduler 新路由器（不允许为 null）
     */
    public void setNodeScheduler(final @NotNull NodeScheduler newScheduler) {
        Objects.requireNonNull(newScheduler, "newScheduler");
        LOGGER.info("[ModernDagTickExecutor] Switching node scheduler: {} -> {}",
                this.nodeScheduler.getClass().getSimpleName(),
                newScheduler.getClass().getSimpleName());
        this.nodeScheduler = newScheduler;
    }

    /**
     * 获取当前节点路由器（用于诊断）。
     */
    public @NotNull NodeScheduler getNodeScheduler() {
        return nodeScheduler;
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

        // 确保 name 与 profile.name() 一致
        if (!name.equals(profile.name())) {
            throw new IllegalArgumentException(
                    "System name mismatch: name='" + name + "', profile.name()='" + profile.name() + "'");
        }

        // Mili 扩展：从 scope 提取 regionId，让 DAG 编译时把节点 region 信息固化
        long nodeRegionId = SystemGraph.extractRegionId(scope);
        systemGraph.register(profile, nodeRegionId, ctx -> executor.accept(profile, scope));
        // 安全转换：SystemProfile 是 Object 的子类型，BiConsumer 可以向上转型
        BiConsumer<Object, Object> rawExec = (BiConsumer<Object, Object>) (BiConsumer<?, ?>) executor;
        SystemGraph.SystemHandle handle = systemGraph.getHandle(profile.name());
        if (handle == null) {
            throw new IllegalStateException("Failed to obtain system handle for: " + profile.name());
        }
        scopeMap.put(handle.id(), new ScopeWrapper(profile.name(), scope, rawExec));
    }

    /**
     * 注销一个 tick 系统。
     *
     * <p>当前实现不支持运行时注销，调用此方法将抛出 UnsupportedOperationException。
     * 如需修改系统，请重新创建执行器实例。
     *
     * @param name 系统名称
     * @throws UnsupportedOperationException 当前不支持
     */
    public void unregisterSystem(final @NotNull String name) {
        throw new UnsupportedOperationException(
                "ModernDagTickExecutor does not support runtime unregister. " +
                "System registration is immutable after initialization.");
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
        // P0-2：保留 SAM 兼容路径 —— 通过 executeSliceStage 复用同一套非阻塞提交逻辑，
        // 并把 join() 推迟到调用方需要同步等待的场景（默认实现已经做了）。
        // 这是 legacy 兼容层；新的 ChunkTickDispatcher 路径应直接调用 executeSliceStage。
        executeSliceStage(worker, slice, context).toCompletableFuture().join();
    }

    /**
     * P0-2 新增：非阻塞的 slice 执行入口。
     *
     * <p>返回 {@link CompletionStage} 后立即返回，不阻塞调用线程（无论是 region tick
     * 线程还是 slice worker 线程）。调用方负责把完成阶段连接到 slice barrier
     * （{@code context.arriveSlice / failSlice + context.endTick()}），从而把"提交"
     * 与"完成"分离。</p>
     *
     * <p>与 {@link #executeSlice}（legacy SAM）的区别：
     * <ul>
     *   <li>{@link #executeSlice}：提交后立即 {@code join()}，调用线程被阻塞直至 DAG 完成
     *       —— 这正是计划 §2.1 要消除的"Region Tick 上的无意义 join 阻塞"。</li>
     *   <li>{@link #executeSliceStage}：提交后立即返回；完成发生在节点实际完成时刻，
     *       由调用方的 {@code whenComplete} 回调驱动 slice barrier。</li>
     * </ul>
     *
     * <p><b>完成语义</b>（计划 §2.3）：
     * <ul>
     *   <li><b>Submit</b> —— 此方法返回时已成功（如适用）提交；不保证节点已执行完。</li>
     *   <li><b>Complete</b> —— 所有 DAG 节点已执行完，对应 stage 正常完成。</li>
     *   <li><b>Commit</b> —— 调用方在 whenComplete 回调内驱动 {@code arriveSlice / endTick}
     *       才允许下一阶段 / 下一 tick 看到本次结果。</li>
     * </ul>
     */
    @Override
    public @NotNull CompletionStage<Void> executeSliceStage(
            final @NotNull RegionTickWorker worker,
            final @NotNull RegionTickSlice slice,
            final @NotNull RegionTickContext context
    ) {
        return executeSliceAsync(worker, slice, context, null);
    }

    /**
     * 在 region tick 进入时执行积压的跨 region DAG 子任务。
     *
     * <p>当 CompositeNodeScheduler 把跨 region 节点投递给目标 region 时，
     * 通过 Bukkit 主线程调度器触发目标 region 的任务执行。
     * 本方法在 tick 进入入口被调用，先 drain 上次 tick 遗留的 pending 任务。</p>
     *
     * @param context 当前 region tick 上下文
     */
    public void drainCrossRegionPending(@NotNull final RegionTickContext context) {
        if (systemGraph.systemCount() == 0) return;
        if (!(this.nodeScheduler instanceof CompositeNodeScheduler composite)) {
            return;
        }
        composite.drainCrossRegionPending(context.regionId);
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

        // 使用新引擎执行 —— 节点派发由 NodeScheduler 按 regionId 路由
        DagExecutionEngine engine = new DagExecutionEngine();
        DagExecutionEngine.TaskHandle handle = engine.execute(
                dag, nodeScheduler, context.getCurrentTick(), context, tickCtx);

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
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        synchronized (scopeMap) {
            for (ScopeWrapper wrapper : scopeMap.values()) {
                result.put(wrapper.name(), wrapper.scope());
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * 获取已注册系统数量。
     */
    public int getSystemCount() {
        return systemGraph.systemCount();
    }
}
