package fun.bm.mili.lmili.data;

import net.minecraft.world.level.ChunkPos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A ByteArrayOutputStream variant that, when closed, writes its accumulated data
 * as a chunk to the owning BufferedLinearRegionFile.
 */
public class ChunkBufferHelper extends ByteArrayOutputStream {
    private final ChunkPos pos;
    private final BufferedLinearRegionFile file;

    ChunkBufferHelper(ChunkPos pos, BufferedLinearRegionFile file) {
        this.pos = pos;
        this.file = file;
    }

    @Override
    public void close() throws IOException {
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
