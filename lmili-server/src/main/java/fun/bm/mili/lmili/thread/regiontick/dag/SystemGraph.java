package fun.bm.mili.lmili.thread.regiontick.dag;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 系统依赖图 — 管理所有已注册的 tick 系统及其依赖关系。
 *
 * <p>这是用户与 DAG 调度器交互的主要入口：
 * <ol>
 *   <li>注册系统（{@link #register}）</li>
 *   <li>声明依赖（{@link #addDependency}）</li>
 *   <li>编译为 {@link CompiledDag}（{@link #compile}）</li>
 * </ol>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * SystemGraph graph = new SystemGraph();
 *
 * SystemHandle entityTick = graph.register(
 *     SystemProfile.builder("entity_tick")
 *         .writes(ResourceType.ENTITY_POSITION, ResourceType.ENTITY_MOTION)
 *         .reads(ResourceType.ENTITY_AI_STATE)
 *         .priority(100)
 *         .build(),
 *     ctx -> entityManager.tickEntities()
 * );
 *
 * SystemHandle aiTick = graph.register(
 *     SystemProfile.builder("ai_tick")
 *         .writes(ResourceType.ENTITY_AI_STATE)
 *         .reads(ResourceType.ENTITY_POSITION)
 *         .priority(50)
 *         .build(),
 *     ctx -> entityManager.tickAI()
 * );
 *
 * // entity_tick 必须在 ai_tick 之前执行
 * graph.addDependency(entityTick, aiTick);
 *
 * CompiledDag dag = graph.compile();
 * }</pre>
 *
 * <p>此类为线程安全的（注册和编译可以并发进行，但编译期间不应修改图）。
 */
public final class SystemGraph {

    /** 已注册的系统列表 */
    private final List<RegisteredSystem> systems = new ArrayList<>();

    /** 系统名称到 ID 的映射 */
    private final Map<String, Integer> nameToId = new HashMap<>();

    /** 依赖边：beforeId → afterId（before 必须在 after 之前执行） */
    private final List<int[]> dependencies = new ArrayList<>();

    /** 编译后的 DAG 缓存（惰性计算） */
    private volatile CompiledDag cachedDag;

    /** 图结构版本号（每次修改递增） */
    private int version = 0;

    /**
     * 注册一个 tick 系统。
     *
     * @param profile  系统声明（资源访问模式、优先级）
     * @param executor 系统执行函数
     * @return 系统句柄，用于后续声明依赖
     * @throws IllegalArgumentException 如果系统名称已存在
     */
    public @NotNull SystemHandle register(
            final @NotNull SystemProfile profile,
            final @NotNull CompiledDag.DagExecutor executor
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(executor, "executor");

        synchronized (this) {
            if (nameToId.containsKey(profile.name())) {
                throw new IllegalArgumentException("System already registered: " + profile.name());
            }

            int id = systems.size();
            systems.add(new RegisteredSystem(id, profile, executor));
            nameToId.put(profile.name(), id);
            invalidateCache();

            return new SystemHandle(id, profile.name());
        }
    }

    /**
     * 声明两个系统之间的执行顺序依赖。
     *
     * @param before 先执行的系统
     * @param after  后执行的系统
     * @throws IllegalArgumentException 如果系统未注册或产生循环依赖
     */
    public void addDependency(final @NotNull SystemHandle before, final @NotNull SystemHandle after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");

        synchronized (this) {
            validateHandle(before);
            validateHandle(after);
            if (before.id == after.id) {
                throw new IllegalArgumentException("Cannot add self-dependency: " + before.name);
            }
            dependencies.add(new int[]{before.id, after.id});
            invalidateCache();
        }
    }

    /**
     * 声明两个系统之间的执行顺序依赖（通过名称）。
     *
     * @param beforeName 先执行的系统名称
     * @param afterName  后执行的系统名称
     */
    public void addDependency(final @NotNull String beforeName, final @NotNull String afterName) {
        SystemHandle before = getHandle(beforeName);
        SystemHandle after = getHandle(afterName);
        if (before == null) throw new IllegalArgumentException("System not found: " + beforeName);
        if (after == null) throw new IllegalArgumentException("System not found: " + afterName);
        addDependency(before, after);
    }

    /**
     * 获取系统句柄。
     *
     * @param name 系统名称
     * @return 系统句柄，如果不存在返回 null
     */
    public @Nullable SystemHandle getHandle(final @NotNull String name) {
        Integer id = nameToId.get(name);
        if (id == null) return null;
        RegisteredSystem sys = systems.get(id);
        return new SystemHandle(id, sys.profile.name());
    }

    /**
     * 编译为 {@link CompiledDag}。
     *
     * <p>编译过程：
     * <ol>
     *   <li>构建冲突图（基于资源访问模式）</li>
     *   <li>合并显式依赖和隐式冲突依赖</li>
     *   <li>拓扑排序并验证无环</li>
     *   <li>生成不可变的 CompiledDag</li>
     * </ol>
     *
     * <p>结果会被缓存，直到图结构发生变化。
     *
     * @return 编译后的 DAG
     * @throws IllegalStateException 如果存在循环依赖
     */
    public @NotNull CompiledDag compile() {
        CompiledDag dag = cachedDag;
        if (dag != null) {
            return dag;
        }

        synchronized (this) {
            // 双重检查
            if (cachedDag != null) {
                return cachedDag;
            }

            int n = systems.size();
            if (n == 0) {
                dag = CompiledDag.builder(0).build();
                cachedDag = dag;
                return dag;
            }

            // 1. 构建冲突图
            SystemProfile[] profiles = new SystemProfile[n];
            for (RegisteredSystem sys : systems) {
                profiles[sys.id] = sys.profile;
            }
            ConflictGraph conflictGraph = ConflictGraph.builder(n)
                    .addTypeConflicts(profiles)
                    .build();

            // 2. 构建 CompiledDag
            int[] priorities = new int[n];
            for (RegisteredSystem sys : systems) {
                priorities[sys.id] = sys.profile.priority();
            }

            CompiledDag.Builder builder = CompiledDag.builder(n);

            // 设置执行函数
            for (RegisteredSystem sys : systems) {
                builder.setExecutor(sys.id, sys.executor);
            }

            // 添加显式依赖
            for (int[] dep : dependencies) {
                builder.addEdge(dep[0], dep[1]);
            }

            // 添加隐式冲突依赖（基于优先级决定方向）
            builder.addEdgesFromConflictGraph(conflictGraph, priorities);

            // 构建（会验证无环）
            dag = builder.build();
            cachedDag = dag;

            return dag;
        }
    }

    /**
     * 获取当前图结构版本号。
     *
     * <p>版本号每次注册系统或添加依赖时递增，
     * 可用于检测图是否发生变化。
     */
    public int version() {
        return version;
    }

    /**
     * 获取已注册系统数量。
     */
    public int systemCount() {
        synchronized (this) {
            return systems.size();
        }
    }

    /**
     * 使缓存失效。
     */
    private void invalidateCache() {
        cachedDag = null;
        version++;
    }

    /**
     * 验证句柄是否有效。
     */
    private void validateHandle(final SystemHandle handle) {
        if (handle.id < 0 || handle.id >= systems.size()) {
            throw new IllegalArgumentException("Invalid system handle: " + handle);
        }
        RegisteredSystem sys = systems.get(handle.id);
        if (sys == null || !sys.profile.name().equals(handle.name)) {
            throw new IllegalArgumentException("Stale system handle: " + handle);
        }
    }

    // ==================== 内部数据结构 ====================

    /**
     * 已注册的系统。
     */
    private record RegisteredSystem(
            int id,
            SystemProfile profile,
            CompiledDag.DagExecutor executor
    ) {}

    /**
     * 系统句柄 — 用户引用已注册系统的轻量级标识。
     */
    public record SystemHandle(int id, String name) {
        @Override
        public String toString() {
            return "SystemHandle{" + name + " (id=" + id + ")}";
        }
    }
}
