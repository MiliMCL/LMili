package fun.bm.mili.lmili.data;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

/**
 * LZ4 compression/decompression wrapper for chunk sector data.
 * Used to compress data before writing to swap file and decompress on read.
 *
 * <p>Optimizations:
 * <ul>
 *   <li>Reusable compression buffer: commitSectionData result is consumed by Sector.store()
 *       (written to FileChannel and not retained), so a ThreadLocal buffer avoids repeated
 *       allocations on the write path.</li>
 *   <li>Fast-path for array-backed ByteBuffer: avoids intermediate byte[] allocation
 *       when the source ByteBuffer has a backing array.</li>
 * </ul>
 */
public class ChunkCompressor {
    private final LZ4Compressor lz4Compressor = LZ4Factory.fastestInstance().fastCompressor();
    private final LZ4FastDecompressor lz4Decompressor = LZ4Factory.fastestInstance().fastDecompressor();

    // Mili start - ThreadLocal 压缩缓冲区复用
    // commitSectionData 结果仅在 Sector.store() 中消耗（写入 FileChannel），
    // 调用方不会长期持有，因此可以安全复用
    private final ThreadLocal<byte[]> compressBuffer = ThreadLocal.withInitial(() -> new byte[4096]);
    // Mili end

    /**
     * Compress the input data and prepend the original length (4 bytes, big-endian).
     *
     * @param in the raw data to compress
     * @return a buffer containing [originalLength(int)][lz4CompressedData(bytes)]
     */
    public @NotNull ByteBuffer commitSectionData(@NotNull ByteBuffer in) {
        final int inputLen = in.remaining();
        final int maxCompressedLen = this.lz4Compressor.maxCompressedLength(inputLen);
        final int totalLen = maxCompressedLen + 4;

        // 从 ThreadLocal 获取缓冲区，不足则扩容
        byte[] buffer = this.compressBuffer.get();
        if (buffer.length < totalLen) {
            // 按 2 倍增长以避免频繁扩容
            int newSize = Math.max(totalLen, buffer.length * 2);
            buffer = new byte[newSize];
            this.compressBuffer.set(buffer);
        }

        // 手动写入大端序 4 字节原始长度（比 putInt + 手动翻转更高效）
        buffer[0] = (byte) (inputLen >>> 24);
        buffer[1] = (byte) (inputLen >>> 16);
        buffer[2] = (byte) (inputLen >>> 8);
        buffer[3] = (byte) inputLen;

        // 压缩源数据到缓冲区
        final int compressedLen;
        if (in.hasArray()) {
            // array-backed 快速路径：直接使用底层数组
            final byte[] inArray = in.array();
            final int inArrayOffset = in.position() + in.arrayOffset();
            compressedLen = this.lz4Compressor.compress(inArray, inArrayOffset, inputLen, buffer, 4, maxCompressedLen);
        } else {
            // 非 array-backed ByteBuffer，需要先拷贝
            final byte[] inHeap = new byte[inputLen];
            in.get(inHeap);
            compressedLen = this.lz4Compressor.compress(inHeap, 0, inputLen, buffer, 4, maxCompressedLen);
        }

        return ByteBuffer.wrap(buffer, 0, compressedLen + 4);
    }

    /**
     * Decompress data that was produced by {@link #commitSectionData(ByteBuffer)}.
     *
     * <p>Note: 返回的 ByteBuffer 会被调用方长期持有（包装为 DataInputStream），
     * 因此每次必须分配新的 byte[]，不做缓冲区复用。
     * 优化点在于减少中间数组的分配次数。
     *
     * @param flippedIn buffer positioned at the start of committed section data
     * @return a buffer containing the original uncompressed data
     */
    public @NotNull ByteBuffer fromCommitedSection(@NotNull ByteBuffer flippedIn) {
        final int originalLen = flippedIn.getInt();

        // 解压目标数组（必须新分配，不可复用）
        final byte[] decompressed = new byte[originalLen];

        // 解压压缩数据
        if (flippedIn.hasArray()) {
            // array-backed 快速路径：直接使用底层数组，避免中间 byte[] 分配
            final byte[] src = flippedIn.array();
            final int srcOffset = flippedIn.position() + flippedIn.arrayOffset();
            this.lz4Decompressor.decompress(src, srcOffset, decompressed, 0, originalLen);
        } else {
            // 非 array-backed，需要拷贝到临时数组
            final byte[] src = new byte[flippedIn.remaining()];
            flippedIn.get(src);
            this.lz4Decompressor.decompress(src, 0, decompressed, 0, originalLen);
        }

        return ByteBuffer.wrap(decompressed, 0, originalLen);
    }
}
