/*
 * This file is part of Lithium
 *
 * Lithium is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Lithium is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Lithium. If not, see <https://www.gnu.org/licenses/>.
 */

package org.leavesmc.leaves.lithium.common.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

public interface SleepingBlockEntity {
    TickingBlockEntity SLEEPING_BLOCK_ENTITY_TICKER = new TickingBlockEntity() {
        public void tick() {
        }

        public boolean isRemoved() {
            return false;
        }

        public BlockPos getPos() {
            return null;
        }

        public String getType() {
            return "<lithium_sleeping>";
        }

        @Override
        public BlockEntity getTileEntity() {
            return null;
        }
    };

    // Mili - RebindableTickingBlockEntityWrapper is now private; use Object
    Object lithium$getTickWrapper();

    void lithium$setTickWrapper(Object tickWrapper);

    TickingBlockEntity lithium$getSleepingTicker();

    void lithium$setSleepingTicker(TickingBlockEntity sleepingTicker);

    default boolean lithium$startSleeping() {
        // Mili - stub: mixin support removed
        return false;
    }

    default void sleepOnlyCurrentTick() {
        // Mili - stub: mixin support removed
    }

    default void wakeUpNow() {
        // Mili - stub: mixin support removed
    }

    default void setTicker(TickingBlockEntity delegate) {
        // Mili - stub: mixin support removed
    }

    default boolean isSleeping() {
        return this.lithium$getSleepingTicker() != null;
    }
}
