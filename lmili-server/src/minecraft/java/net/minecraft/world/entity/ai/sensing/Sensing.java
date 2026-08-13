package net.minecraft.world.entity.ai.sensing;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

public class Sensing {
    private final Mob mob;
    // Gale start - initialize line of sight cache with low capacity
    private final IntSet seen = new IntOpenHashSet(2);
    private final IntSet unseen = new IntOpenHashSet(2);
    // Gale end - initialize line of sight cache with low capacity

    public Sensing(final Mob mob) {
        this.mob = mob;
    }

    public void tick() {
        this.seen.clear();
        this.unseen.clear();
    }

    public boolean hasLineOfSight(final Entity target) {
        int targetId = target.getId();
        if (this.seen.contains(targetId)) {
            return true;
        }

        if (this.unseen.contains(targetId)) {
            return false;
        }

        ProfilerFiller profiler = Profiler.get();
        profiler.push("hasLineOfSight");
        boolean hasLineOfSight = this.mob.hasLineOfSight(target);
        profiler.pop();
        if (hasLineOfSight) {
            this.seen.add(targetId);
        } else {
            this.unseen.add(targetId);
        }

        return hasLineOfSight;
    }
}
