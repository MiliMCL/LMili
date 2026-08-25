package fun.bm.mili.lmili.thread.regiontick;

import com.mojang.logging.LogUtils;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

/**
 * 世界级实体清理管理器 —— Paper 的 spawn-limits 控制自然刷怪速率但无法限制刷怪塔产物
 * （portal farm 等），本类做硬 cap 兜底：当世界实体总数超过 {@code cap} 时按优先级移除。
 *
 * <p>清理策略（按优先级从高到低）：
 * <ol>
 *   <li>{@link Mob}（刷怪塔产物的主体 —— 猪人、僵尸、骷髅等）</li>
 *   <li>非持久（{@link Mob#isPersistenceRequired()} == false）—— 排除命名 / 刷怪笼 / 交易村民等</li>
 *   <li>非命名（{@link Entity#hasCustomName()} == false）—— 玩家马 / NPC 等被保留</li>
 *   <li>存活（{@link Entity#isAlive()}）—— 已死亡实体本就不 tick，跳过</li>
 *   <li>离最近玩家超过阈值（默认 128 方块）</li>
 * </ol>
 *
 * <p>候选按"离最近玩家的距离"降序排序，远的优先移除（玩家附近实体最后动）。
 * 一次清理直到 {@code total - removed <= cap * 0.9}（留 10% 余量避免频繁触发）。
 *
 * <p>线程：必须在 region tick 线程上调用（{@link Entity#remove} 的 Folia 线程约束）。
 * 性能：每次扫描遍历 {@link RegionizedWorldData#getLocalEntities()} 一次（无拷贝，
 * 直接迭代 {@code allEntities} fastutil {@code ReferenceList}），对 N 实体的开销为 O(N) instanceof
 * + 简单字段检查 + O(N × players) 距离平方计算 —— 5s 一次可接受。
 */
public final class EntityCleanupManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 修复：使用 ThreadLocal 缓存数组，避免每次调用都创建新数组，减少 GC 压力。
     * 每个线程维护自己的缓冲区，当需要更大容量时自动扩容。
     */
    private static final ThreadLocal<CleanupBuffer> BUFFER_CACHE = ThreadLocal.withInitial(CleanupBuffer::new);

    /**
     * 清理缓冲区 —— 用于复用数组，减少 GC 压力。
     */
    private static final class CleanupBuffer {
        it.unimi.dsi.fastutil.objects.ReferenceArrayList<Entity> candidates =
            new it.unimi.dsi.fastutil.objects.ReferenceArrayList<>();
        double[] distSqs = new double[256];
        int[] order = new int[256];

        void ensureCapacity(int size) {
            if (distSqs.length < size) {
                // 扩容到所需大小的 1.5 倍，避免频繁扩容
                int newSize = Math.max(size, distSqs.length * 3 / 2);
                distSqs = new double[newSize];
                order = new int[newSize];
            }
        }
    }

    private EntityCleanupManager() {}

    /**
     * 在 region tick 线程上调用。检查并清理世界级实体总数超限。
     *
     * @param level             region tick 上下文（用于日志）
     * @param data              region 数据（提供 {@code getEntityCount} / {@code getLocalEntities} /
     *                          {@code getLocalPlayers}）
     * @param cap               硬上限（绝对值）
     * @param minPlayerDistance 离玩家最小距离（方块），低于此距离的实体不清理
     * @param metrics           指标（可为 null），清理数记入 {@link EntityTickMetrics#recordRemoved()}
     * @return 本次清理移除的实体数
     */
    public static int tick(final ServerLevel level,
                           final RegionizedWorldData data,
                           final int cap,
                           final double minPlayerDistance,
                           final EntityTickMetrics metrics) {
        final int total = data.getEntityCount();
        if (total <= cap) {
            return 0;
        }
        final double minDistSq = minPlayerDistance * minPlayerDistance;
        final java.util.List<ServerPlayer> players = data.getLocalPlayers();

        // 修复：使用 ThreadLocal 缓冲区复用数组
        final CleanupBuffer buffer = BUFFER_CACHE.get();
        final it.unimi.dsi.fastutil.objects.ReferenceArrayList<Entity> candidates = buffer.candidates;
        candidates.clear(); // 清空但保留底层数组

        // 两阶段：先按 Mob + 非持久 + 非命名 + 存活筛选；只对候选计算距离（避免 80w 实体的 N×P 距离计算）
        for (final Entity e : data.getLocalEntities()) {
            if (!(e instanceof Mob mob)) continue;
            if (!mob.isAlive()) continue;
            if (mob.isPersistenceRequired()) continue;
            if (mob.hasCustomName()) continue;
            candidates.add(mob);
        }
        if (candidates.isEmpty()) {
            return 0;
        }

        final int candidateCount = candidates.size();
        buffer.ensureCapacity(candidateCount);
        final double[] distSqs = buffer.distSqs;
        final int[] order = buffer.order;

        // 计算每个候选离最近玩家距离平方（O(candidates × players)）
        if (players.isEmpty()) {
            // 无玩家时全部候选都视为"远"
            for (int i = 0; i < candidateCount; i++) {
                distSqs[i] = Double.MAX_VALUE;
            }
        } else {
            for (int i = 0; i < candidateCount; i++) {
                final Entity e = candidates.get(i);
                double min = Double.MAX_VALUE;
                for (final ServerPlayer p : players) {
                    final double d = e.distanceToSqr(p);
                    if (d < min) min = d;
                }
                distSqs[i] = min;
            }
        }
        // 按距离降序索引排序（远的优先 remove）
        for (int i = 0; i < candidateCount; i++) order[i] = i;
        it.unimi.dsi.fastutil.ints.IntArrays.quickSort(order,
            (a, b) -> Double.compare(distSqs[b], distSqs[a]));
        // 移除直到 ≤ 90% cap；同时尊重 minPlayerDistance（近的保留）
        final int target = Math.max(0, (int) (cap * 0.9));
        int removed = 0;
        for (int oi = 0; oi < candidateCount; oi++) {
            final int idx = order[oi];
            if (total - removed <= target) break;
            // 二次过滤：最终确认离玩家 > minPlayerDistance（候选阶段未过滤距离，仅过滤了 Mob 标志）
            if (distSqs[idx] < minDistSq) {
                // 近玩家实体本 tick 不清理（保留玩家周边体验），但放弃继续（本轮已按远→近遍历，
                // 后面的更近，所以直接退出）
                break;
            }
            final Entity e = candidates.get(idx);
            e.remove(Entity.RemovalReason.DISCARDED);
            removed++;
            if (metrics != null) {
                metrics.recordRemoved();
            }
        }
        if (removed > 0) {
            LOGGER.info("[EntityCleanup] world='{}' removed={} (cap={}, was={}, candidates={})",
                level.dimension().identifier(), removed, cap, total, candidateCount);
        }
        return removed;
    }
}