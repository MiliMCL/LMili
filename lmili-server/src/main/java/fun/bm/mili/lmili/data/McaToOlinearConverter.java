package fun.bm.mili.lmili.data;

import fun.bm.mili.lmili.utils.OptimizedLinearRegionFileFlusher;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Migrates a single legacy {@code .mca} (Anvil) region file to the {@code o_linear} format.
 *
 * <p>Used when the configured region format is {@code O_LINEAR} but an existing world still has
 * {@code .mca} region files. Each present chunk is read from the MCA file and written into a fresh
 * {@link OptimizedLinearRegionFile}; oversized chunks (whose entities/tile-entities were split out
 * into a side file by Paper) are merged back into a single NBT before being written, since
 * {@code o_linear} has no oversized-chunk concept.
 *
 * <p>The caller is responsible for staging to a temporary path and atomically publishing the
 * result, so a failed conversion never leaves a partially-written region behind.
 */
public final class McaToOlinearConverter {
    private static final int CHUNKS_PER_AXIS = 32;

    private McaToOlinearConverter() {
    }

    /**
     * Converts every present chunk of {@code mcaPath} into {@code olinearPath}.
     *
     * @param mcaPath         source Anvil region file (must already exist)
     * @param olinearPath     target o_linear region file (must not exist; typically a temp path)
     * @param info            region storage info, forwarded to the MCA reader
     * @param folder          region folder (used as the MCA external-chunk directory)
     * @param regionX         region X coordinate (file {@code r.<regionX>.<regionZ>}); needed so the
     *                        MCA reader resolves external {@code .mcc} and oversized side files by
     *                        their global chunk coordinates
     * @param regionZ         region Z coordinate
     * @param compressionLevel o_linear compression level (1..22)
     * @param flusher         o_linear background flusher (non-null when O_LINEAR is configured)
     * @return the number of chunks migrated
     */
    public static int convert(final Path mcaPath, final Path olinearPath, final RegionStorageInfo info,
                              final Path folder, final int regionX, final int regionZ,
                              final int compressionLevel,
                              final OptimizedLinearRegionFileFlusher flusher) throws IOException {
        int chunkCount = 0;

        // try-with-resources closes the target first (reverse declaration order), so the
        // OptimizedLinearRegionFile performs its final master-file sync before the MCA reader closes.
        try (final RegionFile mca = new RegionFile(info, mcaPath, folder, false);
             final OptimizedLinearRegionFile olinear = new OptimizedLinearRegionFile(olinearPath, compressionLevel, flusher)) {

            for (int cx = 0; cx < CHUNKS_PER_AXIS; cx++) {
                for (int cz = 0; cz < CHUNKS_PER_AXIS; cz++) {
                    // Global chunk coordinates: the MCA reader masks to a local index internally,
                    // but uses the raw coordinates for external/oversized side-file paths.
                    final ChunkPos pos = new ChunkPos((regionX << 5) + cx, (regionZ << 5) + cz);

                    if (!mca.doesChunkExist(pos)) {
                        continue;
                    }

                    final byte[] data;
                    if (mca.isOversized(pos.x(), pos.z())) {
                        data = readMergedOversizedChunk(mca, pos);
                    } else {
                        try (DataInputStream in = mca.getChunkDataInputStream(pos)) {
                            if (in == null) {
                                continue;
                            }
                            data = in.readAllBytes();
                        }
                    }

                    olinear.write(pos, ByteBuffer.wrap(data));
                    chunkCount++;
                }
            }
        }

        return chunkCount;
    }

    /**
     * Reconstructs the full chunk NBT for a Paper oversized chunk by merging the entities and
     * tile-entities side file back into the internal chunk data, then re-serializes it.
     */
    private static byte[] readMergedOversizedChunk(final RegionFile mca, final ChunkPos pos) throws IOException {
        final CompoundTag chunk;
        try (DataInputStream in = mca.getChunkDataInputStream(pos)) {
            if (in == null) {
                throw new IOException("Missing internal chunk data for oversized chunk " + pos);
            }
            chunk = NbtIo.read(in);
        }

        final CompoundTag oversizedData = mca.getOversizedData(pos.x(), pos.z());
        if (oversizedData != null) {
            final CompoundTag oversizedLevel = oversizedData.getCompoundOrEmpty("Level");
            mergeChunkList(chunk.getCompoundOrEmpty("Level"), oversizedLevel, "Entities", "Entities");
            mergeChunkList(chunk.getCompoundOrEmpty("Level"), oversizedLevel, "TileEntities", "TileEntities");
        }

        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            NbtIo.write(chunk, out);
        }
        return buffer.toByteArray();
    }

    private static void mergeChunkList(final CompoundTag level, final CompoundTag oversizedLevel,
                                       final String key, final String oversizedKey) {
        final ListTag levelList = level.getListOrEmpty(key);
        final ListTag oversizedList = oversizedLevel.getListOrEmpty(oversizedKey);

        if (!oversizedList.isEmpty()) {
            levelList.addAll(oversizedList);
            level.put(key, levelList);
        }
    }
}
