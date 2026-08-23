package net.minecraft.world.entity.ai.sensing;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.IntArrays;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.phys.AABB;

public class NearestLivingEntitySensor<T extends LivingEntity> extends Sensor<T> {
    @Override
    protected void doTick(final ServerLevel level, final T body) {
        double followRange = body.getAttributeValue(Attributes.FOLLOW_RANGE);
        AABB boundingBox = body.getBoundingBox().inflate(followRange, followRange, followRange);
        List<LivingEntity> livingEntities = level.getEntitiesOfClass(LivingEntity.class, boundingBox, mob -> mob != body && mob.isAlive());
        final int size = livingEntities.size();
        if (size > 1) {
            // Mili - sort by squared distance with a primitive index sort. Comparator.comparingDouble
            // re-computes distanceToSqr twice per comparison and boxes the result inside an O(n log n)
            // TimSort; here each distance is computed once and an int[] permutation is stably sorted.
            // This is the dominant cost of this sensor inside entity-dense regions.
            final double[] distances = new double[size];
            final int[] perm = new int[size];
            for (int i = 0; i < size; i++) {
                distances[i] = body.distanceToSqr(livingEntities.get(i));
                perm[i] = i;
            }
            IntArrays.stableSort(perm, (a, b) -> Double.compare(distances[a], distances[b]));
            final List<LivingEntity> sorted = new java.util.ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                sorted.add(livingEntities.get(perm[i]));
            }
            livingEntities = sorted;
        }
        Brain<?> brain = body.getBrain();
        brain.setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, livingEntities);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES, new NearestVisibleLivingEntities(level, body, livingEntities));
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return ImmutableSet.of(MemoryModuleType.NEAREST_LIVING_ENTITIES, MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES);
    }
}
