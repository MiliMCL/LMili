package fun.bm.mili.lmili.data;

import abomination.AbstractRegionFile;
import abomination.IRegionFile;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import fun.bm.mili.lmili.utils.BufferedLinearRegionFileFlusher;
import org.apache.commons.lang3.Validate;
import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BufferedLinearRegionFile extends AbstractRegionFile {
    private static final double SWAP_FILE_AUTO_COMPACT_PERCENT = 3.0 / 5.0; // 60 %
    private static final long SWAP_FILE_AUTO_COMPACT_SIZE = 1024 * 1024; // 1 MiB

    private static final long SWAP_FILE_SUPER_BLOCK = 0x1145141919810L;
    private static final int SWAP_FILE_HASH_SEED = 0x0721; // ～(∠・ω< )⌒★
    private static final byte SWAP_FILE_VERSION = 0x02; // ver 2.0

    private static final int BUCKET_SHIFT = 6;
    private static final int BUCKET_SIZE = 1 << BUCKET_SHIFT;
    private static final int BUCKET_COUNT = 1024 / BUCKET_SIZE;

    private static final StandardOpenOption[] SWAP_FILE_CHANNEL_OPTIONS = new StandardOpenOption[]{
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ,
            StandardOpenOption.DELETE_ON_CLOSE
    };

    private static final StandardOpenOption[] MASTER_TMP_FILE_CHANNEL_OPTIONS = new StandardOpenOption[]{
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
    };

    static final class Bucket {
        final Object lock = new Object();

        volatile boolean dirty = false;
        volatile boolean loaded = false;
    }

    final Bucket[] buckets = new Bucket[BUCKET_COUNT];

    private final Path masterFilePath;
    private final Path swapFilePath;

    private final ReadWriteLock regionObjectLock = new ReentrantReadWriteLock();
    private final XXHash32 xxHash32 = XXHashFactory.fastestInstance().hash32();
    private Sector[] sectors = new Sector[1024];
    private long currentAcquiredIndex = this.headerSize();
    private int xxHash32Seed = SWAP_FILE_HASH_SEED;
    private FileChannel swapFileChannel;

    private final byte compressionLevel;
    private final LinearFormatMigrator masterFileParser;
    private final ChunkCompressor compressingOps;

    // managed by VarHandles following
    private boolean closed = false;
    private boolean beingSynced = false;
    private boolean synced = false;
    private long lastWritten = System.nanoTime();

    private static final VarHandle CLOSED_HANDLE = ConcurrentUtil.getVarHandle(BufferedLinearRegionFile.class, "closed", boolean.class);
    private static final VarHandle SYNCED_HANDLE = ConcurrentUtil.getVarHandle(BufferedLinearRegionFile.class, "synced", boolean.class);
    private static final VarHandle BEING_SYNCED_HANDLE = ConcurrentUtil.getVarHandle(BufferedLinearRegionFile.class, "beingSynced", boolean.class);
    private static final VarHandle LAST_WRITTEN_HANDLE = ConcurrentUtil.getVarHandle(BufferedLinearRegionFile.class, "lastWritten", long.class);

    private final BufferedLinearRegionFileFlusher flusher;

    public BufferedLinearRegionFile(Path masterFilePath, int compressionLevel, @NotNull BufferedLinearRegionFileFlusher flusher) throws IOException {
        super(masterFilePath);
        this.masterFilePath = masterFilePath;
        this.swapFilePath = Path.of(this.masterFilePath.toString() + ".swp");

        for (int i = 0; i < this.buckets.length; i++) {
            this.buckets[i] = new Bucket();
        }

        Validate.inclusiveBetween(1, 22, compressionLevel);
        this.compressionLevel = (byte) compressionLevel;

        this.masterFileParser = new LinearFormatMigrator(this);
        this.compressingOps = new ChunkCompressor();

        this.cleanUpSwapFile();
        this.initSwapFile();
        this.tryLoadOldBlinearMasterFileData();

        this.flusher = flusher;

        this.flusher.addFile(this);
    }

    private void cleanUpSwapFile() throws IOException {
        Files.deleteIfExists(this.swapFilePath);
    }

    void ensureBucketLoaded(int chunkIndex) throws IOException {
        final int bucketIndex = chunkIndex >> BUCKET_SHIFT;
        final Bucket bucket = this.buckets[bucketIndex];

        // bucket lock -> master read lock -> swap write lock
        synchronized (bucket.lock) {
            if (bucket.loaded) {
                return;
            }

            this.masterFileParser.loadBucketsFor(this.masterFilePath, bucketIndex);
            bucket.loaded = true;
        }
    }

    void makeBucketDirty(int chunkIndex) {
        final int bucketIndex = chunkIndex >> BUCKET_SHIFT;
        final Bucket bucket = this.buckets[bucketIndex];

        bucket.dirty = true;
    }

    void markAsNotDirty(int bucketIndex) {
        final Bucket bucket = this.buckets[bucketIndex];

        bucket.dirty = false;
    }

    boolean isBucketDirty(int bucketIndex) {
        final Bucket bucket = this.buckets[bucketIndex];

        return bucket.dirty;
    }

    public boolean markAsBeingSynced() {
        return BEING_SYNCED_HANDLE.compareAndSet(this, false, true);
    }


    public long getLastWritten() {
        return (long) LAST_WRITTEN_HANDLE.getVolatile(this);
    }

    public boolean shouldSync() {
        return !((boolean) SYNCED_HANDLE.getVolatile(this));
    }

    public boolean softReadLock() {
        // not done close logic yet
        return this.regionObjectLock.readLock().tryLock();
    }

    public void releaseReadLock() {
        this.regionObjectLock.readLock().unlock();
    }

    public boolean isClosedRaw() {
        return (boolean) CLOSED_HANDLE.getVolatile(this);
    }

    public boolean isClosed() {
        this.regionObjectLock.readLock().lock();
        try {
            return (boolean) CLOSED_HANDLE.getVolatile(this);
        } finally {
            this.regionObjectLock.readLock().unlock();
        }
    }

    // Mili start - fix: Refactored syncIfNeeded to eliminate TOCTOU race with close().
    // The flusher now holds the readLock across the entire check-and-sync sequence,
    // so syncIfNeeded no longer acquires the readLock itself (reentrant, but unnecessary).
    public void syncIfNeeded() throws IOException {
        try {
            this.syncToMasterFile();
        } finally {
            BEING_SYNCED_HANDLE.setVolatile(this, false);
        }
    }
    // Mili end

    void syncToMasterFile() throws IOException {
        // prevent multiple syncs in the same time
        if (!SYNCED_HANDLE.compareAndSet(this, false, true)) {
            return;
        }

        try {
            this.masterFileParser.writeMainFileBucketed(this.masterFilePath);
        } catch (Throwable e) {
            // set back
            SYNCED_HANDLE.setVolatile(this, false);

            throw new IOException("Failed to sync to master file!", e);
        }
    }

    private void tryLoadOldBlinearMasterFileData() throws IOException {
        this.masterFileParser.tryParseMainFileOld(this.masterFilePath);
    }

    private void initSwapFile() throws IOException {
        this.swapFileChannel = FileChannel.open(
                this.swapFilePath,
                SWAP_FILE_CHANNEL_OPTIONS
        );

        // fill default sectors
        for (int i = 0; i < 1024; i++) {
            this.sectors[i] = new Sector(i, this.headerSize(), 0);
        }
    }

    private void recalculateAcquiredIndex() {
        long newValue = this.headerSize();

        for (Sector sector : this.sectors) {
            if (sector.hasData()) {
                newValue = Math.max(newValue, sector.getOffset() + sector.getLength());
            }
        }

        this.currentAcquiredIndex = newValue;
    }

    private void writeSwapFileHeaders(boolean forceFile, boolean forceMeta) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(this.headerSize());

        buffer.putLong(SWAP_FILE_SUPER_BLOCK); // Magic
        buffer.put(SWAP_FILE_VERSION); // Version
        buffer.putInt(this.xxHash32Seed); // XXHash32 seed
        buffer.putLong(this.currentAcquiredIndex); // Acquired index

        for (Sector sector : this.sectors) {
            // encode each sector
            buffer.put(sector.getEncoded());
        }

        buffer.flip();

        long offset = 0;
        while (buffer.hasRemaining()) {
            offset += this.swapFileChannel.write(buffer, offset);
        }

        if (forceFile) {
            this.swapFileChannel.force(forceMeta);
        }
    }

    private int sectorSize() {
        return this.sectors.length * Sector.sizeOfSingle();
    }

    private int headerSize() {
        int result = 0;

        result += Long.BYTES; // Magic
        result += Byte.BYTES; // Version
        result += Integer.BYTES; // XXHash32 seed
        result += Long.BYTES; // Acquired index
        result += this.sectorSize(); // Sectors

        return result;
    }

    void flushInternal() throws IOException {
        boolean initiallySyncRequired;

        this.regionObjectLock.writeLock().lock();
        try {
            if (this.isClosedRaw()) {
                return;
            }

            long spareSize = this.currentAcquiredIndex;

            spareSize -= this.headerSize();
            for (Sector sector : this.sectors) {
                // skip no data sectors
                if (!sector.hasData()) {
                    continue;
                }

                spareSize -= sector.getLength();
            }

            long sectorSize = 0;
            for (Sector sector : this.sectors) {
                // skip no data sectors
                if (!sector.hasData()) {
                    continue;
                }

                sectorSize += sector.getLength();
            }

            final boolean compactRequested = spareSize > SWAP_FILE_AUTO_COMPACT_SIZE && (double) spareSize > ((double) sectorSize) * SWAP_FILE_AUTO_COMPACT_PERCENT;

            // try auto compact to clean the garbage area
            if (compactRequested) {
                // do compact
                this.compactSwapFile();
            }

            // prevent syncing after compact because it could be time costing sometimes
            initiallySyncRequired = !Files.exists(this.masterFilePath) && !compactRequested;
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }

        if (initiallySyncRequired) {
            this.syncToMasterFile();
        }
    }

    // Mili start - fix: Close race condition — syncIfNeeded() must run inside writeLock
    // to prevent concurrent sync/close from operating on the same channel simultaneously.
    private void closeInternal() throws IOException {
        this.regionObjectLock.writeLock().lock();
        try {
            if (this.isClosedRaw()) {
                return;
            }
            // Mark closed first so concurrent sync attempts abort immediately
            CLOSED_HANDLE.setVolatile(this, true);
            this.flusher.removeFile(this);

            // Perform final sync while holding writeLock for exclusivity
            try {
                this.syncToMasterFile();
            } catch (IOException e) {
                com.mojang.logging.LogUtils.getLogger()
                        .error("[BufferedLinearRegionFile] Final sync failed before close: {}",
                                this.masterFilePath, e);
            }

            this.swapFileChannel.close();
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }
    }
    // Mili end

    private void markClosed() throws IOException {
        if (!CLOSED_HANDLE.compareAndSet(this, false, true)) {
            throw new IOException("Already closed!");
        }

        this.flusher.removeFile(this);
    }

    private void compactSwapFile() throws IOException {
        this.writeSwapFileHeaders(true, true); // save headers for compact

        final Sector[] newSectorsToBeReplaced = new Sector[this.sectors.length];

        for (int i = 0; i < this.sectors.length; i++) {
            final Sector old = this.sectors[i];

            if (old.hasData()) {
                newSectorsToBeReplaced[i] = old;
                continue;
            }

            // note:
            // we reset length to 0 and this would make length <= newLength(which is >= 0) is always true.
            // so that the following write operation wouldn't override the data of other sectors
            // see the write method in Sector class
            newSectorsToBeReplaced[i] = new Sector(i, 0, 0);
        }

        long newAcquiredIndex;

        final Path targetTemp = new File(this.swapFilePath.toString() + ".tmp").toPath();

        try (FileChannel tempChannel = FileChannel.open(
                targetTemp,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            long offsetPointer = this.headerSize();
            tempChannel.position(offsetPointer);

            for (Sector sector : newSectorsToBeReplaced) {
                // skip cleared or no data-contained sectors
                if (!sector.hasData()) {
                    continue;
                }

                // transfer to target
                sector.transferTo(this.swapFileChannel, tempChannel);

                // recalculate the offset and length
                final Sector newRecalculated = new Sector(sector.getIndex(), offsetPointer, sector.getLength());
                newRecalculated.hasData = true;

                offsetPointer += sector.getLength();
                newSectorsToBeReplaced[sector.getIndex()] = newRecalculated; // update sector infos
            }

            tempChannel.force(true);

            newAcquiredIndex = offsetPointer;
        } catch (Throwable ex) {
            // recalculate acquired index
            this.recalculateAcquiredIndex();
            // delete the target temp file
            Files.deleteIfExists(targetTemp);
            // fast-fail
            // note: we don't block new write operations here as this is recoverable
            throw new IOException("Failed to compact swap file!", ex);
        }

        this.swapFileChannel.close();

        // replace swap file
        try {
            Files.move(
                    targetTemp,
                    this.swapFilePath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (Throwable e) {
            // atomic move might be unsupported on some file systems, so give it an attempt to retry without atomic move
            try {
                Files.move(
                        targetTemp,
                        this.swapFilePath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (Throwable ex) {
                // now we are totally failed
                e.addSuppressed(ex);

                // delete file that failed to replace
                Files.deleteIfExists(targetTemp);
                // recalculate acquired index
                this.recalculateAcquiredIndex();
                // reopen closed channel
                this.reopenSwapFileChannel();
                // fast-fail
                this.markClosed(); // prevent new writing & sync operations
                throw new IOException("Failed to replace original swap file!", e);
            }
        }


        try {
            // reopen file channel
            this.reopenSwapFileChannel();

            // replace with recalculated file headers
            this.sectors = newSectorsToBeReplaced;
            this.currentAcquiredIndex = newAcquiredIndex;

            // flush to file
            this.writeSwapFileHeaders(true, true);
        } catch (Throwable ex) {
            // we are totally failed here,
            // directly mark as closed as the swap file is already replaced, and we failed to update the
            // data which is still in the memory
            //
            // which means we might write any data into any incorrect indexed sectors which will blow the whole data
            this.markClosed();
            throw new IOException(ex);
        }
    }

    private void reopenSwapFileChannel() throws IOException {
        if (this.swapFileChannel.isOpen()) {
            this.swapFileChannel.close();
        }

        this.swapFileChannel = FileChannel.open(
                this.swapFilePath,
                SWAP_FILE_CHANNEL_OPTIONS
        );
    }

    void writeChunkDataRaw(int chunkOrdinal, ByteBuffer chunkData, boolean skipSync) throws IOException {
        final ByteBuffer committed = this.compressingOps.commitSectionData(chunkData); // run compression out of lock

        this.regionObjectLock.writeLock().lock();
        try {
            final Sector sector = this.sectors[chunkOrdinal];

            sector.store(committed, this.swapFileChannel, this);
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }

        if (skipSync) {
            return;
        }

        this.markAsToSync();
    }

    @Nullable ByteBuffer readChunkDataRaw(int chunkOrdinal) throws IOException {
        final ByteBuffer raw;

        this.regionObjectLock.readLock().lock();
        try {
            final Sector sector = this.sectors[chunkOrdinal];

            if (!sector.hasData()) {
                return null;
            }

            raw = sector.read(this.swapFileChannel);
        } finally {
            this.regionObjectLock.readLock().unlock();
        }

        return this.compressingOps.fromCommitedSection(raw);
    }

    private void clearChunkData(int chunkOrdinal) throws IOException {
        this.ensureBucketLoaded(chunkOrdinal);

        this.regionObjectLock.writeLock().lock();
        try {
            final Sector sector = this.sectors[chunkOrdinal];

            sector.clear();
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }

        this.makeBucketDirty(chunkOrdinal);
        this.markAsToSync();
    }

    private void markAsToSync() {
        SYNCED_HANDLE.setVolatile(this, false); // mark as unsynced
        LAST_WRITTEN_HANDLE.setVolatile(this, System.nanoTime()); // update last written time
    }

    private boolean hasData(int chunkOrdinal) throws IOException {
        this.ensureBucketLoaded(chunkOrdinal);

        this.regionObjectLock.readLock().lock();
        try {
            return this.sectors[chunkOrdinal].hasData();
        } finally {
            this.regionObjectLock.readLock().unlock();
        }
    }

    void writeChunk(int x, int z, @NotNull ByteBuffer data) throws IOException {
        final int chunkIndex = getChunkIndex(x, z);

        final int oldPositionOfData = data.position();
        final int xxHash32OfData = this.xxHash32.hash(data, this.xxHash32Seed);
        data.position(oldPositionOfData);

        // uncompressed length(int) + timestamp(long) + xxhash32(int)
        final ByteBuffer chunkSectionBuilder = ByteBuffer.allocate(data.remaining() + 4 + 8 + 4);

        chunkSectionBuilder.putInt(data.remaining()); // Length(int)
        chunkSectionBuilder.putLong(System.currentTimeMillis()); // Timestamp(long)
        chunkSectionBuilder.putInt(xxHash32OfData); // xxHash32 of the original data(int)
        chunkSectionBuilder.put(data); // Data(bytes)
        chunkSectionBuilder.flip();

        this.writeChunkDataRaw(chunkIndex, chunkSectionBuilder, false);
    }

    private @Nullable ByteBuffer readChunk(int x, int z) throws IOException {
        final int chunkIndex = getChunkIndex(x, z);

        this.ensureBucketLoaded(chunkIndex);

        final ByteBuffer data = this.readChunkDataRaw(chunkIndex);

        if (data == null) {
            return null;
        }

        final int length = data.getInt(); // compressed length(int)
        final long timestamp = data.getLong(); // TODO use this timestamp(long) for something?
        final int dataXXHash32 = data.getInt(); // XXHash32 for validation(int)

        final IOException xxHash32CheckFailedEx = this.checkXXHash32(dataXXHash32, data);
        if (xxHash32CheckFailedEx != null) {
            throw xxHash32CheckFailedEx; // prevent from loading
        }

        return data;
    }

    private @Nullable IOException checkXXHash32(long originalXXHash32, @NotNull ByteBuffer input) {
        final int oldPositionOfInput = input.position();
        final int currentXXHash32 = this.xxHash32.hash(input, this.xxHash32Seed);
        input.position(oldPositionOfInput);

        if (originalXXHash32 != currentXXHash32) {
            return new IOException("XXHash32 check failed ! Expected: " + originalXXHash32 + ",but got: " + currentXXHash32);
        }

        return null;
    }

    @Override
    public DataInputStream getChunkDataInputStream(@NotNull ChunkPos pos) throws IOException {
        final ByteBuffer data = this.readChunk(pos.x(), pos.z());

        if (data == null) {
            return null;
        }

        return new DataInputStream(new ByteBufferInputStream(data));
    }

    @Override
    public boolean doesChunkExist(@NotNull ChunkPos pos) throws IOException {
        return this.hasData(getChunkIndex(pos.x(), pos.z()));
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) {
        return new DataOutputStream(new ChunkBufferHelper(pos, this));
    }

    @Override
    public void clear(@NotNull ChunkPos pos) throws IOException {
        this.clearChunkData(getChunkIndex(pos.x(), pos.z()));
    }

    @Override
    public boolean hasChunk(@NotNull ChunkPos pos) {
        try {
            return this.hasData(getChunkIndex(pos.x(), pos.z()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(@NotNull ChunkPos pos, ByteBuffer buf) throws IOException {

        final int chunkIndex = getChunkIndex(pos.x(), pos.z());

        this.ensureBucketLoaded(chunkIndex);

        this.writeChunk(pos.x(), pos.z(), buf);

        this.makeBucketDirty(chunkIndex);
    }

    // MCC 的玩意,这东西也用不上给Linear了()
    @Override
    public CompoundTag getOversizedData(int x, int z) {
        return null;
    }
    // MCC end

    @Override
    public MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(CompoundTag data, ChunkPos pos) {
        final DataOutputStream out = this.getChunkDataOutputStream(pos);

        return new MoonriseRegionFileIO.RegionDataController.WriteData(
                data, MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
                out, regionFile -> out.close()
        );
    }

    @Override
    public void flush() throws IOException {
        this.flushInternal();
    }

    @Override
    public void close() throws IOException {
        this.closeInternal();
    }

    // =====================================================================
    // Package-private accessors for extracted classes
    // =====================================================================

    long getCurrentAcquiredIndex() {
        return this.currentAcquiredIndex;
    }

    void advanceAcquiredIndex(long delta) {
        this.currentAcquiredIndex += delta;
    }

    byte getCompressionLevel() {
        return this.compressionLevel;
    }

    int getXxHash32Seed() {
        return this.xxHash32Seed;
    }

    Bucket[] getBuckets() {
        return this.buckets;
    }

    // =====================================================================
    // Retained inner class: simple data container
    // =====================================================================

    // Bucket is declared at the top of this class (lines 64-70)

    // =====================================================================
    // Retained static nested class: independent utility
    // =====================================================================

    public static class ByteBufferInputStream extends InputStream {
        protected final ByteBuffer internal;

        public ByteBufferInputStream(ByteBuffer buf) {
            this.internal = buf;
        }

        @Override
        public int available() {
            return this.internal.remaining();
        }

        @Override
        public int read() throws IOException {
            return this.internal.hasRemaining() ? (this.internal.get() & 0xFF) : -1;
        }

        @Override
        public int read(byte @NotNull [] bytes, int off, int len) throws IOException {
            if (!this.internal.hasRemaining()) return -1;
            len = Math.min(len, this.internal.remaining());
            this.internal.get(bytes, off, len);
            return len;
        }
    }
}
