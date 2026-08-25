package fun.bm.mili.lmili.thread.regiontick;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import it.unimi.dsi.fastutil.ints.IntArrays;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * 实体 tick 优先级调度器 —— 按"到最近玩家距离"分桶的玩家体验导向调度。
 *
 * <p><b>核心思想</b>：玩家附近的实体应该<b>先 tick</b>（反应快、体验好），
 * 远处的实体<b>后 tick</b> 或 <b>skip 本 tick</b>（性能减压 + 远处玩家感知不到延迟）。
 *
 * <p><b>算法</b>：
 * <ol>
 *   <li>每 {@code EntityTickPerformanceConfig.tickPriorityRecalcIntervalTicks} ticks 重算一次</li>
 *   <li>重算：对每个 ticking entity 计算到最近玩家的距离平方，按"距离桶"分桶</li>
 *   <li>桶内 stable sort（保持原 entityTickList 顺序，避免同距离实体跳序影响 findClosest 等 AI）</li>
 *   <li>跨桶按距离升序拼接：玩家附近先 tick → 远处后 tick → 超 {@code maxDistanceChunks} skip</li>
 *   <li>其他 tick 用上次缓存的距离排序（玩家位置未大变）</li>
 * </ol>
 *
 * <p><b>与现代 Tick 系统其他层的协作</b>：
 * <ul>
 *   <li>Kaiiju：仍按"类型 limit" 降频分片，但本调度让 limit 优先消耗在<b>最近的</b>该类型实体</li>
 *   <li>时间/shard 预算：仍生效（迭代器中途 throw 跳出）</li>
 *   <li>远处 skip：超 {@code maxDistanceChunks} 的实体不入本 tick 迭代器（不入 Kaiiju 计数），
 *   节省带宽但 Kaiiju 窗口计数变少（这正是降频的精确含义）</li>
 * </ul>
 *
 * <p><b>复杂度</b>：
 * <ul>
 *   <li>重算：O(entities × players) 距离平方 + O(entities) 桶分桶 + O(entities × log(entities))
 *   桶内稳定排序</li>
 *   <li>其他 tick：O(entities) 直接返回缓存迭代器</li>
 *   <li>重算频率默认 20 ticks（1 秒）平衡精度与性能</li>
 * </ul>
 *
 * <p><b>线程</b>：所有方法必须在 region tick 线程上调用（regionizedWorldData 是线程本地的）。
 */
public final class EntityPriorityScheduler {

    /** tick 间隔（用 {@code regionTickCounter++} 触发重算判断） */
    private final long regionId;
    private final RegionizedWorldData regionizedWorldData;

    /** 上次重算时的 tick 计数器 */
    private long lastRecalcTickCounter = -1L;

    /** 按优先级排序的 ticking entity 列表（玩家近 →远 → skip 候选） */
    private Entity[] sortedTicking = new Entity[0];

    /** sortedTicking 中"超 maxDistanceChunks 的实体数量"（跳过的实体总数，本 tick 不迭代） */
    private int skippedCount = 0;

    /** 玩家位置缓存（用于判断是否需要重算 —— 玩家位置变化超过 1 chunk 就触发） */
    private double lastRecalcPlayerX;
    private double lastRecalcPlayerY;
    private double lastRecalcPlayerZ;
    private boolean hasLastRecalcPlayerPos = false;

    /** 桶数量（从 config 读取，写入构造以避免反复读 static field） */
    private final int buckets;
    /** 最远距离 chunk 数（超此距离 skip） */
    private final int maxDistanceChunks;

    // ---- 修复：缓存数组，避免每次重算都创建新数组，减少 GC 压力 ----
    /** 缓存的 ticking entities 缓冲区（按需扩容） */
    private Entity[] tickingBuffer = new Entity[256];
    /** 缓存的桶索引缓冲区 */
    private int[] bucketsIdxBuffer = new int[256];
    /** 缓存的排序索引缓冲区 */
    private int[] permBuffer = new int[256];

    public EntityPriorityScheduler(final long regionId, final RegionizedWorldData regionizedWorldData,
                                   final int buckets, final int maxDistanceChunks) {
        this.regionId = regionId;
        this.regionizedWorldData = regionizedWorldData;
        this.buckets = buckets;
        this.maxDistanceChunks = maxDistanceChunks;
    }

    /**
     * 确保缓冲区容量足够，避免频繁扩容。
     */
    private void ensureBufferCapacity(int requiredSize) {
        if (tickingBuffer.length < requiredSize) {
            int newSize = Math.max(requiredSize, tickingBuffer.length * 3 / 2);
            tickingBuffer = new Entity[newSize];
            bucketsIdxBuffer = new int[newSize];
            permBuffer = new int[newSize];
        }
    }

    /**
     * 获取当前 tick 的 ticking entities（按优先级排序），可迭代一次。
     *
     * <p>如果距上次重算 ≥ {@code recalcInterval} ticks 或玩家位置变化 > 1 chunk，触发重算。
     * 否则返回缓存的排序数组。
     *
     * @param tickCounter          当前 region tick 计数（递增）
     * @param recalcIntervalTicks  重算间隔（ticks）
     * @return 排序后的 ticking entities 数组（长度可能 = 实际 ticking 数 - skip 数）
     */
    public Entity[] getSortedTicking(final long tickCounter, final int recalcIntervalTicks) {
        // 触发重算的条件：从未重算过 / 距上次重算 ≥ interval / 玩家移动超过 1 chunk
        if (this.lastRecalcTickCounter < 0L
            || tickCounter - this.lastRecalcTickCounter >= recalcIntervalTicks
            || shouldRecalcDueToPlayerMovement()) {
            recompute(tickCounter);
            this.lastRecalcTickCounter = tickCounter;
            this.hasLastRecalcPlayerPos = true;
        }
        return this.sortedTicking;
    }

    /** 本 tick 被 skip（超过 maxDistanceChunks）的实体数。 */
    public int getSkippedCount() {
        return this.skippedCount;
    }

    private boolean shouldRecalcDueToPlayerMovement() {
        if (!this.hasLastRecalcPlayerPos) {
            return true;
        }
        final List<ServerPlayer> players = this.regionizedWorldData.getLocalPlayers();
        if (players.isEmpty()) {
            return false;
        }
        // 简化策略：任意玩家远离上次重算位置 > 1 chunk (16 方块) 就触发重算
        for (final ServerPlayer p : players) {
            final double dx = p.getX() - this.lastRecalcPlayerX;
            final double dy = p.getY() - this.lastRecalcPlayerY;
            final double dz = p.getZ() - this.lastRecalcPlayerZ;
            if (dx * dx + dy * dy + dz * dz > 16.0 * 16.0) {
                return true;
            }
        }
        return false;
    }

    private void recompute(final long tickCounter) {
        // 用 forEachTickingEntity 收集 ticking 实体子集（避免 inactive 实体浪费距离计算）
        // 修复：使用缓存的缓冲区，避免每次重算都创建新数组
        final int entityCount = this.regionizedWorldData.getEntityCount();
        ensureBufferCapacity(entityCount);
        final Entity[] allTicking = this.tickingBuffer;
        final int[] counter = {0};
        this.regionizedWorldData.forEachTickingEntity(entity -> {
            if (counter[0] < allTicking.length) {
                allTicking[counter[0]++] = entity;
            }
        });
        final int n = counter[0];

        final List<ServerPlayer> players = this.regionizedWorldData.getLocalPlayers();

        // 记录玩家位置（用于下次重算判断）
        if (!players.isEmpty()) {
            final ServerPlayer p = players.get(0);
            this.lastRecalcPlayerX = p.getX();
            this.lastRecalcPlayerY = p.getY();
            this.lastRecalcPlayerZ = p.getZ();
        }

        if (n == 0) {
            this.sortedTicking = EMPTY_ENTITY_ARRAY;
            this.skippedCount = 0;
            return;
        }

        // 计算每个 entity 到最近玩家的距离平方
        final int[] bucketsIdx = this.bucketsIdxBuffer;
        if (players.isEmpty()) {
            // 无玩家：所有实体都视为"超远"（直接 skip）—— 安全策略
            for (int i = 0; i < n; i++) {
                bucketsIdx[i] = this.buckets;
            }
        } else {
            final double maxDistSq = (double) this.maxDistanceChunks * 16.0 * (double) this.maxDistanceChunks * 16.0;
            for (int i = 0; i < n; i++) {
                final Entity e = allTicking[i];
                double min = Double.MAX_VALUE;
                for (final ServerPlayer p : players) {
                    final double d = e.distanceToSqr(p);
                    if (d < min) min = d;
                }
                if (min >= maxDistSq) {
                    bucketsIdx[i] = this.buckets; // 超远 —— 本 tick skip
                } else {
                    // 距离平方 → 桶索引（线性分桶到 [0, buckets-1]）
                    final double ratio = min / maxDistSq; // [0, 1)
                    bucketsIdx[i] = Math.min(this.buckets - 1, (int) (ratio * this.buckets));
                }
            }
        }

        // 按桶索引 stable sort（保持 entityTickList 原顺序）
        // 修复：使用缓存的 perm 缓冲区
        final int[] perm = this.permBuffer;
        for (int i = 0; i < n; i++) perm[i] = i;
        IntArrays.stableSort(perm, (a, b) -> Integer.compare(bucketsIdx[a], bucketsIdx[b]));

        // 按排序后顺序构建 sortedTicking
        int keepCount = 0;
        for (int i = 0; i < n; i++) {
            if (bucketsIdx[perm[i]] >= this.buckets) {
                break; // 桶索引 == buckets 表示 skip
            }
            keepCount++;
        }
        final Entity[] sorted = new Entity[keepCount];
        for (int i = 0; i < keepCount; i++) {
            sorted[i] = allTicking[perm[i]];
        }
        this.sortedTicking = sorted;
        this.skippedCount = n - keepCount;
    }

    private static final Entity[] EMPTY_ENTITY_ARRAY = new Entity[0];

    /** 用于 {@link EntityTickDispatcher} 在 region 销毁时清理缓存 */
    public void clear() {
        this.sortedTicking = EMPTY_ENTITY_ARRAY;
        this.skippedCount = 0;
        this.lastRecalcTickCounter = -1L;
        this.hasLastRecalcPlayerPos = false;
    }

    public long getRegionId() {
        return this.regionId;
    }

    /**
     * 强制下次 {@link #getSortedTicking} 调用重算（跳过 interval 检查）——
     * 用于新加载实体预热后让新实体立即进入排序。
     */
    public void invalidateRecalc() {
        this.lastRecalcTickCounter = -1L;
    }
}