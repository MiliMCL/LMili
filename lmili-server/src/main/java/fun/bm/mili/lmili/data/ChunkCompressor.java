package fun.bm.mili.lmili.data;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

/**
 * LZ4 compression/decompression wrapper for chunk sector data.
 * Used to compress data before writing to swap file and decompress on read.
 */
public class ChunkCompressor {
    private final LZ4Compressor lz4Compressor = LZ4Factory.fastestInstance().fastCompressor();
    private final LZ4FastDecompressor lz4Decompressor = LZ4Factory.fastestInstance().fastDecompressor();

    /**
     * Compress the input data and prepend the original length (4 bytes, big-endian).
     *
     * @param in the raw data to compress
     * @return a buffer containing [originalLength(int)][lz4CompressedData(bytes)]
     */
    public @NotNull ByteBuffer commitSectionData(@NotNull ByteBuffer in) {
        final int bufferLenToAllocate = this.lz4Compressor.maxCompressedLength(in.remaining());
        final ByteBuffer result = ByteBuffer.allocate(bufferLenToAllocate + 4);

        result.putInt(in.remaining());
        this.lz4Compressor.compress(in, result);

        return result.flip();
    }

    /**
     * Decompress data that was produced by {@link #commitSectionData(ByteBuffer)}.
     *
     * @param flippedIn buffer positioned at the start of committed section data
     * @return a buffer containing the original uncompressed data
     */
    public @NotNull ByteBuffer fromCommitedSection(@NotNull ByteBuffer flippedIn) {
        final int originalLen = flippedIn.getInt();
        final byte[] raw = new byte[flippedIn.remaining()];
        flippedIn.get(raw);

        final byte[] decompressed = new byte[originalLen];
        this.lz4Decompressor.decompress(raw, decompressed);

        return ByteBuffer.wrap(decompressed);
    }
}
