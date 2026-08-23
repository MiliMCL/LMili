package fun.bm.mili.lmili.data;

import abomination.IRegionFile;
import net.minecraft.world.level.ChunkPos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A ByteArrayOutputStream variant that, when closed, writes its accumulated data
 * as a chunk to the owning OptimizedLinearRegionFile.
 *
 * <p>Implements {@link ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemChunkBuffer}
 * to mirror {@code RegionFile.ChunkBuffer}: the Moonrise write path defers the actual chunk write
 * to the I/O stage via {@link #moonrise$write(IRegionFile)} (invoked from {@code finishWrite}),
 * while the legacy path ({@code getChunkDataOutputStream}) writes on {@link #close()}. This
 * prevents the same chunk from being written twice (once on {@code output.close()} during the
 * compress stage and once via the write callback in the I/O stage).
 */
public class ChunkBufferHelper extends ByteArrayOutputStream
        implements ca.spottedleaf.moonrise.patches.chunk_system.storage.ChunkSystemChunkBuffer {
    private final ChunkPos pos;
    private final OptimizedLinearRegionFile file;

    // Mili - single-write control, same as RegionFile.ChunkBuffer#writeOnClose.
    private boolean writeOnClose = true;

    ChunkBufferHelper(ChunkPos pos, OptimizedLinearRegionFile file) {
        this.pos = pos;
        this.file = file;
    }

    @Override
    public boolean moonrise$getWriteOnClose() {
        return this.writeOnClose;
    }

    @Override
    public void moonrise$setWriteOnClose(final boolean value) {
        this.writeOnClose = value;
    }

    @Override
    public void moonrise$write(final IRegionFile regionFile) throws IOException {
        // The owning OptimizedLinearRegionFile is always the region file passed by finishWrite
        // (one instance per region), so writing through `this.file` is correct and preserves the
        // package-private write path.
        this.writeData();
    }

    @Override
    public void close() throws IOException {
        // Mili - only write on close for the legacy (non-Moonrise) path. The Moonrise path sets
        // writeOnClose = false and defers to moonrise$write() to avoid a duplicate write.
        if (this.writeOnClose) {
            this.writeData();
        }
    }

    private void writeData() throws IOException {
        ByteBuffer bytebuffer = ByteBuffer.wrap(this.buf, 0, this.count);

        final int chunkIndex = getChunkIndex(this.pos.x(), this.pos.z());

        this.file.ensureBucketLoaded(chunkIndex);
        this.file.writeChunk(this.pos.x(), this.pos.z(), bytebuffer);
        this.file.flushInternal();
        this.file.makeBucketDirty(chunkIndex);
    }

    private static int getChunkIndex(int x, int z) {
        return (x & 31) + (z & 31) * 32;
    }
}
