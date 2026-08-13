package fun.bm.mili.lmili.thread.regiontick.dag;

import it.unimi.dsi.fastutil.ints.IntCollection;
import org.jetbrains.annotations.NotNull;

public sealed interface Scope permits Scope.RegionScope, Scope.ChunkScope, Scope.EntityIdSetScope {

    boolean intersects(@NotNull Scope other);
    boolean isGlobal();

    record RegionScope(long regionId) implements Scope {
        @Override
        public boolean intersects(@NotNull final Scope other) { return true; }
        @Override
        public boolean isGlobal() { return true; }
        @Override
        public String toString() { return "RegionScope{regionId=" + regionId + "}"; }
    }

    record ChunkScope(long regionId, it.unimi.dsi.fastutil.longs.LongSet chunkPositions) implements Scope {
        @Override
        public boolean intersects(@NotNull final Scope other) {
            if (other instanceof RegionScope) return true;
            if (other instanceof EntityIdSetScope) return true;
            if (other instanceof ChunkScope o) return intersectsChunks(o);
            return true;
        }

        @Override
        public boolean isGlobal() { return false; }

        public boolean intersectsChunks(@NotNull final ChunkScope other) {
            if (this.regionId != other.regionId) return false;
            var small = this.chunkPositions.size() <= other.chunkPositions.size() ? this.chunkPositions : other.chunkPositions;
            var large = small == this.chunkPositions ? other.chunkPositions : this.chunkPositions;
            var iter = small.iterator();
            while (iter.hasNext()) if (large.contains(iter.nextLong())) return true;
            return false;
        }

        @Override
        public String toString() { return "ChunkScope{regionId=" + regionId + ", chunks=" + chunkPositions.size() + "}"; }
    }

    record EntityIdSetScope(long regionId, @NotNull IntCollection entityIds) implements Scope {
        @Override
        public boolean intersects(@NotNull final Scope other) {
            if (other instanceof RegionScope) return true;
            if (other instanceof ChunkScope) return true;
            if (other instanceof EntityIdSetScope o) {
                var small = this.entityIds.size() <= o.entityIds.size() ? this.entityIds : o.entityIds;
                var large = small == this.entityIds ? o.entityIds : this.entityIds;
                var iter = small.iterator();
                while (iter.hasNext()) if (large.contains(iter.nextInt())) return true;
                return false;
            }
            return true;
        }

        @Override
        public boolean isGlobal() { return false; }

        @Override
        public String toString() { return "EntityIdSetScope{regionId=" + regionId + ", entities=" + entityIds.size() + "}"; }
    }
}
