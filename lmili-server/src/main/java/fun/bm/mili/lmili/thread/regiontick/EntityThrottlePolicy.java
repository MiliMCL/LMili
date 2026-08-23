package fun.bm.mili.lmili.thread.regiontick;

import fun.bm.mili.config.modules.optimizations.EntityTickPerformanceConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * 远距离实体降频节流策略（Stage A1 —— 单一桶：&gt;256 blocks 的 Mob）。
 *
 * <p><b>throttle-first（降频，而非跳过/删除）</b>：本策略只决定"本 tick 走 FULL 还是 THROTTLE"，
 * <b>不删除实体、不永久跳过实体</b>。节流路径仍推进时间（传送门 + noActionTime），
 * 玩家靠近即恢复 FULL，无任何死锁。
 *
 * <p><b>判定位置</b>：由 {@code ServerLevel.tickNonPassenger} 的 inactive 分支调用，
 * 此时 {@code entity.tickCount} 已在 tick 入口递增。因此：
 * <ul>
 *   <li>{@code tickCount % interval == 0} → FULL（完整 {@code inactiveTick}）</li>
 *   <li>{@code tickCount % interval != 0} → THROTTLE（{@code mob.mili$throttledInactiveTick()}）</li>
 * </ul>
 *
 * <p><b>距离单位</b>：配置 {@code entityThrottleDistanceBlocks} 是<b>线性方块数</b>，
 * 本类平方为 {@code 256 * 256 = 65536} 后与 {@code distanceToSqr()} 比较，
 * 避免"16*16=256 误当 256 blocks"的单位错误。
 *
 * <p><b>线程</b>：所有方法必须在 region tick 线程上调用（{@code getLocalPlayers()} 依赖 Folia 线程本地数据）。
 */
public final class EntityThrottlePolicy {

    private EntityThrottlePolicy() {
        throw new AssertionError("no instances");
    }

    /**
     * 节流总开关（{@code entityThrottleEnabled}）。用于调用方短路 ——
     * 禁用时跳过所有 eligibility 计算，行为与原版完全一致。
     */
    public static boolean isEnabled() {
        return EntityTickPerformanceConfig.entityThrottleEnabled;
    }

    /**
     * 判断该 Mob 是否满足节流的<b>类型 / 持久化 / 距离</b> eligibility（不含 tick 节拍）。
     *
     * <p>全部满足才返回 true：
     * <ol>
     *   <li>总开关 {@code entityThrottleEnabled} 启用</li>
     *   <li>非持久化（{@code !isPersistenceRequired()}）</li>
     *   <li>未被骑乘 / 未拴绳（{@code !requiresCustomPersistence()}）—— 保护玩家交互实体</li>
     *   <li>无自定义名（{@code !hasCustomName()}）—— 保护命名实体</li>
     *   <li>离最近玩家距离 &gt; {@code entityThrottleDistanceBlocks} 方块</li>
     * </ol>
     *
     * @param level 当前 ticking 的 ServerLevel（用于取本地玩家）
     * @param mob   候选实体（已由调用方确认 {@code instanceof Mob}）。
     *              <b>不检查 {@code ActivationRange.checkIfActive}</b> —— 本策略仅在
     *              {@code ServerLevel.tickNonPassenger} 的 inactive 分支被调用（该分支本身保证
     *              {@code isActive == false}），调用点已隐含此条件，策略内不重复检查、不浪费 CPU。
     */
    public static boolean isThrottleEligible(final ServerLevel level, final Mob mob) {
        if (!isEnabled()) {
            return false;
        }
        if (mob.isPersistenceRequired()) {
            return false;
        }
        if (mob.requiresCustomPersistence()) {
            return false;
        }
        if (mob.hasCustomName()) {
            return false;
        }

        final double thresholdBlocks = EntityTickPerformanceConfig.entityThrottleDistanceBlocks;
        final double thresholdSq = thresholdBlocks * thresholdBlocks;
        return nearestPlayerDistanceSq(level, mob) > thresholdSq;
    }

    /**
     * 判断本 tick 是否应走节流路径（而非 FULL）。
     *
     * <p>{@code entity.tickCount % interval == 0} → FULL（返回 false）；
     * 其余 → THROTTLE（返回 true）。tickCount 已在 tick 入口递增，
     * 因此节拍自然为 {@code 1→throttle, 2→throttle, 3→throttle, 4→full, 5→throttle, ...}。
     */
    public static boolean shouldThrottleThisTick(final Mob mob) {
        final int interval = Math.max(1, EntityTickPerformanceConfig.entityThrottleIntervalTicks);
        return mob.tickCount % interval != 0;
    }

    /**
     * 计算实体到最近本地玩家的距离平方。
     *
     * <p>使用 Folia 的 {@code getLocalPlayers()}（当前 ticking region 的玩家，线程本地）。
     * 无本地玩家时返回 {@code Double.MAX_VALUE}（视为超远 → 节流），与
     * {@link EntityPriorityScheduler} 的无玩家策略一致。
     */
    private static double nearestPlayerDistanceSq(final ServerLevel level, final Mob mob) {
        final List<ServerPlayer> players = level.getLocalPlayers();
        if (players.isEmpty()) {
            return Double.MAX_VALUE;
        }

        double min = Double.MAX_VALUE;
        for (final ServerPlayer player : players) {
            final double d = mob.distanceToSqr(player);
            if (d < min) {
                min = d;
            }
        }
        return min;
    }
}
