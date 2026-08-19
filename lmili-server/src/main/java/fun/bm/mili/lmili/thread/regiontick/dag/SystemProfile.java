package fun.bm.mili.lmili.thread.regiontick.dag;

import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class SystemProfile {

    private final String name;
    private final long[] readBits;
    private final long[] writeBits;
    private final long[] allBits;
    private final int priority;

    private SystemProfile(final String name, final long[] readBits, final long[] writeBits, final long[] allBits, final int priority) {
        this.name = name;
        this.readBits = readBits;
        this.writeBits = writeBits;
        this.allBits = allBits;
        this.priority = priority;
    }

    public @NotNull String name() { return this.name; }
    public long @NotNull [] readBits() { return this.readBits.clone(); }
    public long @NotNull [] writeBits() { return this.writeBits.clone(); }
    public long @NotNull [] allBits() { return this.allBits.clone(); }
    public int priority() { return this.priority; }

    // 内部方法：直接访问字段以提高性能，避免克隆
    long[] rawReadBits() { return this.readBits; }
    long[] rawWriteBits() { return this.writeBits; }
    long[] rawAllBits() { return this.allBits; }

    public boolean typeIntersects(final SystemProfile other) {
        return ResourceType.intersects(this.allBits, other.allBits);
    }

    public boolean hasWriteConflictWith(final SystemProfile other) {
        if (ResourceType.intersects(this.writeBits, other.writeBits)) return true;
        if (ResourceType.intersects(this.writeBits, other.readBits)) return true;
        if (ResourceType.intersects(this.readBits, other.writeBits)) return true;
        return false;
    }

    @Override
    public String toString() { return "SystemProfile{" + name + ", priority=" + priority + "}"; }
    @Override
    public boolean equals(final Object o) { return this == o || (o instanceof SystemProfile that && name.equals(that.name)); }
    @Override
    public int hashCode() { return name.hashCode(); }

    public static @NotNull Builder builder(final @NotNull String name) { return new Builder(name); }

    public static final class Builder {
        private final String name;
        final EnumSet<ResourceType> reads = EnumSet.noneOf(ResourceType.class);
        final EnumSet<ResourceType> writes = EnumSet.noneOf(ResourceType.class);
        int priority = 0;

        Builder(final String name) { this.name = Objects.requireNonNull(name, "name"); }

        public @NotNull Builder reads(final @NotNull ResourceType @NotNull ... types) {
            Collections.addAll(this.reads, types);
            return this;
        }

        public @NotNull Builder writes(final @NotNull ResourceType @NotNull ... types) {
            Collections.addAll(this.writes, types);
            return this;
        }

        public @NotNull Builder priority(final int value) {
            this.priority = value;
            return this;
        }

        public @NotNull SystemProfile build() {
            long[] rBits = ResourceType.toBitSet(reads.toArray(new ResourceType[0]));
            long[] wBits = ResourceType.toBitSet(writes.toArray(new ResourceType[0]));
            long[] allBits = new long[Math.max(rBits.length, wBits.length)];
            for (int i = 0; i < allBits.length; i++) {
                long a = i < rBits.length ? rBits[i] : 0L;
                long b = i < wBits.length ? wBits[i] : 0L;
                allBits[i] = a | b;
            }
            return new SystemProfile(name, rBits, wBits, allBits, priority);
        }
    }
}
