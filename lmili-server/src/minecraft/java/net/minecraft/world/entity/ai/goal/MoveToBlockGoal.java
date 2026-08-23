package net.minecraft.world.entity.ai.goal;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.LevelReader;
import fun.bm.mili.config.modules.optimizations.EntityTickPerformanceConfig; // Mili

public abstract class MoveToBlockGoal extends Goal {
    private static final int GIVE_UP_TICKS = 1200;
    private static final int STAY_TICKS = 1200;
    private static final int INTERVAL_TICKS = 200;
    protected final PathfinderMob mob;
    public final double speedModifier;
    protected int nextStartTick;
    protected int tryTicks;
    private int maxStayTicks;
    protected BlockPos blockPos = BlockPos.ZERO;
    private boolean reachedTarget;
    private final int searchRange;
    private final int verticalSearchRange;
    protected int verticalSearchStart;

    // Mili start - MoveToBlockGoal caching: 缓存 findNearestBlock 结果，避免每 200-400 tick 触发一次 ~1000 blocks 扫描
    // 缓存验证条件：1) 缓存年龄 ≤ moveToBlockGoalCacheMaxAgeTicks；2) isValidTarget 仍为 true；3) stop 时清空
    private BlockPos mili$cachedBlockPos = BlockPos.ZERO;
    private int mili$cachedValidTick = -1; // mob.tickCount at cache write; -1 = 缓存无效
    // Mili end

    public MoveToBlockGoal(final PathfinderMob mob, final double speedModifier, final int searchRange) {
        this(mob, speedModifier, searchRange, 1);
    }
    // Paper start - activation range improvements
    @Override
    public void stop() {
        super.stop();
        this.blockPos = BlockPos.ZERO;
        this.mob.movingTarget = null;
        // Mili: stop 清缓存（goal 停止时缓存不再有效）
        this.mili$cachedBlockPos = BlockPos.ZERO;
        this.mili$cachedValidTick = -1;
    }
    // Paper end

    public MoveToBlockGoal(final PathfinderMob mob, final double speedModifier, final int searchRange, final int verticalSearchRange) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.searchRange = searchRange;
        this.verticalSearchStart = 0;
        this.verticalSearchRange = verticalSearchRange;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.nextStartTick > 0) {
            this.nextStartTick--;
            return false;
        } else {
            this.nextStartTick = this.nextStartTick(this.mob);
            // Mili start - 缓存命中：age 在配置阈值内且 isValidTarget 通过则跳过 findNearestBlock 的 ~1000 blocks 扫描
            if (EntityTickPerformanceConfig.moveToBlockGoalCacheEnabled && this.mili$cachedValidTick >= 0) {
                final int age = this.mob.tickCount - this.mili$cachedValidTick;
                if (age >= 0 && age <= EntityTickPerformanceConfig.moveToBlockGoalCacheMaxAgeTicks) {
                    if (this.isValidTarget(this.mob.level(), this.mili$cachedBlockPos)) {
                        this.blockPos = this.mili$cachedBlockPos;
                        this.mob.movingTarget = this.mili$cachedBlockPos == BlockPos.ZERO ? null : this.mili$cachedBlockPos.immutable();
                        return true; // 缓存命中，避免 findNearestBlock 的块扫描
                    }
                    // 缓存块已无效（block 被破坏/改变）
                    this.mili$cachedValidTick = -1;
                } else {
                    // 缓存过期
                    this.mili$cachedValidTick = -1;
                }
            }
            // Mili end
            return this.findNearestBlock();
        }
    }

    protected int nextStartTick(final PathfinderMob mob) {
        return reducedTickDelay(200 + mob.getRandom().nextInt(200));
    }

    @Override
    public boolean canContinueToUse() {
        return this.tryTicks >= -this.maxStayTicks && this.tryTicks <= 1200 && this.isValidTarget(this.mob.level(), this.blockPos);
    }

    @Override
    public void start() {
        this.moveMobToBlock();
        this.tryTicks = 0;
        this.maxStayTicks = this.mob.getRandom().nextInt(this.mob.getRandom().nextInt(1200) + 1200) + 1200;
    }

    protected void moveMobToBlock() {
        this.mob.getNavigation().moveTo(this.blockPos.getX() + 0.5, this.blockPos.getY() + 1, this.blockPos.getZ() + 0.5, this.speedModifier);
    }

    public double acceptedDistance() {
        return 1.0;
    }

    protected BlockPos getMoveToTarget() {
        return this.blockPos.above();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        BlockPos moveToTarget = this.getMoveToTarget();
        if (!moveToTarget.closerToCenterThan(this.mob.position(), this.acceptedDistance())) {
            this.reachedTarget = false;
            this.tryTicks++;
            if (this.shouldRecalculatePath()) {
                this.mob.getNavigation().moveTo(moveToTarget.getX() + 0.5, moveToTarget.getY(), moveToTarget.getZ() + 0.5, this.speedModifier);
            }
        } else {
            this.reachedTarget = true;
            this.tryTicks--;
        }
    }

    public boolean shouldRecalculatePath() {
        return this.tryTicks % 40 == 0;
    }

    protected boolean isReachedTarget() {
        return this.reachedTarget;
    }

    protected boolean findNearestBlock() {
        int horizontalSearch = this.searchRange;
        int verticalSearch = this.verticalSearchRange;
        BlockPos mobPos = this.mob.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = this.verticalSearchStart; y <= verticalSearch; y = y > 0 ? -y : 1 - y) {
            for (int r = 0; r < horizontalSearch; r++) {
                for (int x = 0; x <= r; x = x > 0 ? -x : 1 - x) {
                    for (int z = x < r && x > -r ? r : 0; z <= r; z = z > 0 ? -z : 1 - z) {
                        pos.setWithOffset(mobPos, x, y - 1, z);
                        if (!this.mob.level().hasChunkAt(pos)) continue; // Gale - Airplane - block goal does not load chunks - if this block isn't loaded, continue
                        if (this.mob.isWithinHome(pos) && this.isValidTarget(this.mob.level(), pos)) {
                            this.blockPos = pos;
                            this.mob.movingTarget = pos == BlockPos.ZERO ? null : pos.immutable(); // Paper
                            // Mili: 写入缓存（用 immutable 避免 MutableBlockPos 被外部修改）
                            if (EntityTickPerformanceConfig.moveToBlockGoalCacheEnabled) {
                                this.mili$cachedBlockPos = pos.immutable();
                                this.mili$cachedValidTick = this.mob.tickCount;
                            }
                            // Mili end
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    protected abstract boolean isValidTarget(LevelReader level, BlockPos pos);
}
