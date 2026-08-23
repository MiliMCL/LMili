package fun.bm.mili.lmili.data;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Migrates a single {@code o_linear} region file back to the vanilla {@code .mca} (Anvil) format.
 *
 * <p>Used when the configured region format is {@code MCA} but a world previously converted to
 * {@code o_linear} still has {@code .o_linear} region files. Each present chunk is read from the
 * {@link OptimizedLinearRegionFile} and written into a fresh {@link RegionFile}; the MCA writer
 * re-compresses it with the vanilla Anvil compressor (gzip/zlib), so the output is a standard
 * region file.
 *
 * <p>Like the forward converter, the caller stages the output to a temporary path and atomically
 * publishes it, so a failed conversion never leaves a partially-written region behind.
 */
public final class OlinearToMcaConverter {
    private static final int CHUNKS_PER_AXIS = 32;

    private OlinearToMcaConverter() {
    }

    /**
     * Converts every present chunk of {@code olinearPath} into {@code mcaPath}.
     *
     * @param olinearPath      source o_linear region file (must already exist)
     * @param mcaPath          target Anvil region file (must not exist; typically a temp path)
     * @param info             region storage info, forwarded to the MCA writer
     * @param folder           region folder (used as the MCA external-chunk directory)
     * @param regionX          region X coordinate (file {@code r.<regionX>.<regionZ>}); used to emit
     *                         global chunk coordinates so oversized writes land in the correct
     *                         {@code .mcc} side file
     * @param regionZ          region Z coordinate
     * @param compressionLevel o_linear compression level, only used for header validation on the
     *                         read-only source open
     * @return the number of chunks migrated
     */
    public static int convert(final Path olinearPath, final Path mcaPath, final RegionStorageInfo info,
                              final Path folder, final int regionX, final int regionZ,
                              final int compressionLevel) throws IOException {
        int chunkCount = 0;

        // The source is opened read-only with a null flusher: MCA is the configured format at this
        // point, so the background o_linear flusher does not exist. close() then performs a no-op
        // final sync (the file is marked synced on construction when its master already exists).
        try (final OptimizedLinearRegionFile olinear = new OptimizedLinearRegionFile(olinearPath, compressionLevel, null);
             final RegionFile mca = new RegionFile(info, mcaPath, folder, false)) {

            for (int cx = 0; cx < CHUNKS_PER_AXIS; cx++) {
                for (int cz = 0; cz < CHUNKS_PER_AXIS; cz++) {
                    final ChunkPos pos = new ChunkPos((regionX << 5) + cx, (regionZ << 5) + cz);

                    if (!olinear.doesChunkExist(pos)) {
                        continue;
                    }

                    final byte[] data;
                    try (DataInputStream in = olinear.getChunkDataInputStream(pos)) {
                        if (in == null) {
                            continue;
                        }
                        data = in.readAllBytes();
                    }

                    // getChunkDataOutputStream wraps the buffer in the MCA compressor (version.wrap),
                    // producing the [length][version][compressed] layout on close.
                    try (DataOutputStream out = mca.getChunkDataOutputStream(pos)) {
                        out.write(data);
                    }
                    chunkCount++;
                }
            }
        }

        return chunkCount;
    }
}
