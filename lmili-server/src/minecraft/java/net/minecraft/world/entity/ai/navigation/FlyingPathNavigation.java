package net.minecraft.world.entity.ai.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;

public class FlyingPathNavigation extends PathNavigation {
    public FlyingPathNavigation(final Mob mob, final Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(final int maxVisitedNodes) {
        this.nodeEvaluator = new FlyNodeEvaluator();
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    @Override
    protected boolean canMoveDirectly(final Vec3 startPos, final Vec3 stopPos) {
        return isClearForMovementBetween(this.mob, startPos, stopPos, true);
    }

    @Override
    protected boolean canUpdatePath() {
        return this.canFloat() && this.mob.isInLiquid() || !this.mob.isPassenger();
    }

    @Override
    protected Vec3 getTempMobPos() {
        return this.mob.position();
    }

    @Override
    public Path createPath(final Entity target, final int reachRange) {
        return this.createPath(target.blockPosition(), target, reachRange); // Paper - EntityPathfindEvent
    }

    @Override
    public void tick() {
        this.tick++;
        if (this.hasDelayedRecomputation) {
            this.recomputePath();
        }

        if (!this.isDone()) {
            if (this.canUpdatePath()) {
                this.followThePath();
            } else if (this.path != null && !this.path.isDone()) {
                Vec3 pos = this.path.getNextEntityPos(this.mob);
                if (this.mob.getBlockX() == Mth.floor(pos.x) && this.mob.getBlockY() == Mth.floor(pos.y) && this.mob.getBlockZ() == Mth.floor(pos.z)) {
                    this.path.advance();
                }
            }

            if (!this.isDone()) {
                Vec3 target = this.path.getNextEntityPos(this.mob);
                // Lmili - Recompute path when path finding out of current tick region
                if (fun.bm.mili.config.modules.fixes.PathfindingFixesConfig.breakDownPathfindingWhenOutOfRegion) {
                    // we assume that:
                    // 1. The code above doesn't touch the 'main thread context' with the position from 'this.path'
                    // 2. The pathfinder could correctly recompute or discard the incorrect target position and this situation is happening rarely
                    if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this.mob.level(), target)) {
                        this.hasDelayedRecomputation = true;
                        return;
                    }
                }
                // Lmili end
                this.mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, this.speedModifier);
            }
        }
    }

    @Override
    public boolean isStableDestination(final BlockPos pos) {
        return this.level.getBlockState(pos).entityCanStandOn(this.level, pos, this.mob);
    }

    @Override
    public boolean canNavigateGround() {
        return false;
    }
}
