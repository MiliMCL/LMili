package fun.bm.mili.lmili.data;

import abomination.AbstractRegionFile;
import abomination.IRegionFile;
import ca.spottedleaf.concurrentutil.util.ConcurrentUtil;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import fun.bm.mili.lmili.utils.OptimizedLinearRegionFileFlusher;
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

/**
 * Optimized buffered linear region file. Previously known as {@code BufferedLinearRegionFile}
 * ({@code b_linear}); renamed to {@code o_linear} ("Optimized Linear").
 *
 * <p>This format keeps chunk data in a grow-on-write swap file (LZ4-compressed per chunk) and
 * flushes a Zstd-compressed, bucketed master file in the background. It is the successor of the
 * removed LINEAR_V2 format and retains on-load migration from the legacy Linear V1/V2 and the
 * previous BLinear V2 master formats (see {@link LinearFormatMigrator}).
 *
 * <p>Hot-path optimizations over the original implementation:
 * <ul>
 *   <li><b>Thread-local XXHash32</b> — the shared {@link XXHash32} instance is stateful and must
 *       not be used concurrently; a shared instance caused spurious checksum failures during
 *       multi-threaded chunk reads/writes. Each I/O thread now gets its own instance.</li>
 *   <li><b>O(1) auto-compact accounting</b> — {@link #trackedSectorBytes} tracks the total bytes
 *       held by live sectors so {@link #flushInternal()} no longer scans all 1024 sectors twice on
 *       every chunk write.</li>
 *   <li><b>Reused header buffer</b> — the swap-file header buffer is allocated once instead of on
 *       every compact.</li>
 * </ul>
 *
 * <p><b>Concurrency model / single-writer guarantee.</b> Folia ticks regions in parallel, but a
 * chunk is owned by exactly one tick region at a time, so the same {@code ChunkPos} is never saved
 * by two threads simultaneously at the scheduling layer. This class additionally provides
 * defense-in-depth: every swap-file mutation ({@link Sector#store} and the append pointer
 * {@code currentAcquiredIndex}/{@link #trackedSectorBytes}) is performed under
 * {@code regionObjectLock.writeLock()}, and reads under {@code regionObjectLock.readLock()}.
 * Consequently, even if two threads wrote the same {@code ChunkPos} concurrently, their sector
 * writes would serialize on the write lock (last writer wins) rather than corrupt the file.
 * {@code close()} marks the file closed first, and {@link #writeChunkDataRaw} /
 * {@link #readChunkDataRaw} reject further I/O after that point.
 */
public class OptimizedLinearRegionFile extends AbstractRegionFile {
    private static final double SWAP_FILE_AUTO_COMPACT_PERCENT = 3.0 / 5.0; // 60 %
    private static final long SWAP_FILE_AUTO_COMPACT_SIZE = 1024 * 1024; // 1 MiB

    private static final long SWAP_FILE_SUPER_BLOCK = 0x1145141919810L;
    private static final int SWAP_FILE_HASH_SEED = 0x0721; // ～(∠・ω< )⌒★
    private static final byte SWAP_FILE_VERSION = 0x02; // ver 2.0

    private static final int BUCKET_SHIFT = 6;
    private static final int BUCKET_SIZE = 1 << BUCKET_SHIFT;
    private static final int BUCKET_COUNT = 1024 / BUCKET_SIZE;

    // Mili - swap-file header layout (byte offsets). The header is: magic(8) + version(1) +
    // xxHash32Seed(4) + acquiredIndex(8) + 1024 × sector(17). Sector entries are persisted after
    // every write so the swap file remains self-describing and can be recovered after a crash.
    private static final int SWAP_HEADER_MAGIC_SIZE = Long.BYTES;                                  // 8
    private static final int SWAP_HEADER_VERSION_SIZE = Byte.BYTES;                                // 1
    private static final int SWAP_HEADER_SEED_OFFSET = SWAP_HEADER_MAGIC_SIZE + SWAP_HEADER_VERSION_SIZE; // 9
    private static final int SWAP_HEADER_ACQUIRED_INDEX_OFFSET = SWAP_HEADER_SEED_OFFSET + Integer.BYTES;  // 13
    private static final int SWAP_HEADER_SECTOR_TABLE_OFFSET = SWAP_HEADER_ACQUIRED_INDEX_OFFSET + Long.BYTES; // 21

    // Mili - the swap file is no longer opened with DELETE_ON_CLOSE. It is a crash-recoverable
    // write-ahead copy of the region: on a graceful close it is deleted manually (after the final
    // master sync), and on a crash it is left behind so the constructor can recover the most recent
    // chunk data from it. See recoverSwapFileIfPresent().
    private static final StandardOpenOption[] SWAP_FILE_CHANNEL_OPTIONS = new StandardOpenOption[]{
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ
    };

    private static final StandardOpenOption[] MASTER_TMP_FILE_CHANNEL_OPTIONS = new StandardOpenOption[]{
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
    };

    // Mili start - fix/perf: XXHash32 instances are stateful and therefore unsafe to share across
    // threads. The previous shared instance raced during concurrent chunk I/O, occasionally
    // throwing spurious "XXHash32 check failed" errors. A ThreadLocal gives each worker an
    // independent instance with zero per-call allocation on the hot path.
    private static final ThreadLocal<XXHash32> XX_HASH32 = ThreadLocal.withInitial(
            () -> XXHashFactory.fastestInstance().hash32()
    );
    // Mili end

    static final class Bucket {
        final Object lock = new Object();

        volatile boolean dirty = false;
        volatile boolean loaded = false;
    }

    final Bucket[] buckets = new Bucket[BUCKET_COUNT];

    private final Path masterFilePath;
    private final Path swapFilePath;

    private final ReadWriteLock regionObjectLock = new ReentrantReadWriteLock();
    private Sector[] sectors = new Sector[1024];
    private long currentAcquiredIndex = this.headerSize();
    private int xxHash32Seed = SWAP_FILE_HASH_SEED;
    private FileChannel swapFileChannel;

    // Mili start - perf: running total of bytes held by live (hasData) sectors.
    // Maintained under regionObjectLock.writeLock() and used to compute swap-file garbage in O(1).
    private long trackedSectorBytes = 0;
    // Mili end

    // Mili start - perf: cached "master file exists" flag. flushInternal() previously called
    // Files.exists() on every chunk write to decide whether to force the very first master sync.
    // The flag is monotonic (set true once the master is written), so it can be read without I/O.
    private volatile boolean masterFileExists = false;
    // Mili end

    // Mili start - perf: reuse the swap-file header buffer (allocated once) across compacts.
    private final ByteBuffer swapFileHeaderBuffer = ByteBuffer.allocate(this.headerSize());
    // Mili end

    // Mili start - Stage A2 读取优化：gather read batch buffer 复用。
    // 每次 tryReadWithGather() 默认会 ByteBuffer.allocate(batchTotalBytes)，在线程局部回收可避免反复 GC 压力。
    // 仅在线性 Gather Read 开启时由 tryReadWithGather() 触达；关闭时本字段闲置但占用 1 个 ThreadLocal 槽位（<1KB）。
    // closeInternal() 末尾 BATCH_BUFFER.remove() 防 region 关闭后 ThreadLocal 仍持有 buffer。
    private final ThreadLocal<ByteBuffer> BATCH_BUFFER = new ThreadLocal<>();
    // Mili end

    // Mili start - Stage B 写路径优化：chunkSectionBuilder 复用。
    // 每次 writeChunk() 默认会 ByteBuffer.allocate(data.remaining() + 16)，在线程局部回收可避免反复 GC 压力。
    // 由 writeChunk() 在 regionObjectLock.writeLock() 外调（LZ4 压缩一样在锁外），无锁竞争。
    // closeInternal() 末尾 WRITE_BUFFER.remove() 防 region 关闭后 ThreadLocal 仍持有 buffer。
    private final ThreadLocal<ByteBuffer> WRITE_BUFFER = new ThreadLocal<>();
    // Mili end

    private final byte compressionLevel;
    private final LinearFormatMigrator masterFileParser;
    private final ChunkCompressor compressingOps;

    // managed by VarHandles following
    private boolean closed = false;
    private boolean beingSynced = false;
    private boolean synced = false;
    private long lastWritten = System.nanoTime();
    // Mili - time (nanoTime) of the last successful master-file sync; used by the flusher to force
    // a sync after a maximum age even when the region is being written to continuously, so a power
    // failure can only lose a bounded amount of recent data.
    private long lastSynced = System.nanoTime();

    private static final VarHandle CLOSED_HANDLE = ConcurrentUtil.getVarHandle(OptimizedLinearRegionFile.class, "closed", boolean.class);
    private static final VarHandle SYNCED_HANDLE = ConcurrentUtil.getVarHandle(OptimizedLinearRegionFile.class, "synced", boolean.class);
    private static final VarHandle BEING_SYNCED_HANDLE = ConcurrentUtil.getVarHandle(OptimizedLinearRegionFile.class, "beingSynced", boolean.class);
    private static final VarHandle LAST_WRITTEN_HANDLE = ConcurrentUtil.getVarHandle(OptimizedLinearRegionFile.class, "lastWritten", long.class);
    private static final VarHandle LAST_SYNCED_HANDLE = ConcurrentUtil.getVarHandle(OptimizedLinearRegionFile.class, "lastSynced", long.class);

    // Mili - nullable: the reverse migration (o_linear -> MCA) opens a source region file read-only
    // while MCA is the configured format, in which case the background flusher does not exist.
    private final @Nullable OptimizedLinearRegionFileFlusher flusher;

    public OptimizedLinearRegionFile(Path masterFilePath, int compressionLevel, @Nullable OptimizedLinearRegionFileFlusher flusher) throws IOException {
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

        // Mili start - crash recovery. If a swap file is left over from a crash (or an unclean
        // kill), it holds the most recent chunk writes (possibly newer than the master file).
        // Recover it first so no recent chunk data is lost; otherwise fall back to the master.
        this.masterFileExists = Files.exists(this.masterFilePath);
        final boolean recoveredFromSwap = this.recoverSwapFileIfPresent();

        if (!recoveredFromSwap) {
            // A failed recovery attempt may have marked buckets loaded/dirty; reset them so the
            // master is (lazily) reloaded correctly below instead of being skipped.
            for (final Bucket bucket : this.buckets) {
                bucket.loaded = false;
                bucket.dirty = false;
            }

            this.cleanUpSwapFile();
            this.initSwapFile();
            this.tryLoadOldBlinearMasterFileData();
        }
        // Mili end

        // Mili - a freshly-opened EXISTING file's swap is in sync with its master: the V3 format is
        // read lazily without introducing changes, and legacy formats were just migrated+synced.
        // Marking it synced avoids a redundant full master rewrite on close for read-only opens
        // (e.g. the o_linear -> MCA reverse migration). New files (no master yet) stay unsynced so
        // close() still creates an empty master.
        if (this.masterFileExists) {
            SYNCED_HANDLE.setVolatile(this, true);
        }

        this.flusher = flusher;

        if (this.flusher != null) {
            this.flusher.addFile(this);
        }
    }

    private void cleanUpSwapFile() throws IOException {
        Files.deleteIfExists(this.swapFilePath);
    }

    // Mili start - crash recovery. The swap file is a self-describing write-ahead copy of the
    // region (sector entries are persisted after every write), so on startup we can reconstruct
    // the most recent chunk data even when the master file is stale (i.e. the previous process
    // died before the background flusher synced). This closes the data-loss window that previously
    // existed between a chunk write and the next master sync.
    //
    // The swap is NOT necessarily a complete snapshot: after a clean close it is deleted and the
    // master is then loaded back lazily, so at any instant the swap only holds the chunks that were
    // read or written this session. Recovery therefore MERGES the swap with the master — for each
    // chunk, the swap's copy wins when present (it is never older than the master's), a deletion
    // tombstone clears the master's copy, and the master fills in the rest.
    //
    // Returns true when a crash-leftover swap was recovered and merged; false when there was none
    // or it was corrupt (in which case the caller falls back to the master alone).
    private boolean recoverSwapFileIfPresent() {
        if (!Files.exists(this.swapFilePath)) {
            return false;
        }

        // Phase A: read the entire crash-leftover swap into memory. We close (and then delete) the
        // file before rebuilding the working state so this works on platforms that lock open files.
        final byte[][] recoveredData = new byte[this.sectors.length][];
        final boolean[] recoveredCleared = new boolean[this.sectors.length];
        boolean hasRecoveredState = false;
        try (final FileChannel oldChannel = FileChannel.open(this.swapFilePath, StandardOpenOption.READ)) {
            final Sector[] recoveredSectors = this.readSwapHeaderIntoArray(oldChannel);
            for (int i = 0; i < recoveredSectors.length; i++) {
                if (recoveredSectors[i].hasData()) {
                    final ByteBuffer raw = recoveredSectors[i].read(oldChannel);
                    final byte[] arr = new byte[raw.remaining()];
                    raw.get(arr);
                    recoveredData[i] = arr;
                    hasRecoveredState = true;
                } else if (recoveredSectors[i].isCleared()) {
                    recoveredCleared[i] = true;
                    hasRecoveredState = true;
                }
            }
        } catch (Throwable ex) {
            this.discardCorruptSwapFile(ex, "read");
            return false;
        }

        // Nothing but the (empty) header: the previous session crashed before any chunk was read or
        // written, so the master is already the complete state. Discard the leftover and use it.
        if (!hasRecoveredState) {
            try {
                Files.deleteIfExists(this.swapFilePath);
            } catch (IOException ignored) {
                // The constructor's fallback (cleanUpSwapFile) will retry the deletion.
            }
            return false;
        }

        // Phase B: rebuild a fresh swap from the master, overlay the recovered (newer) chunks and
        // deletion tombstones, then persist the merged state back to the master so it becomes
        // durable immediately.
        try {
            this.cleanUpSwapFile();
            this.initSwapFile();
            this.tryLoadOldBlinearMasterFileData();
            this.forceLoadAllMasterBuckets();

            for (int i = 0; i < recoveredData.length; i++) {
                if (recoveredData[i] != null) {
                    this.storeRawSector(i, ByteBuffer.wrap(recoveredData[i]));
                } else if (recoveredCleared[i]) {
                    this.clearRawSector(i);
                }
            }

            for (final Bucket bucket : this.buckets) {
                bucket.dirty = true;
            }
            this.syncToMasterFile();
            return true;
        } catch (Throwable ex) {
            this.discardCorruptSwapFile(ex, "merge");
            return false;
        }
    }

    private void discardCorruptSwapFile(Throwable cause, String phase) {
        try {
            if (this.swapFileChannel != null && this.swapFileChannel.isOpen()) {
                this.swapFileChannel.close();
            }
        } catch (IOException closeFailure) {
            cause.addSuppressed(closeFailure);
        }
        this.swapFileChannel = null;
        try {
            Files.deleteIfExists(this.swapFilePath);
        } catch (IOException deleteFailure) {
            cause.addSuppressed(deleteFailure);
        }

        com.mojang.logging.LogUtils.getLogger()
                .warn("[OptimizedLinearRegionFile] Failed to {} crash-leftover swap file {}; " +
                        "falling back to master (recent writes may be lost)",
                        phase, this.swapFilePath, cause);
    }

    /**
     * Reads and validates the swap-file header, returning the recovered sector table. The seed is
     * applied to this instance (it matches the XXHash32 seed embedded in every chunk section).
     */
    private Sector[] readSwapHeaderIntoArray(final FileChannel channel) throws IOException {
        final long fileSize = channel.size();
        if (fileSize < this.headerSize()) {
            throw new IOException("Swap file too small (" + fileSize + " < header " + this.headerSize() + ")");
        }

        final ByteBuffer header = ByteBuffer.allocate(this.headerSize());
        long readOffset = 0;
        while (header.hasRemaining()) {
            final int read = channel.read(header, readOffset);
            if (read < 0) {
                throw new EOFException("Unexpected EOF while reading swap header");
            }
            readOffset += read;
        }
        header.flip();

        if (header.getLong() != SWAP_FILE_SUPER_BLOCK) {
            throw new IOException("Invalid swap superblock");
        }
        if (header.get() != SWAP_FILE_VERSION) {
            throw new IOException("Unsupported swap version");
        }

        this.xxHash32Seed = header.getInt();
        // Acquired index is not needed: the append pointer is recomputed from the live sectors.
        header.getLong();

        final Sector[] sectors = new Sector[this.sectors.length];
        for (int i = 0; i < sectors.length; i++) {
            final Sector sector = new Sector(i, 0, 0);
            sector.restoreFrom(header);
            if (sector.hasData() && sector.getOffset() + sector.getLength() > fileSize) {
                throw new IOException("Sector " + i + " points past EOF (corrupt swap header)");
            }
            sectors[i] = sector;
        }
        return sectors;
    }

    /**
     * Eagerly loads every master bucket into the swap, so that recovery can overlay the swap's
     * newer chunks on top of a complete copy of the master's data.
     */
    private void forceLoadAllMasterBuckets() throws IOException {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            this.ensureBucketLoaded(i << BUCKET_SHIFT);
        }
    }

    /**
     * Stores an already-compressed chunk section (recovered from a previous swap file) directly into
     * the given sector, bypassing re-compression.
     */
    private void storeRawSector(final int index, final ByteBuffer raw) throws IOException {
        this.regionObjectLock.writeLock().lock();
        try {
            if (this.isClosedRaw()) {
                throw new IOException("Region file already closed: " + this.masterFilePath);
            }
            this.sectors[index].store(raw, this.swapFileChannel, this);
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }
    }

    /**
     * Applies a recovered deletion tombstone: clears the sector and persists the tombstone so the
     * chunk is omitted when the merged state is next synced to the master.
     */
    private void clearRawSector(final int index) throws IOException {
        this.regionObjectLock.writeLock().lock();
        try {
            if (this.isClosedRaw()) {
                throw new IOException("Region file already closed: " + this.masterFilePath);
            }
            final Sector sector = this.sectors[index];
            if (sector.hasData()) {
                this.trackSectorBytes(-sector.getLength());
            }
            sector.clear();
            this.persistSectorMetadata(index);
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }
    }
    // Mili end

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

    // Mili start - flusher needs to release the claim when the write-timeout deadline has not yet
    // been reached, so the next check cycle can re-evaluate the same file.
    public void clearBeingSynced() {
        BEING_SYNCED_HANDLE.setVolatile(this, false);
    }
    // Mili end

    public long getLastWritten() {
        return (long) LAST_WRITTEN_HANDLE.getVolatile(this);
    }

    public long getLastSynced() {
        return (long) LAST_SYNCED_HANDLE.getVolatile(this);
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
        this.syncToMasterFile(false);
    }

    /**
     * @param forceOnWriteEnabledOverride {@code true} = override {@code linearForceOnEverySync=false}
     *   and force fsync on this call (used by close path to guarantee graceful-shutdown durability).
     *   {@code false} = honor config.
     */
    void syncToMasterFile(final boolean forceOnWriteEnabledOverride) throws IOException {
        // prevent multiple syncs in the same time
        if (!SYNCED_HANDLE.compareAndSet(this, false, true)) {
            return;
        }

        try {
            // Mili - Stage C1: pass override flag through to migrator for fsync control
            this.masterFileParser.writeMainFileBucketed(this.masterFilePath, forceOnWriteEnabledOverride);
            // Mili - the master now exists on disk; cache this so flushInternal() avoids a
            // Files.exists() syscall on every chunk write.
            this.masterFileExists = true;
            LAST_SYNCED_HANDLE.setVolatile(this, System.nanoTime());
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

        this.trackedSectorBytes = 0;

        // Mili - write the full header (magic/version/seed/acquired-index/sector table) immediately
        // so the swap file is self-describing from the very first byte. Without this, a crash before
        // the first compact would leave a zero magic and the next startup's recovery would reject an
        // otherwise-valid swap file.
        this.writeSwapFileHeaders(false, false);
    }

    private void recalculateAcquiredIndex() {
        long newValue = this.headerSize();
        long newTracked = 0;

        for (Sector sector : this.sectors) {
            if (sector.hasData()) {
                newValue = Math.max(newValue, sector.getOffset() + sector.getLength());
                newTracked += sector.getLength();
            }
        }

        this.currentAcquiredIndex = newValue;
        this.trackedSectorBytes = newTracked;
    }

    // Mili start - crash recovery: persist a single sector's 17-byte header entry after its data
    // has been written, so the swap file stays self-describing. The data write happens first (in
    // Sector.store) and the metadata second, so a crash between the two leaves the previous,
    // still-valid entry in place rather than one pointing at half-written data. Only ever invoked
    // under regionObjectLock.writeLock().
    void persistSectorMetadata(int index) throws IOException {
        final Sector sector = this.sectors[index];
        final ByteBuffer entry = sector.getEncoded(); // 17 bytes, flipped

        long offset = SWAP_HEADER_SECTOR_TABLE_OFFSET + (long) index * Sector.sizeOfSingle();
        while (entry.hasRemaining()) {
            offset += this.swapFileChannel.write(entry, offset);
        }

        // Also persist the append pointer so recovery does not have to rely on recalculating it
        // (recalculation is still done on recovery for safety with older swap files).
        final ByteBuffer acquired = ByteBuffer.allocate(Long.BYTES);
        acquired.putLong(this.currentAcquiredIndex);
        acquired.flip();
        offset = SWAP_HEADER_ACQUIRED_INDEX_OFFSET;
        while (acquired.hasRemaining()) {
            offset += this.swapFileChannel.write(acquired, offset);
        }
    }
    // Mili end

    private void writeSwapFileHeaders(boolean forceFile, boolean forceMeta) throws IOException {
        // Mili start - perf: reuse a single pre-allocated header buffer. This method is only ever
        // invoked under regionObjectLock.writeLock(), so reuse is safe.
        final ByteBuffer buffer = this.swapFileHeaderBuffer;
        buffer.clear();

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
        // Mili end

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

    // Mili start - perf: O(1) spare-size computation via trackedSectorBytes.
    void flushInternal() throws IOException {
        boolean initiallySyncRequired;

        this.regionObjectLock.writeLock().lock();
        try {
            if (this.isClosedRaw()) {
                return;
            }

            final long spareSize = this.currentAcquiredIndex - this.headerSize() - this.trackedSectorBytes;

            // Mili - Stage D2：compact 阈值改读 config（暴露调参旋钮）。
            // 默认值与原硬编码常量一致（1 MiB + 60%）。
            final long compactSizeThreshold =
                fun.bm.mili.config.modules.function.RegionFormatConfig.linearAutoCompactSizeBytes;
            final double compactPercentThreshold =
                fun.bm.mili.config.modules.function.RegionFormatConfig.linearAutoCompactPercent;

            final boolean compactRequested = spareSize > compactSizeThreshold
                    && (double) spareSize > ((double) this.trackedSectorBytes) * compactPercentThreshold;

            // try auto compact to clean the garbage area
            if (compactRequested) {
                // do compact
                this.compactSwapFile();
            }

            // prevent syncing after compact because it could be time costing sometimes
            // Mili - cached flag instead of a per-write Files.exists() syscall.
            initiallySyncRequired = !this.masterFileExists && !compactRequested;
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }

        if (initiallySyncRequired) {
            this.syncToMasterFile();
        }
    }
    // Mili end

    // Mili start - fix: 3-phase close to eliminate an ABBA deadlock.
    // The previous implementation held regionObjectLock.writeLock() across syncToMasterFile(),
    // which takes masterFileLock.writeLock() and then regionObjectLock.readLock() (per chunk).
    // The background flusher worker takes those same two locks in the opposite order
    // (masterFileLock.writeLock -> regionObjectLock.readLock), so close and a concurrent sync
    // could deadlock the server during shutdown. Phases:
    //   1) mark closed + deregister from the flusher (under writeLock),
    //   2) final sync (NO regionObjectLock held; lock order is masterFileLock -> regionObjectLock),
    //   3) close the swap channel (under writeLock, after all sync/read operations finished).
    private void closeInternal() throws IOException {
        // Phase 1: atomically mark closed and deregister from the flusher.
        this.regionObjectLock.writeLock().lock();
        try {
            if (this.isClosedRaw()) {
                return;
            }
            CLOSED_HANDLE.setVolatile(this, true);
            if (this.flusher != null) {
                this.flusher.removeFile(this);
            }
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }

        // Phase 2: final sync. Do NOT hold regionObjectLock here.
        // Mili - Stage C1: pass forceOverride=true to guarantee graceful-shutdown durability
        // regardless of linearForceOnEverySync setting (close must always fsync to be safe).
        try {
            this.syncToMasterFile(true);
        } catch (IOException e) {
            com.mojang.logging.LogUtils.getLogger()
                    .error("[OptimizedLinearRegionFile] Final sync failed before close: {}",
                            this.masterFilePath, e);
        }

        // Phase 3: close the swap channel once every sync/read has quiesced, then delete the swap
        // file. The swap is now a crash-recoverable file (no DELETE_ON_CLOSE), so it must be removed
        // manually after the final master sync; leaving it behind would cause a spurious "recovery"
        // (and a redundant full master rewrite) on the next open.
        this.regionObjectLock.writeLock().lock();
        try {
            this.swapFileChannel.close();
            Files.deleteIfExists(this.swapFilePath);
            // Mili - Stage A2：region 关闭后释放 ThreadLocal 持有的 batch buffer
            // (典型 region 文件在数 GB 世界中数百个，长期持有 ThreadLocal 槽位 + 256KB buffer 可能泄漏)。
            this.BATCH_BUFFER.remove();
            // Mili - Stage B：同上，清理 writeChunk() 池的 ThreadLocal 槽位
            this.WRITE_BUFFER.remove();
            // Mili end
        } finally {
            this.regionObjectLock.writeLock().unlock();
        }
    }
    // Mili end

    private void markClosed() throws IOException {
        if (!CLOSED_HANDLE.compareAndSet(this, false, true)) {
            throw new IOException("Already closed!");
        }

        if (this.flusher != null) {
            this.flusher.removeFile(this);
        }
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
            final Sector replacement = new Sector(i, 0, 0);
            if (old.isCleared()) {
                // Mili - preserve the deletion tombstone across compaction; otherwise a crash
                // before the next master sync would resurrect the cleared chunk.
                replacement.clear();
            }
            newSectorsToBeReplaced[i] = replacement;
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

            // Mili start - Stage D1：跳过 compactSwapFile 内的 tempChannel.force(true)。
            // 风险分析：tmp 文件 atomic rename 后由后续 syncToMasterFile / maxSyncAgeMs 兜底 fsync，
            // 进程崩溃时若 tmp 未落盘 → 旧 swap 仍完整 → 下次启动 recovery 正常。
            // 收益：每次 compact 节省 fsync 5-50ms（取决于存储设备）。
            // 仅当 O_LINEAR + linear_optimizations_enabled + linearForceOnEverySync == true 时执行 force。
            if (fun.bm.mili.config.modules.function.RegionFormatConfig.isLinearOptimizationsActive()
                && fun.bm.mili.config.modules.function.RegionFormatConfig.linearForceOnEverySync) {
                tempChannel.force(true);
            }
            // Mili end

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

            // Mili start - perf: recompute tracked sector bytes from the compacted layout.
            long newTracked = 0;
            for (Sector sector : newSectorsToBeReplaced) {
                if (sector.hasData()) {
                    newTracked += sector.getLength();
                }
            }
            this.trackedSectorBytes = newTracked;
            // Mili end

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
            // Mili - single-writer guarantee: after close() marks this file closed, no further
            // sector mutation may occur, otherwise a late write could race the channel close.
            if (this.isClosedRaw()) {
                throw new IOException("Region file already closed: " + this.masterFilePath);
            }

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
        this.regionObjectLock.readLock().lock();
        try {
            // Mili - refuse to read from a closed/being-closed region file.
            if (this.isClosedRaw()) {
                return null;
            }

            final Sector sector = this.sectors[chunkOrdinal];

            if (!sector.hasData()) {
                return null;
            }

            // Mili start - Stage A1 读取优化：相邻 sector 批量读（gather read）
            // 探测从 chunkOrdinal 向后物理连续的 sector，一次 FileChannel.read 拉整个 batch
            // 到堆 buffer（堆 buffer 触发 page cache），从中切出 chunkOrdinal 段返回。
            // 任何 IOException → fallback 到原 sector.read 路径（行为不变）。
            // 仅当 O_LINEAR + linear_optimizations_enabled + linearGatherReadEnabled == true 时启用。
            // （"切换 regionFormat = O_LINEAR 即自动生效"）
            if (fun.bm.mili.config.modules.function.RegionFormatConfig.isLinearOptimizationsActive()
                && fun.bm.mili.config.modules.function.RegionFormatConfig.linearGatherReadEnabled) {
                try {
                    final ByteBuffer gathered = this.tryReadWithGather(chunkOrdinal);
                    if (gathered != null) {
                        return this.compressingOps.fromCommitedSection(gathered);
                    }
                } catch (IOException ioe) {
                    // fallback: 原单 sector read
                    org.slf4j.LoggerFactory.getLogger(OptimizedLinearRegionFile.class)
                        .debug("[o_linear] gather read failed at ordinal {}, fallback to single-sector read: {}",
                            chunkOrdinal, ioe.toString());
                }
            }
            // Mili end

            return this.compressingOps.fromCommitedSection(sector.read(this.swapFileChannel));
        } finally {
            this.regionObjectLock.readLock().unlock();
        }
    }

    /**
     * 尝试 gather read：从 {@code startOrdinal} 开始，向后探测物理连续的 sector，
     * 一次 {@link FileChannel#read(ByteBuffer, long)} 拉整个 batch 并从 batch buffer 中切出
     * {@code startOrdinal} 段返回。
     *
     * <p><b>前提</b>：调用方必须已持有 {@link #regionObjectLock} 的 read lock；
     * 本方法不做锁获取。
     *
     * <p><b>失败模式</b>：
     * <ul>
     *   <li>关闭 / / chunkOrdinal 无数据 → 返回 null（让调用方走默认路径或返回 null）</li>
     *   <li>batch 总字节 0 / 或 batch 仅含 startOrdinal 自身 → 仍执行（单 sector read 等价）</li>
     *   <li>任何 {@link IOException}（含 partial read 切到 chunkOrdinal 段）→ 上抛，
     *   由 {@link #readChunkDataRaw} catch 后 fallback 到单 sector read</li>
     * </ul>
     *
     * <p><b>batch 探测规则</b>：
     * <ol>
     *   <li>仅向后（更高 ordinal）扩展；保证原 chunkOrdinal 段是 batch 的第一段</li>
     *   <li>下一 sector 必须 {@code hasData()} 且 {@code offset == prevOffset + prevLength}（物理连续）</li>
     *   <li>batch sector 数 ≤ {@link RegionFormatConfig#linearGatherReadBatchSize}</li>
     *   <li>batch 总字节累加 ≤ {@link RegionFormatConfig#linearGatherReadMaxBatchBytes}</li>
     * </ol>
     */
    @Nullable
    private ByteBuffer tryReadWithGather(final int startOrdinal) throws IOException {
        final Sector startSector = this.sectors[startOrdinal];
        if (!startSector.hasData()) {
            return null;
        }

        final int maxBatchSectors = Math.max(1,
            fun.bm.mili.config.modules.function.RegionFormatConfig.linearGatherReadBatchSize);
        final long maxBatchBytes = Math.max(
            (long) startSector.getLength(),
            fun.bm.mili.config.modules.function.RegionFormatConfig.linearGatherReadMaxBatchBytes);

        // 探测 batch 终点：第一个不连续 / hasData=false / 超过限额的 sector 即终止
        int batchEndOrdinal = startOrdinal; // exclusive end (next sector to consider)
        long batchTotalBytes = 0L;
        long expectedNextOffset = startSector.getOffset() + startSector.getLength();
        final int maxOrdinal = this.sectors.length;

        for (int probe = startOrdinal + 1;
             probe < maxOrdinal
                 && (probe - startOrdinal) < maxBatchSectors
                 && (batchTotalBytes + startSector.getLength()) < maxBatchBytes;
             probe++) {
            final Sector next = this.sectors[probe];
            if (!next.hasData()) {
                break;
            }
            if (next.getOffset() != expectedNextOffset) {
                break; // 物理不连续 → 终止
            }
            // 累加前一个 sector 的长度（probe 是被纳入的 sector，但其长度在下一轮才用）
            final Sector included = this.sectors[probe - 1];
            batchTotalBytes += included.getLength();
            expectedNextOffset = next.getOffset() + next.getLength();
            batchEndOrdinal = probe + 1;
        }
        // 累加最后纳入的 sector 长度
        if (batchEndOrdinal > startOrdinal) {
            batchTotalBytes += this.sectors[batchEndOrdinal - 1].getLength();
        } else {
            // 不可能（startSector 必有数据），但 defensive
            return null;
        }

        // 防御：batchTotalBytes 必须 >= startSector 长度
        if (batchTotalBytes < startSector.getLength()) {
            return null;
        }

        // 防御：(int) 转型溢出（maxBatchBytes 配置大于 Integer.MAX_VALUE 时静默溢出）。
        // ByteBuffer.allocate 接受 int，超过 Integer.MAX_VALUE 是配置错误，回退到原 sector.read。
        if (batchTotalBytes > Integer.MAX_VALUE) {
            return null;
        }

        // 堆 buffer（重要：堆 buffer 让 page cache 生效；direct buffer 可能绕过）
        // Mili - Stage A2：优先从 ThreadLocal 池中复用，避免重复 ByteBuffer.allocate。
        // 池 buffer 容量 = maxBatchBytes；本次所需 <= 容量 → 直接 wrap 复用；否则分配一次性 buffer（不归还）。
        final ByteBuffer batchBuf = this.acquireBatchBuffer((int) batchTotalBytes);
        try {
            int bytesRead = 0;
            while (bytesRead < batchTotalBytes && batchBuf.hasRemaining()) {
                final int n = this.swapFileChannel.read(batchBuf, startSector.getOffset() + bytesRead);
                if (n <= 0) {
                    throw new IOException("Unexpected short read during gather: expected "
                        + batchTotalBytes + " bytes, got " + bytesRead + " at offset "
                        + (startSector.getOffset() + bytesRead));
                }
                bytesRead += n;
            }

            // 从 batchBuf.array() 拷贝 startSector 段到一个新的独立 ByteBuffer（堆）
            final byte[] batchArr = batchBuf.array();
            final byte[] chunkArr = new byte[(int) startSector.getLength()];
            System.arraycopy(batchArr, 0, chunkArr, 0, chunkArr.length);
            // batchBuf 引用离开本栈后 GC，page cache 保留
            return ByteBuffer.wrap(chunkArr);
        } finally {
            // Mili - Stage A2：异常路径也必须归还，否则下次 acquire 会新建。
            this.releaseBatchBuffer(batchBuf, (int) batchTotalBytes);
            // Mili end
        }
    }

    // Mili start - Stage A2：gather read batch buffer 回收池（ThreadLocal，无锁）
    /**
     * 从 ThreadLocal 池获取一个容量至少 {@code neededBytes} 的堆 buffer。
     * <ul>
     *   <li>若当前线程池中的 buffer 容量 ≥ needed → 直接复用（clear + position=0）</li>
     *   <li>否则新建一次性 buffer（<b>不会被归还</b>，避免池容量超过 maxBatchBytes 限制）</li>
     * </ul>
     * <p>返回的 buffer 必须通过 {@link #releaseBatchBuffer} 显式归还（除了新建的一次性 buffer）。
     */
    private ByteBuffer acquireBatchBuffer(final int neededBytes) {
        final int maxBatchBytes = Math.max(
            neededBytes,
            fun.bm.mili.config.modules.function.RegionFormatConfig.linearGatherReadMaxBatchBytes);
        final ByteBuffer pooled = this.BATCH_BUFFER.get();
        if (pooled != null && pooled.capacity() >= neededBytes) {
            pooled.clear();
            return pooled;
        }
        // 池未初始化 / 容量不足 → 新建一次性 buffer（不会被归还，避免池容量超过上限）
        return ByteBuffer.allocate(maxBatchBytes);
    }

    /**
     * 归还 batch buffer 到 ThreadLocal 池。仅当 buffer 容量等于当前配置的
     * {@link RegionFormatConfig#linearGatherReadMaxBatchBytes} 时才归还。
     *
     * <p><b>为什么检查 capacity == maxBatchBytes？</b>
     * <ul>
     *   <li>{@link #acquireBatchBuffer} 的"池路径"始终返回 capacity = maxBatchBytes 的 buffer</li>
     *   <li>"一次性"路径（neededBytes > maxBatchBytes 或池空）返回的 buffer 容量可能 > maxBatchBytes</li>
     *   <li>显式比较 capacity 避免把"一次性大 buffer"塞回池（会超过池容量上限）</li>
     * </ul>
     *
     * <p><b>配置变更兼容</b>：若运行时修改 {@code linearGatherReadMaxBatchBytes}（{@code @HotReloadUnsupported}，
     * 本配置不支持热重载），池中旧 buffer 容量 ≠ 新值 → 不归还 → 被 GC；下次 acquire 按新值新建一次性 buffer。
     */
    private void releaseBatchBuffer(final ByteBuffer buf, final int usedBytes) {
        final int maxBatchBytes = fun.bm.mili.config.modules.function.RegionFormatConfig.linearGatherReadMaxBatchBytes;
        // 仅回收"恰好 = maxBatchBytes"的 buffer —— 这是 acquireBatchBuffer 池路径创建的
        if (buf.capacity() == maxBatchBytes && maxBatchBytes >= usedBytes) {
            buf.clear();
            this.BATCH_BUFFER.set(buf);
        }
        // 否则是"一次性大 buffer"（或 config 变更后的旧 buffer），自然 GC
    }
    // Mili end

    // Mili start - Stage B：writeChunk() chunkSectionBuilder 回收池（ThreadLocal，无锁）
    /**
     * 写池的硬上限（单线程持有），防异常 chunk 大小导致池无限增长。
     * <p>4 MiB 远大于单 chunk 上限（一个 chunk ≈ 16 KiB 解压前），超过此值视为异常 → 一次性分配不归还。
     */
    private static final int WRITE_BUFFER_HARD_CAP = 4 * 1024 * 1024;

    /**
     * 从 ThreadLocal 池获取一个容量至少 {@code neededBytes} 的堆 buffer，position=0, limit=neededBytes
     * （即 {@code remaining()==neededBytes}，可直接 fill）。
     *
     * <p><b>策略</b>：
     * <ul>
     *   <li>池中 buffer 容量 ≥ needed → 复用（clear + limit=needed）</li>
     *   <li>池中 buffer 容量 < needed 且 needed ≤ 硬上限 → 新建并写入池（grow，单调增长，永不缩）</li>
     *   <li>池中 buffer 容量 < needed 且 needed > 硬上限 → 一次性 buffer（不写入池，不被归还）</li>
     * </ul>
     */
    private ByteBuffer acquireWriteBuffer(final int neededBytes) {
        final ByteBuffer pooled = this.WRITE_BUFFER.get();
        if (pooled != null && pooled.capacity() >= neededBytes) {
            pooled.clear();
            pooled.limit(neededBytes);
            return pooled;
        }
        if (neededBytes <= WRITE_BUFFER_HARD_CAP) {
            // grow 路径：新建更大 buffer 替换池中旧 buffer
            final ByteBuffer fresh = ByteBuffer.allocate(neededBytes);
            this.WRITE_BUFFER.set(fresh);
            return fresh;
        }
        // 异常 chunk 大小 → 一次性分配
        return ByteBuffer.allocate(neededBytes);
    }

    /**
     * 归还 chunkSectionBuilder 到 ThreadLocal 池。
     *
     * <p>本方法在 {@link #acquireWriteBuffer} 已经管理了池容量，release 只需确保 buffer
     * 是"grow 路径"创建的（即 {@code buf.capacity() == usedBytes} 或池当前无值）才归还。
     * 一次性大 buffer（> 硬上限）不归还，让其 GC。
     */
    private void releaseWriteBuffer(final ByteBuffer buf, final int usedBytes) {
        // 仅当"grow 路径"创建的 buffer 才归还（容量 == usedBytes 且未超过硬上限）
        if (buf.capacity() == usedBytes && usedBytes <= WRITE_BUFFER_HARD_CAP && usedBytes > 0) {
            buf.clear();
            this.WRITE_BUFFER.set(buf);
        }
        // 否则是"一次性大 buffer"，自然 GC
    }
    // Mili end

    private void clearChunkData(int chunkOrdinal) throws IOException {
        this.ensureBucketLoaded(chunkOrdinal);

        this.regionObjectLock.writeLock().lock();
        try {
            final Sector sector = this.sectors[chunkOrdinal];

            // Mili start - perf: keep trackedSectorBytes consistent when a sector is cleared.
            if (sector.hasData()) {
                this.trackSectorBytes(-sector.getLength());
            }
            // Mili end

            sector.clear();

            // Mili - persist the deletion tombstone so crash recovery does not resurrect this
            // chunk from the stale master.
            this.persistSectorMetadata(chunkOrdinal);
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
        // Mili start - fix: use the thread-local XXHash32 to avoid the stateful-instance race.
        final int xxHash32OfData = XX_HASH32.get().hash(data, this.xxHash32Seed);
        // Mili end
        data.position(oldPositionOfData);

        // uncompressed length(int) + timestamp(long) + xxhash32(int) = 16 bytes overhead
        final int payloadBytes = data.remaining();
        final int needed = payloadBytes + 16;
        // Mili - Stage B 写路径优化：从 ThreadLocal 池复用 chunkSectionBuilder
        // 池 buffer 在 acquire 时被设 position=0, limit=needed（remaining()==needed），ready to fill。
        // 仅当 O_LINEAR + linear_optimizations_enabled + linearWriteBufferPoolEnabled == true 时启用。
        final boolean writePoolEnabled =
            fun.bm.mili.config.modules.function.RegionFormatConfig.isLinearOptimizationsActive()
                && fun.bm.mili.config.modules.function.RegionFormatConfig.linearWriteBufferPoolEnabled;
        final ByteBuffer chunkSectionBuilder = writePoolEnabled
            ? this.acquireWriteBuffer(needed)
            : ByteBuffer.allocate(needed);
        try {
            chunkSectionBuilder.putInt(payloadBytes); // Length(int)
            chunkSectionBuilder.putLong(System.currentTimeMillis()); // Timestamp(long)
            chunkSectionBuilder.putInt(xxHash32OfData); // xxHash32 of the original data(int)
            chunkSectionBuilder.put(data); // Data(bytes)
            chunkSectionBuilder.flip();

            this.writeChunkDataRaw(chunkIndex, chunkSectionBuilder, false);
        } finally {
            // Mili - Stage B：归还 buffer 到 ThreadLocal 池（仅当本次启用了 pool 路径）
            // 注意：writeChunkDataRaw 内部走 regionObjectLock.writeLock() 并调 Sector.store，
            // 已消费 chunkSectionBuilder 的所有字节（position == limit）。归还前 clear 即可。
            if (writePoolEnabled) {
                this.releaseWriteBuffer(chunkSectionBuilder, needed);
            }
            // Mili end
        }
    }

    private @Nullable ByteBuffer readChunk(int x, int z) throws IOException {
        final int chunkIndex = getChunkIndex(x, z);

        this.ensureBucketLoaded(chunkIndex);

        final ByteBuffer data = this.readChunkDataRaw(chunkIndex);

        if (data == null) {
            return null;
        }

        final int length = data.getInt(); // original (uncompressed) length(int)
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
        // Mili start - fix: use the thread-local XXHash32 to avoid the stateful-instance race.
        final int currentXXHash32 = XX_HASH32.get().hash(input, this.xxHash32Seed);
        // Mili end
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
        final ChunkBufferHelper buffer = new ChunkBufferHelper(pos, this);
        // Mili - single-write: defer the actual chunk write to the I/O stage (finishWrite ->
        // moonrise$write), mirroring RegionFile.ChunkBuffer. Without this, the chunk would be
        // written twice — once on output.close() during the compress stage and again via the
        // write callback in finishWrite — and the heavy compression/disk write would run in the
        // wrong (compress) stage.
        buffer.moonrise$setWriteOnClose(false);
        final DataOutputStream out = new DataOutputStream(buffer);

        return new MoonriseRegionFileIO.RegionDataController.WriteData(
                data, MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
                out, buffer::moonrise$write
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

    // Mili start - perf: sector-byte tracking for O(1) spare-space accounting.
    // Only ever mutated under regionObjectLock.writeLock().
    void trackSectorBytes(long delta) {
        this.trackedSectorBytes += delta;
    }

    long getTrackedSectorBytes() {
        return this.trackedSectorBytes;
    }
    // Mili end

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
