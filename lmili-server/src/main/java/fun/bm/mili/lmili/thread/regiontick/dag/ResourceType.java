package fun.bm.mili.lmili.thread.regiontick.dag;

import org.jetbrains.annotations.NotNull;

public enum ResourceType {
    BLOCK_STATE("block_state", 0),
    LIGHT_DATA("light_data", 1),
    REDSTONE_NETWORK("redstone_network", 2),
    TILE_ENTITY_DATA("tile_entity_data", 3),
    BLOCK_EVENT("block_event", 4),
    ENTITY_POSITION("entity_position", 5),
    ENTITY_MOTION("entity_motion", 6),
    ENTITY_AI_STATE("entity_ai_state", 7),
    ENTITY_VITALS("entity_vitals", 8),
    ENTITY_INVENTORY("entity_inventory", 9),
    ENTITY_RIDING("entity_riding", 10),
    ENTITY_CHUNK_LOCATION("entity_chunk_location", 11),
    CHUNK_TICKET("chunk_ticket", 12),
    POI_DATA("poi_data", 13),
    WEATHER_STATE("weather_state", 14),
    TIME_AND_SCHEDULE("time_and_schedule", 15),
    ENTITY_TRACKER_SYNC("entity_tracker_sync", 16),
    CHUNK_SEND_QUEUE("chunk_send_queue", 17);

    private final String name;
    private final int bitIndex;

    ResourceType(final String name, final int bitIndex) {
        this.name = name;
        this.bitIndex = bitIndex;
    }

    public @NotNull String resourceName() { return this.name; }
    public int bitIndex() { return this.bitIndex; }

    public static long @NotNull [] toBitSet(final @NotNull ResourceType @NotNull [] types) {
        int maxBit = 0;
        for (ResourceType t : types) maxBit = Math.max(maxBit, t.bitIndex);
        int words = (maxBit + 64) >>> 6;
        long[] bits = new long[words];
        for (ResourceType t : types) {
            bits[t.bitIndex >>> 6] |= (1L << (t.bitIndex & 63));
        }
        return bits;
    }

    public static boolean testBit(final long @NotNull [] bits, final int bitIndex) {
        int wi = bitIndex >>> 6;
        int bi = bitIndex & 63;
        return wi < bits.length && (bits[wi] & (1L << bi)) != 0;
    }

    public static boolean intersects(final long @NotNull [] a, final long @NotNull [] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) if ((a[i] & b[i]) != 0L) return true;
        return false;
    }
}
