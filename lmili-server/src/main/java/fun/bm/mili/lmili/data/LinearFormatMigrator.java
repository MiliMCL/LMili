package fun.bm.mili.lmili.data;

import abomination.AbstractRegionFile;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;
import net.openhft.hashing.LongHashFunction;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Handles parsing, migrating, and writing the master linear region file format.
 * Supports reading legacy formats (Linear V1, V2, BLinear V2) and the current V3 bucketed format,
 * as well as writing the V3 format.
 */
public class LinearFormatMigrator {

    private static final long MASTER_FILE_SUPER_BLOCK = -0x200812250269L;
    private static final byte MASTER_FILE_VERSION = 0x02; // ver 2.0
    private static final byte MASTER_FILE_VERSION_BUCKET = 0x03; // ver 3.0

    private static final int BUCKET_SHIFT = 6;
    private static final int BUCKET_SIZE = 1 << BUCKET_SHIFT;
    private static final int BUCKET_COUNT = 1024 / BUCKET_SIZE;

    // V3 new format layout:
    //   [0,  14): header  — superblock(8) + version(1) + compressionLevel(1) + xxHash32Seed(4)
    //   [14, 142): position table — BUCKET_COUNT(16) × long(8) each; 0 = no data for that bucket
    //   [142, EOF): bucket data — originalLen(int) + compressedLen(int) + compressedData
    private static final long V3_POS_TABLE_OFFSET = 14L;
    private static final int V3_POS_TABLE_SIZE = BUCKET_COUNT * Long.BYTES; // 128
    private static final long V3_DATA_AREA_OFFSET = V3_POS_TABLE_OFFSET + V3_POS_TABLE_SIZE; // 142

    private final ReadWriteLock masterFileLock = new ReentrantReadWriteLock();

    private final BufferedLinearRegionFile file;

    /**
     * @param file the owning BufferedLinearRegionFile instance whose data this migrator operates on
     */
    public LinearFormatMigrator(@NotNull BufferedLinearRegionFile file) {
        this.file = file;
    }

    /**
     * Write the current swap file data to the master file in V3 bucketed format.
     * Non-dirty buckets are copied from the existing master file if present;
     * dirty buckets are re-compressed and written.
     *
     * @param mainFile path to the master file
     */
    public void writeMainFileBucketed(@NotNull Path mainFile) throws IOException {
        final Path tmpFilePath = Path.of(mainFile + ".tmp");
        final boolean[] syncedBuckets = new boolean[BUCKET_COUNT];
        final long[] newPositionTable = new long[BUCKET_COUNT];

        // note: there is no necessary hold the write lock for this stuff
        // as the truly write operations only happens on replacing the master file (see the file move call below this hunk)
        // and we had CAS flags to prevent multiple synchronization happening at the same time

        // Open old file to copy non-dirty buckets
        long[] oldPositionTable = null;
        FileChannel oldChannel = null;

        this.masterFileLock.writeLock().lock();
        try {
            if (Files.exists(mainFile)) {
                try {
                    oldChannel = FileChannel.open(mainFile, StandardOpenOption.READ);
                    if (oldChannel.size() >= V3_DATA_AREA_OFFSET) {
                        final ByteBuffer hdr = ByteBuffer.allocate(14);
                        readFullyAt(oldChannel, hdr, 0);
                        hdr.flip();
                        if (hdr.getLong() == MASTER_FILE_SUPER_BLOCK && hdr.get() == MASTER_FILE_VERSION_BUCKET) {
                            oldPositionTable = this.parseOffsetTable(oldChannel);
                        } else {
                            oldChannel.close();
                            oldChannel = null;
                        }
                    } else {
                        oldChannel.close();
                        oldChannel = null;
                    }
                } catch (Throwable e) {
                    if (oldChannel != null) {
                        try {
                            oldChannel.close();
                        } catch (IOException e2) {
                            e.addSuppressed(e2);
                        }
                    }

                    throw new RuntimeException(e);
                }
            }

            try (FileChannel outChannel = FileChannel.open(tmpFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                // Write header (14 bytes)
                final ByteBuffer header = ByteBuffer.allocate(14);
                header.putLong(MASTER_FILE_SUPER_BLOCK);
                header.put(MASTER_FILE_VERSION_BUCKET);
                header.put(this.file.getCompressionLevel());
                header.putInt(this.file.getXxHash32Seed());
                header.flip();
                writeFullyAt(outChannel, header, 0);

                // Write position table placeholder (all zeros, filled in at the end)
                writeFullyAt(outChannel, ByteBuffer.allocate(V3_POS_TABLE_SIZE), V3_POS_TABLE_OFFSET);

                long dataOffset = V3_DATA_AREA_OFFSET;

                for (int bucketIndex = 0; bucketIndex < BUCKET_COUNT; bucketIndex++) {
                    final boolean isBucketDirty = this.file.isBucketDirty(bucketIndex);

                    if (isBucketDirty) {
                        final int baseChunk = bucketIndex << BUCKET_SHIFT;
                        final ByteArrayOutputStream rawBuf = new ByteArrayOutputStream();
                        final DataOutputStream rawOut = new DataOutputStream(rawBuf);
                        boolean hasAny = false;

                        for (int i = 0; i < BUCKET_SIZE; i++) {
                            // swap read lock
                            final ByteBuffer data = this.file.readChunkDataRaw(baseChunk + i);

                            // note: null -> no data contained
                            if (data == null) {
                                rawOut.writeInt(0);
                            } else {
                                final byte[] arr = new byte[data.remaining()];
                                data.get(arr);
                                rawOut.writeInt(arr.length);
                                rawOut.write(arr);
                                hasAny = true;
                            }
                        }
                        rawOut.flush();

                        if (hasAny) {
                            final byte[] raw = rawBuf.toByteArray();
                            final byte[] compressed = Zstd.compress(raw, this.file.getCompressionLevel());

                            newPositionTable[bucketIndex] = dataOffset;

                            final ByteBuffer bucketBuf = ByteBuffer.allocate(8 + compressed.length);
                            bucketBuf.putInt(raw.length);        // original (uncompressed) length
                            bucketBuf.putInt(compressed.length); // compressed length
                            bucketBuf.put(compressed);
                            bucketBuf.flip();
                            writeFullyAt(outChannel, bucketBuf, dataOffset);
                            dataOffset += bucketBuf.limit();
                        }
                        // else: newPositionTable[bucketIndex] stays 0

                        syncedBuckets[bucketIndex] = true;
                    } else {
                        // Not dirty: copy bytes from old file if available
                        if (oldPositionTable != null && oldPositionTable[bucketIndex] != 0) {
                            final long oldOffset = oldPositionTable[bucketIndex];
                            final ByteBuffer lensBuf = ByteBuffer.allocate(8);
                            readFullyAt(oldChannel, lensBuf, oldOffset);
                            lensBuf.flip();
                            lensBuf.getInt(); // skip originalLen
                            final int compressedLen = lensBuf.getInt();

                            final long bucketTotalSize = 8L + compressedLen;
                            newPositionTable[bucketIndex] = dataOffset;
                            outChannel.position(dataOffset);
                            long remaining = bucketTotalSize;
                            long srcPos = oldOffset;
                            while (remaining > 0) {
                                final long transferred = oldChannel.transferTo(srcPos, remaining, outChannel);
                                srcPos += transferred;
                                remaining -= transferred;
                            }
                            dataOffset += bucketTotalSize;
                        }
                    }
                }

                // Write the finalized position table
                final ByteBuffer posTableBuf = ByteBuffer.allocate(V3_POS_TABLE_SIZE);
                for (final long pos : newPositionTable) {
                    posTableBuf.putLong(pos);
                }
                posTableBuf.flip();
                writeFullyAt(outChannel, posTableBuf, V3_POS_TABLE_OFFSET);

                outChannel.force(true);
            } finally {
                if (oldChannel != null) {
                    oldChannel.close();
                }
            }

            try {
                Files.move(tmpFilePath, mainFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable e) {

                try {
                    Files.move(tmpFilePath, mainFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable ex) {
                    Files.deleteIfExists(tmpFilePath);

                    e.addSuppressed(ex);

                    throw new IOException("Failed to replace master file!", e);
                }
            }
        } finally {
            this.masterFileLock.writeLock().unlock();
        }

        for (int i = 0; i < syncedBuckets.length; i++) {
            if (syncedBuckets[i]) {
                this.file.markAsNotDirty(i);
            }
        }
    }

    /**
     * Load bucket data from the master file for the specified bucket index.
     *
     * @param file        path to the master file
     * @param bucketIndex the bucket index to load
     */
    public void loadBucketsFor(Path file, int bucketIndex) throws IOException {
        final int beginChunkIndex = bucketIndex << BUCKET_SHIFT;

        this.masterFileLock.readLock().lock();

        try {
            if (!Files.exists(file)) {
                return;
            }

            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                if (channel.size() < V3_DATA_AREA_OFFSET) {
                    return;
                }

                final ByteBuffer headerBuf = ByteBuffer.allocate(14);
                readFullyAt(channel, headerBuf, 0);
                headerBuf.flip();

                final long superblock = headerBuf.getLong();
                if (superblock != MASTER_FILE_SUPER_BLOCK)
                    throw new IOException("Invalid superblock " + superblock + "!");

                final byte version = headerBuf.get();
                if (version != MASTER_FILE_VERSION_BUCKET)
                    throw new IOException("Unknown version: " + version);

                // compressionLevel and hashSeed consumed but not used here
                headerBuf.get();
                headerBuf.getInt();

                final long[] posTable = this.parseOffsetTable(channel);

                // New format: jump directly to bucket data
                final long bucketDataOffset = posTable[bucketIndex];
                if (bucketDataOffset == 0) return;

                final ByteBuffer lensBuf = ByteBuffer.allocate(8);
                readFullyAt(channel, lensBuf, bucketDataOffset);
                lensBuf.flip();
                final int originalLen = lensBuf.getInt();
                final int compressedLen = lensBuf.getInt();

                final byte[] compressedData = new byte[compressedLen];
                readFullyAt(channel, ByteBuffer.wrap(compressedData), bucketDataOffset + 8);

                final ByteBuffer decompressed = ByteBuffer.wrap(Zstd.decompress(compressedData, originalLen));
                this.loadChunksFromBucketData(decompressed, beginChunkIndex);
            }
        } finally {
            this.masterFileLock.readLock().unlock();
        }
    }

    private long @NonNull [] parseOffsetTable(FileChannel channel) throws IOException {
        final ByteBuffer buf = ByteBuffer.allocate(V3_POS_TABLE_SIZE);
        readFullyAt(channel, buf, V3_POS_TABLE_OFFSET);
        buf.flip();

        final long[] table = new long[BUCKET_COUNT];

        Arrays.fill(table, 0L);
        for (int i = 0; i < BUCKET_COUNT; i++) {
            final long pos = buf.getLong();
            table[i] = pos;
        }

        return table;
    }

    private void loadChunksFromBucketData(ByteBuffer decompressed, int beginChunkIndex) throws IOException {
        for (int chunkIndex = beginChunkIndex; chunkIndex < beginChunkIndex + BUCKET_SIZE; chunkIndex++) {
            final int chunkSectionDataSize = decompressed.getInt();
            if (chunkSectionDataSize <= 0) continue;

            final byte[] chunkSectionData = new byte[chunkSectionDataSize];
            decompressed.get(chunkSectionData);

            this.file.writeChunkDataRaw(chunkIndex, ByteBuffer.wrap(chunkSectionData), true);
        }
    }

    private static void writeFullyAt(FileChannel channel, @NonNull ByteBuffer buf, long startOffset) throws IOException {
        long offset = startOffset;
        while (buf.hasRemaining()) {
            offset += channel.write(buf, offset);
        }
    }

    private static void readFullyAt(FileChannel channel, @NonNull ByteBuffer buf, long startOffset) throws IOException {
        long offset = startOffset;
        while (buf.hasRemaining()) {
            final int read = channel.read(buf, offset);
            if (read < 0) throw new EOFException("Unexpected EOF at offset " + offset);
            offset += read;
        }
    }

    private void parseLinearV2(@NonNull DataInputStream ioStream, Path file) throws IOException {
        ioStream.readLong(); // Skip newestTimestamp (Long)

        byte gridSize = ioStream.readByte();
        if (gridSize != 1 && gridSize != 2 && gridSize != 4 && gridSize != 8 && gridSize != 16 && gridSize != 32)
            throw new RuntimeException("Invalid grid size: " + gridSize + " file " + file);
        int bucketSize = 32 / gridSize;

        ioStream.readInt(); // Skip region_x (Int)
        ioStream.readInt(); // Skip region_z (Int)

        ioStream.skipBytes(128); // Skip existence bitmap

        // Skip NBT features
        while (true) {
            byte featureNameLength = ioStream.readByte();
            if (featureNameLength == 0) break;
            byte[] featureNameBytes = new byte[featureNameLength];
            ioStream.readFully(featureNameBytes);
            ioStream.readInt(); // featureValue
        }

        // Read bucket metadata
        int totalBuckets = gridSize * gridSize;
        int[] bucketSizes = new int[totalBuckets];
        byte[] bucketCompressionLevels = new byte[totalBuckets];
        long[] bucketHashes = new long[totalBuckets];
        for (int i = 0; i < totalBuckets; i++) {
            bucketSizes[i] = ioStream.readInt();
            bucketCompressionLevels[i] = ioStream.readByte();
            bucketHashes[i] = ioStream.readLong();
        }

        // Read and decompress each bucket, load chunks into swap
        for (int bx = 0; bx < gridSize; bx++) {
            for (int bz = 0; bz < gridSize; bz++) {
                int bucketIdx = bx * gridSize + bz;

                if (bucketSizes[bucketIdx] <= 0) continue;

                byte[] compressedBucket = new byte[bucketSizes[bucketIdx]];
                ioStream.readFully(compressedBucket);

                long rawHash = LongHashFunction.xx().hashBytes(compressedBucket);
                if (rawHash != bucketHashes[bucketIdx]) {
                    throw new IOException("Region file hash incorrect for bucket " + bucketIdx + " in " + file);
                }

                ByteArrayInputStream bucketByteStream = new ByteArrayInputStream(compressedBucket);
                ZstdInputStream zstdStream = new ZstdInputStream(bucketByteStream);
                ByteBuffer bucketBuffer = ByteBuffer.wrap(zstdStream.readAllBytes());
                zstdStream.close();

                for (int cx = 0; cx < bucketSize; cx++) {
                    for (int cz = 0; cz < bucketSize; cz++) {
                        int chunkX = bx * bucketSize + cx;
                        int chunkZ = bz * bucketSize + cz;
                        int chunkIndex = chunkX + chunkZ * 32;

                        int chunkSize = bucketBuffer.getInt();
                        long timestamp = bucketBuffer.getLong();

                        if (chunkSize > 0) {
                            // chunkSize includes the 8 bytes of timestamp already written
                            int dataLen = chunkSize - 8;
                            byte[] chunkData = new byte[dataLen];
                            bucketBuffer.get(chunkData);

                            // Mark bucket as loaded and dirty so it gets synced to new master format
                            final int blinearBucketIndex = chunkIndex >> BUCKET_SHIFT;
                            final BufferedLinearRegionFile.Bucket bucket = this.file.getBuckets()[blinearBucketIndex];

                            bucket.dirty = true;

                            synchronized (bucket.lock) {
                                bucket.loaded = true;
                            }

                            // Use writeChunk to go through the full path (adds length + timestamp + xxhash header)
                            this.file.writeChunk(chunkX, chunkZ, ByteBuffer.wrap(chunkData));
                        }
                    }
                }
            }
        }

        // Footer validation
        long footerSuperBlock = ioStream.readLong();
        if (footerSuperBlock != AbstractRegionFile.LINEAR_FILE_SUPER_BLOCK) {
            throw new IOException("Footer superblock invalid " + file);
        }
    }

    private boolean tryParseBlinearV2(@NotNull DataInputStream ioStream, Path file) throws IOException {
        final byte version = ioStream.readByte();

        // we will parse dynamically (V3)
        if (version == MASTER_FILE_VERSION_BUCKET) {
            ioStream.close();
            return false;
        }

        if (version != MASTER_FILE_VERSION)
            throw new RuntimeException("Invalid version: " + version + " in " + file);

        // Skip newestTimestamp (Long) + Compression level (Byte): Unused.
        ioStream.skipBytes(9);

        try (final ZstdInputStream decompressStream = new ZstdInputStream(ioStream)) {
            // only used as a helper stream
            // the parent stream will be closed in the try-catch block upper
            final DataInputStream decompressedStreamHelper = new DataInputStream(decompressStream);

            for (int index = 0; index < 1024; index++) {
                int size = decompressedStreamHelper.readInt(); // len

                if (size > 0) {
                    byte[] sectorData = new byte[size];
                    decompressedStreamHelper.readFully(sectorData, 0, size); // data

                    final ByteBuffer sectorDataNioBuffer = ByteBuffer.wrap(sectorData);

                    final int bucketIndex = index >> BUCKET_SHIFT;
                    final BufferedLinearRegionFile.Bucket bucket = this.file.getBuckets()[bucketIndex];

                    synchronized (bucket.lock) {
                        bucket.loaded = true;
                    }

                    bucket.dirty = true;

                    this.file.writeChunkDataRaw(index, sectorDataNioBuffer, false);
                }
            }
        }

        return true;
    }

    @Contract(value = "_ -> new", pure = true)
    public static int @NotNull [] coordinatesFromOrdinal(int chunkIndex) {
        int x = chunkIndex & 31;
        int z = (chunkIndex >> 5) & 31;
        return new int[]{x, z};
    }

    private void parseLinearV1(@NotNull DataInputStream ioStream) throws IOException {
        // Skip newestTimestamp (Long) + Compression level (Byte) + Chunk count (Short): Unused.
        ioStream.skipBytes(11);
        // Skip chunk data len(Int)(Unused).
        ioStream.skipBytes(4);
        // Skip data hash (Long): Unused.
        ioStream.skipBytes(8);

        try (final ZstdInputStream decompressedStream = new ZstdInputStream(ioStream)) {
            // only used as a helper stream
            // the parent stream will be closed in the try-catch block upper
            final DataInputStream bufferHelper = new DataInputStream(decompressedStream);

            final int[] chunkStarts = new int[1024];
            for (int i = 0; i < 1024; i++) {
                chunkStarts[i] = bufferHelper.readInt();
                bufferHelper.skipBytes(4); // Skip timestamps (Int): Unused.
            }

            for (int i = 0; i < 1024; i++) {
                if (chunkStarts[i] > 0) {
                    int size = chunkStarts[i];
                    byte[] chunkData = new byte[size];
                    bufferHelper.readFully(chunkData);

                    final ByteBuffer chunkDataNioBuffer = ByteBuffer.wrap(chunkData);

                    final int[] posByAxis = coordinatesFromOrdinal(i);

                    final int x = posByAxis[0];
                    final int z = posByAxis[1];


                    final int bucketIndex = i >> BUCKET_SHIFT;
                    final BufferedLinearRegionFile.Bucket bucket = this.file.getBuckets()[bucketIndex];

                    bucket.dirty = true;

                    this.file.writeChunk(x, z, chunkDataNioBuffer);

                    synchronized (bucket.lock) {
                        bucket.loaded = true;
                    }
                }
            }
        }
    }

    /**
     * Attempt to parse an old-format master file and migrate its data into the swap file.
     * Supports Linear V1, V2, and BLinear V2 formats.
     *
     * @param mainFilePath path to the master file
     */
    public void tryParseMainFileOld(@NotNull Path mainFilePath) throws IOException {
        final File file = mainFilePath.toFile();

        if (!file.exists() || !file.canRead()) {
            return;
        }

        // those streams will be closed in the parse logic, or we will close it manually
        final FileInputStream fileStream = new FileInputStream(file);
        final DataInputStream rawDataStream = new DataInputStream(fileStream);

        boolean oldParsed = false;
        final long superBlock;
        try {
            superBlock = rawDataStream.readLong();

            if (superBlock == MASTER_FILE_SUPER_BLOCK) {
                oldParsed = this.tryParseBlinearV2(rawDataStream, mainFilePath);

                // false -> v3 -> closed in parse block
                if (!oldParsed) {
                    return;
                }
            }

            if (superBlock == AbstractRegionFile.LINEAR_FILE_SUPER_BLOCK) {
                final byte version = rawDataStream.readByte();

                if (version == 1 || version == 2) {
                    this.parseLinearV1(rawDataStream);

                    oldParsed = true;
                }

                if (version == 3) {
                    this.parseLinearV2(rawDataStream, mainFilePath);

                    oldParsed = true;
                }
            }

        } catch (Throwable ex) {
            try {
                rawDataStream.close();
            } catch (IOException ex2) {
                ex.addSuppressed(ex2);
            }

            throw new IOException("Failed to parse master file: " + mainFilePath, ex);
        }

        // old parsed, remove the original file, and we will recreate it as we sync
        if (oldParsed) {
            // immediately do sync operation
            this.file.syncToMasterFile();
            return;
        }

        // anyone non-matched, close stream and throw the error
        rawDataStream.close();

        throw new IOException("Unknown or unsupported super block : " + superBlock);
    }
}
