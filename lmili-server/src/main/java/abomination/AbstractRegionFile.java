package abomination;

import java.nio.file.Path;

/**
 * Abstract base class for region file implementations.
 * Contains common utility methods and default no-op implementations
 * for oversized chunk related operations shared by all Linear region
 * file variants.
 *
 * <p>修复：明确标记空实现为有意为之（Linear 格式不支持 oversized chunks），
 * 避免被误认为是未完成代码。
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

    /**
     * Linear 格式不支持 oversized chunks，默认返回 false。
     * 子类如支持此功能应覆盖此方法。
     */
    @Override
    public boolean isOversized(int x, int z) {
        return false;
    }

    /**
     * Linear 格式无需重新计算 header，默认返回 false。
     * 子类如需要此功能应覆盖此方法。
     */
    @Override
    public boolean recalculateHeader() {
        return false;
    }

    /**
     * Linear 格式不支持 oversized chunks，此方法为空实现。
     * 子类如支持此功能应覆盖此方法。
     */
    @Override
    public void setOversized(int x, int z, boolean oversized) {
        // Linear 格式不支持 oversized chunks，有意留空
    }
}
