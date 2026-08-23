package fun.bm.mili.config.modules.optimizations;

import fun.bm.mili.config.TomlConfigData;
import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.DoNotLoad;
import fun.bm.mili.lmili.thread.regiontick.PlayerChunkPreloadListener;
import fun.bm.mili.enums.EnumConfigCategory;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * 实体 Tick 高性能配置 —— 现代化高性能实体 tick 系统的总开关。
 *
 * <p>所有项默认禁用 / 原版行为，启用后获得 Kaiiju 集成之外的额外现代化优化：
 * <ul>
 *   <li><b>时间预算 + shard 分片</b> —— 类型无关的全 region tick 兜底（Kaiiju 已做类型级降频分片）</li>
 *   <li><b>实体 tick 优先级排序</b> —— 按"到最近玩家距离"分桶，先 tick 玩家附近实体（近→远），
 *   远处超过 {@link #tickPriorityMaxDistanceChunks} 直接 skip</li>
 *   <li><b>远距离实体降频节流</b> —— Stage A1 单一桶：&gt;256 blocks 的 Mob 降频 AI（goal/target
 *   selector 每 {@link #entityThrottleIntervalTicks} tick 一次），其余 tick 只做时间推进
 *   （throttle-first，非跳过），见 {@link #entityThrottleEnabled}</li>
 *   <li><b>玩家周围 chunk 预加载</b> —— 用 Moonrise 的 {@code moonrise$loadChunksAsync}
 *   提前 1-2 tick 异步加载玩家即将进入的 chunks，让 chunk load 与玩家移动并行</li>
 *   <li><b>新加载实体预热</b> —— region tick 开始时对刚加载的实体预激活 + 预算距离</li>
 *   <li><b>世界级实体清理</b> —— Paper 的 spawn-limits 不限制刷怪塔产物（portal farm），本项做硬 cap 兜底</li>
 *   <li><b>指标</b> —— 每 region ticked / skipped / removed 计数与平均 tick 时间</li>
 * </ul>
 *
 * <p>Kaiiju 实体限流（清理 removal + 类型级降频分片 limit）由 {@code KaiijuEntityLimits.enabled}
 * （{@code lmili_config/kaiiju_entity_limits.yml}）控制，不在本类。
 */
@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "entity_tick_performance")
public class EntityTickPerformanceConfig implements IConfigModule {

    /**
     * 单 region 单 tick 的实体 tick 时间预算（毫秒，墙钟）。
     *
     * <p>超过此预算时跳出本 tick 的剩余实体 tick（异常中断 Consumer + 下 tick 重置）。
     * <ul>
     *   <li>0.0 = 禁用（原版行为，无时间预算）</li>
     *   <li>推荐 4.0 - 10.0 ms（50 ms tick 预算内留给 chunk tick / 网络 / 其他）</li>
     * </ul>
     *
     * <p>与 Kaiiju limit 互补：Kaiiju 做类型级降频分片（真正跨 tick 窗口轮转），
     * 本项做类型无关的本 tick 全局兜底，防止未配置类型堆积导致 watchdog 卡死。
     */
    public static double entityTickBudgetMs = 0.0;

    /**
     * 单 region 单 tick 最大 tick 实体数（硬性，与时间预算同时生效，取先到）。
     *
     * <p>0 = 禁用。
     * <p>推荐 256 - 1024。
     */
    public static int entityTickShardSize = 0;

    /**
     * 世界级实体总数硬上限（绝对值，非每 region）。
     *
     * <p>超过此数量时按优先级移除：非持久 + 非命名 + Mob + 离最近玩家最远。
     * <ul>
     *   <li>-1 = 禁用（原版行为，无上限；与 Paper 的 spawn-limits 一致 —— spawn-limits 控制自然
     *   刷怪速率，无法限制刷怪塔 portal farm 产物）</li>
     *   <li>正整数 = 启用，启用后每 {@link #entityCleanupIntervalMs} ms 检查一次</li>
     * </ul>
     */
    public static int entityCleanupCap = -1;

    /**
     * 实体清理扫描间隔（毫秒）。仅在 {@link #entityCleanupCap} &gt; 0 时生效。
     * <p>默认 5000 ms。
     */
    public static long entityCleanupIntervalMs = 5000L;

    /**
     * 清理时优先移除离任何玩家超过此距离（方块）的实体。
     * <p>默认 128（实体激活范围附近边界 —— 远处未激活实体优先清，保留玩家附近实体）。
     */
    public static double entityCleanupMinPlayerDistance = 128.0;

    /**
     * 启用实体 tick 指标收集（每 region ticked / skipped / removed 计数、平均 tick 耗时）。
     * <p>默认 true。可通过 {@code EntityTickDispatcher.getStats()} / region 指标快照查看。
     */
    public static boolean metricsEnabled = true;

    /**
     * 启用按"到最近玩家距离"分桶的实体 tick 优先级排序（全局级别，类型无关）。
     *
     * <p>每 {@link #tickPriorityRecalcIntervalTicks} ticks 重算一次距离桶顺序（玩家位置变化时），
     * 其他 tick 用缓存。实体按距离平方分到 N 个桶（{@link #tickPriorityBuckets}），桶内按桶索引
     * 稳定排序 —— <b>玩家附近实体先 tick，远处实体后 tick</b>，远处超过
     * {@link #tickPriorityMaxDistanceChunks} 个 chunk 直接 skip（不入本 tick）。
     *
     * <p>与 Kaiiju 互补：Kaiiju 按<b>实体类型</b>降频（每个类型独立窗口轮转），
     * 本项按<b>玩家距离</b>分级 —— Kaiiju 的"limit = 200" 含义变成"每 tick tick 200 个最近的该类型实体"。
     *
     * <p>false = 禁用（原版行为，无距离优先级）。
     */
    public static boolean tickPriorityByDistance = false;

    /**
     * 距离桶数量（仅在 {@link #tickPriorityByDistance} 启用时生效）。
     * <p>默认 8（每桶 32 方块，覆盖 0-256 方块，最末桶 = 超 256 方块 skip 候选）。
     */
    public static int tickPriorityBuckets = 8;

    /**
     * 距离桶顺序重算间隔（ticks）。仅在 {@link #tickPriorityByDistance} 启用时生效。
     * <p>默认 20（1 秒）。玩家位置每秒可能移动数 chunk，但每 tick 重算成本高
     * （O(实体×玩家) 距离平方），20 ticks 平衡精度/性能。
     */
    public static int tickPriorityRecalcIntervalTicks = 20;

    /**
     * 距离桶最末桶上限（chunk 数）。仅在 {@link #tickPriorityByDistance} 启用时生效。
     * <p>默认 16（256 方块）—— 实体到最近玩家超过 16 个 chunk（约 256 方块），
     * 直接 skip 本 tick（等待下次重算时再看）。
     */
    public static int tickPriorityMaxDistanceChunks = 16;

    /**
     * 启用玩家周围 chunk 异步预加载（用 Moonrise 的 {@code moonrise$loadChunksAsync}）。
     *
     * <p>每个 region tick 开头遍历本地玩家，对每个玩家提交其当前 chunk 周围
     * {@link #chunkPreloadRadiusChunks} 个 chunk 的异步加载请求（Priority = NORMAL）。
     * 让 chunk load 与玩家移动并行 —— 玩家**进入** chunk A 的**下一 tick**，chunk A 已被异步加载完。
     *
     * <p>Folia 默认也有"load radius"（提前 1-2 chunk 加载），本项可扩大半径或调优先级。
     * false = 禁用（原版行为，使用 Folia 默认 load radius）。
     */
    public static boolean chunkPreloadEnabled = false;

    /**
     * 玩家周围预加载半径（chunk 数）。仅在 {@link #chunkPreloadEnabled} 启用时生效。
     * <p>默认 2（玩家周围 5x5 chunks）。
     */
    public static int chunkPreloadRadiusChunks = 2;

    /**
     * 启用新加载实体预激活 + 距离预算。
     *
     * <p>每个 region tick 开头检查刚加载的 chunk 中的新实体（通过 {@code Moonrise} 的
     * chunk status 监听），调用 Paper 的 {@code ActivationRange.activateEntities} 让它们
     * 立即被纳入激活判断（避免第一个 tick 的"激活冷启动"开销），同时把它们加入
     * {@link EntityPriorityScheduler} 的优先桶（玩家附近实体先 tick）。
     *
     * <p>false = 禁用（原版行为，无预热）。
     */
    public static boolean entityPreActivateEnabled = false;

    /**
     * 启用跨 region chunk 预加载 —— 在玩家还在 A region 时预测并预加载 B region 的 chunks。
     *
     * <p>用 Folia 的 {@code RegionizedTaskQueue.queueTickTaskQueue} 路由 chunk load 任务到
     * 目标 region 线程，让 chunk load 与玩家移动<b>完全并行</b>。玩家到达 B region 第一 tick
     * 时 chunks 已就绪，无加载 spike。
     *
     * <p>与 {@link #chunkPreloadEnabled}（同 region 内扩展半径）互补。
     *
     * <p>false = 禁用（原版行为，使用 Folia 默认 region 切换处理）。
     */
    public static boolean crossRegionChunkPreloadEnabled = false;

    /**
     * 跨 region chunk 预加载 —— 预测未来 chunks 数（玩家当前速度方向上的 chunks）。
     * <p>默认 2（玩家未来 2 chunks）。值越大越激进，但可能浪费（玩家转向）。
     */
    public static int crossRegionChunkPreloadLookaheadChunks = 2;

    /**
     * 跨 region chunk 预加载 —— 预加载半径（以预测 chunk 为中心的周围 chunks 数）。
     * <p>默认 2（5×5 chunks）。与 {@link #chunkPreloadRadiusChunks} 独立。
     */
    public static int crossRegionChunkPreloadRadiusChunks = 2;

    /**
     * 启用 {@link net.minecraft.world.entity.ai.goal.MoveToBlockGoal} 缓存优化。
     *
     * <p>缓存最近一次 {@code findNearestBlock} 找到的 block + 缓存有效 tick 数，
     * 在缓存有效期内跳过 full 块扫描（{@code canUse} 直接复用缓存命中）。当缓存的
     * block 仍然 {@code isValidTarget} 且 mob 距其不超过 {@code searchRange} 时直接复用。
     *
     * <p>消除日志中出现的 {@code findNearestBlock} 热点（每 200-400 tick 触发一次，
     * 最多 ~1000 blocks 扫描）。
     *
     * <p>true = 启用（默认推荐，几乎零风险，原版语义保留：缓存 miss 时 fallback full 扫描）。
     */
    public static boolean moveToBlockGoalCacheEnabled = true;

    /**
     * MoveToBlockGoal 缓存最大有效期（ticks）。
     * <p>默认 100（5 秒）。超过此 tick 数强制 full 扫描（避免长时间缓存失效）。
     */
    public static int moveToBlockGoalCacheMaxAgeTicks = 100;

    /**
     * 启用远距离实体降频节流（Stage A1 —— 单一桶：&gt;256 blocks 的 Mob）。
     *
     * <p><b>throttle-first（降频而非跳过）</b>：对离最近玩家超过
     * {@link #entityThrottleDistanceBlocks} 方块的 Mob，把 AI（goal/target selector）降频到
     * 每 {@link #entityThrottleIntervalTicks} tick 执行一次，其余 tick 只做<b>时间推进</b>
     * （传送门处理 + noActionTime 递增，保证刷怪塔传送门与 despawn 逻辑不冻结）。
     *
     * <p><b>严格 eligibility（全部满足才节流）</b>：
     * <ul>
     *   <li>{@code entity instanceof Mob}</li>
     *   <li>{@code !isPersistenceRequired()} —— 非持久化实体</li>
     *   <li>{@code !requiresCustomPersistence()} —— 未被骑乘 / 未拴绳</li>
     *   <li>{@code !hasCustomName()} —— 无自定义名</li>
     *   <li>不处于 ActivationRange —— 节流只在 {@code tickNonPassenger} 的 inactive 分支生效
     *   （调用点保证 {@code isActive == false}，策略内不重复调用 {@code checkIfActive}）</li>
     *   <li>离最近玩家距离 &gt; {@link #entityThrottleDistanceBlocks} 方块</li>
     * </ul>
     *
     * <p>节流接入 <b>{@code ServerLevel.tickNonPassenger}</b> 的 inactive 分支（不绕过其生命周期）：
     * {@code tickCount++ / totalEntityAge++ / setOldPosAndRot / ActivationRange / profiler / portal /
     * passenger} 全部由原有路径维护。节流判定放在 {@code tickCount++} 之后，
     * 用 {@code entity.tickCount % interval == 0} 做 FULL / THROTTLE 节拍（不额外维护 per-entity 计数器）。
     *
     * <p><b>false = 禁用（原版行为）</b>。启用后玩家靠近（进入 activation range 或 256 方块内）
     * 立即恢复 FULL 路径，无永久跳过。
     */
    public static boolean entityThrottleEnabled = false;

    /**
     * 节流距离阈值（方块，线性距离，非平方）。仅在 {@link #entityThrottleEnabled} 启用时生效。
     *
     * <p>默认 256。代码内部会平方为 {@code 256 * 256 = 65536} 与 {@code distanceToSqr()} 结果比较，
     * 避免"16*16=256 误当 256 blocks"的单位错误。
     */
    public static double entityThrottleDistanceBlocks = 256.0;

    /**
     * 节流间隔（ticks）—— 每 N tick 执行一次 FULL（完整 inactiveTick），其余 tick 走节流路径。
     * 仅在 {@link #entityThrottleEnabled} 启用时生效。
     *
     * <p>默认 4。节拍用 {@code entity.tickCount % interval == 0} 判断（tickCount 已在
     * {@code tickNonPassenger} 入口递增），无需额外 per-entity 计数器。
     */
    public static int entityThrottleIntervalTicks = 4;

    // Mili: Bukkit listener —— 玩家下线时清理 PlayerChunkPreloader / CrossRegionChunkPreloader 的 per-player 缓存
    @DoNotLoad
    private static PlayerChunkPreloadListener playerQuitListener = null;

    /**
     * 由 {@code MiliOptimizations.init(Plugin)} 调用 —— 提供 Mili plugin 实例，
     * listener 注册时直接使用，避免 {@code Bukkit.getPluginManager().getPlugin("Mili")} 间接查找
     * 在启动早期 race condition 返回 null 的问题。
     */
    @DoNotLoad
    private static org.bukkit.plugin.Plugin cachedMiliPlugin = null;

    /**
     * 提供 Mili plugin 实例（由 MiliOptimizations.init 调用）。
     * 应在 onLoaded 之前调用。
     */
    public static void setMiliPlugin(final org.bukkit.plugin.Plugin plugin) {
        cachedMiliPlugin = plugin;
        // 如果 listener 已创建但未注册（onLoaded 先于 setMiliPlugin 调用），现在补注册
        if (playerQuitListener != null && !playerQuitListener.isRegistered()) {
            playerQuitListener.register(plugin);
        }
    }

    @Override
    public void onLoaded(final TomlConfigData configInstance, @Nullable final Set<Exception> e) {
        // 注册 Bukkit listener（仅注册一次）
        if (playerQuitListener == null) {
            playerQuitListener = new PlayerChunkPreloadListener();
            // 优先使用 MiliOptimizations.init 提供的 plugin 实例
            if (cachedMiliPlugin != null) {
                playerQuitListener.register(cachedMiliPlugin);
            } else {
                // 回退：尝试任意已启用的 plugin（极端情况：MiliOptimizations.init 未调用）
                final org.bukkit.plugin.Plugin miliPlugin = Bukkit.getPluginManager().getPlugin("Mili");
                if (miliPlugin != null) {
                    playerQuitListener.register(miliPlugin);
                } else {
                    for (final org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
                        if (p.isEnabled()) {
                            playerQuitListener.register(p);
                            break;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onUnloaded(final TomlConfigData configInstance) {
        if (playerQuitListener != null) {
            playerQuitListener.unregister();
            playerQuitListener = null;
        }
        cachedMiliPlugin = null;
    }
}