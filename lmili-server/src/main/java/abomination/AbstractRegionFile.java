package abomination;

import java.nio.file.Path;

/**
 * Abstract base class for region file implementations.
 * Contains common utility methods and default no-op implementations
 * for oversized chunk related operations shared by all Linear region
 * file variants.
 */
public abstract class AbstractRegionFile implements IRegionFile {

    public static final long LINEAR_FILE_SUPER_BLOCK = 0xc3ff13183cca9d9aL;

    protected final Path path;

    protected AbstractRegionFile(Path path) {
        this.path = path;
    }

    @Override
    public Path getPath() {
        return this.path;
    }

    protected static int getChunkIndex(int x, int z) {
        return (x & 31) + ((z & 31) << 5);
    }

    @Override
    public boolean isOversized(int x, int z) {
        return false;
    }

    @Override
    public boolean recalculateHeader() {
        return false;
    }

    @Override
    public void setOversized(int x, int z, boolean oversized) {
    }
}
