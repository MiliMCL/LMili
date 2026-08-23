package fun.bm.mili.lmili.data;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Represents a sector (chunk slot) in the linear region file's swap file.
 * Each sector tracks its position and length within the swap file channel.
 */
public class Sector {
    private final int index;
    private long offset;
    private long length;
    boolean hasData = false;
    // Mili - tombstone. Distinguishes "explicitly cleared" (the chunk must stay absent after crash
    // recovery) from "never written" (the chunk may be reloaded from the master file). A plain
    // hasData=false would conflate the two, causing a cleared chunk to resurrect from the stale
    // master after a crash. Encoded as a 3-state byte: 0 = absent, 1 = present, 2 = cleared.
    boolean cleared = false;

    public Sector(int index, long offset, long length) {
        this.index = index;
        this.offset = offset;
        this.length = length;
    }

    /**
     * Transfer this sector's data from source channel to target channel.
     */
    public void transferTo(@NotNull FileChannel source, @NotNull FileChannel target) throws IOException {
        long transferred = 0;
        while (transferred < this.length) {
            transferred += source.transferTo(
                    this.offset + transferred,
                    this.length - transferred,
                    target);
        }
    }

    /**
     * Read this sector's data from the given file channel.
     */
    public @NotNull ByteBuffer read(@NotNull FileChannel channel) throws IOException {
        final ByteBuffer result = ByteBuffer.allocate((int) this.length);

        int totalRead = 0;
        while (totalRead < this.length) {
            int read = channel.read(result, this.offset + totalRead);
            if (read == -1) {
                throw new IOException("Unexpected EOF while reading sector " + this.index +
                        ", expected " + this.length + " bytes, got " + totalRead);
            }
            totalRead += read;
        }

        result.flip();
        return result;
    }

    /**
     * Store new data into this sector. If the new data fits within the old length,
     * it's written in-place; otherwise it's appended at the end of the file.
     *
     * @param newData the data to write
     * @param channel the file channel to write to
     * @param owner   the owning OptimizedLinearRegionFile (for currentAcquiredIndex access)
     */
    public void store(@NotNull ByteBuffer newData, @NotNull FileChannel channel,
                      @NotNull OptimizedLinearRegionFile owner) throws IOException {
        final long oldLength = this.length;
        final long newDataLength = newData.remaining();
        final boolean hadData = this.hasData;

        // data is smaller or its length equals to the local buffer we hold, write it directly
        if (newDataLength <= oldLength) {
            long localOffset = this.offset;
            while (newData.hasRemaining()) {
                localOffset += channel.write(newData, localOffset);
            }

            this.hasData = true;
            this.cleared = false;
            this.length = newDataLength;

            // Mili start - perf: keep owner's live-sector byte count consistent.
            if (!hadData) {
                owner.trackSectorBytes(newDataLength);
            }
            // Mili end

            // Mili - persist this sector's header entry (data first, metadata second) so the swap
            // file remains recoverable after a crash.
            owner.persistSectorMetadata(this.index);
            return;
        }

        // or we will append to the end of file
        this.offset = owner.getCurrentAcquiredIndex();

        owner.advanceAcquiredIndex(newDataLength);

        long localOffset = this.offset;
        while (newData.hasRemaining()) {
            localOffset += channel.write(newData, localOffset);
        }

        this.hasData = true;
        this.cleared = false;
        this.length = newDataLength;

        // Mili start - perf: account for the delta of live-sector bytes.
        owner.trackSectorBytes(hadData ? (newDataLength - oldLength) : newDataLength);
        // Mili end

        // Mili - persist this sector's header entry (data first, metadata second) so the swap file
        // remains recoverable after a crash.
        owner.persistSectorMetadata(this.index);
    }

    /**
     * Encode this sector's metadata into a fixed-size buffer for header storage.
     */
    @NotNull
    public ByteBuffer getEncoded() {
        final ByteBuffer buffer = ByteBuffer.allocate(sizeOfSingle());

        buffer.putLong(this.offset);
        buffer.putLong(this.length);
        buffer.put((byte) (this.hasData ? 1 : (this.cleared ? 2 : 0)));
        buffer.flip();

        return buffer;
    }

    /**
     * Restore sector metadata from an encoded buffer.
     */
    public void restoreFrom(@NotNull ByteBuffer buffer) {
        this.offset = buffer.getLong();
        this.length = buffer.getLong();
        final byte state = buffer.get();
        this.hasData = state == 1;
        this.cleared = state == 2;

        if (this.length < 0 || this.offset < 0) {
            throw new IllegalStateException("Invalid sector data: " + this);
        }
    }

    /**
     * Clear this sector's data flag (marks as having no data) and records a tombstone so crash
     * recovery knows the chunk was explicitly deleted rather than simply never loaded.
     */
    public void clear() {
        this.hasData = false;
        this.cleared = true;
    }

    /**
     * @return true when this sector was explicitly cleared (a deletion tombstone).
     */
    public boolean isCleared() {
        return this.cleared;
    }

    /**
     * @return true if this sector contains valid data.
     */
    public boolean hasData() {
        return this.hasData;
    }

    /**
     * @return the fixed byte size of a single sector's encoded metadata.
     */
    public static int sizeOfSingle() {
        //     offset + length  hasData
        return Long.BYTES * 2 + 1;
    }

    public int getIndex() {
        return this.index;
    }

    public long getOffset() {
        return this.offset;
    }

    public long getLength() {
        return this.length;
    }
}
