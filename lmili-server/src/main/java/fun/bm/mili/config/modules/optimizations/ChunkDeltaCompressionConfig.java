package fun.bm.mili.config.modules.optimizations;

import fun.bm.mili.config.TomlConfigData;
import fun.bm.mili.utils.ChunkDeltaCompressor;
import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "chunk-delta-compression")
public class ChunkDeltaCompressionConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            启用区块数据增量压缩""")
    public static boolean enabled = false;

    @ConfigInfo(name = "max-delta-size", comments = """
            最大增量大小（字节）""")
    public static int maxDeltaSize = 65536;

    @ConfigInfo(name = "snapshot-interval", comments = """
            快照间隔（tick）""")
    public static int snapshotInterval = 20;

    @ConfigInfo(name = "compression-level", comments = """
            压缩级别（1-9，默认3，越低越快但压缩率越低）""")
    public static int compressionLevel = 3;

    @Override
    public void onLoaded(TomlConfigData configInstance, @Nullable Set<Exception> exs) {
        ChunkDeltaCompressor.setEnabled(enabled);
    }

    @Override
    public void onUnloaded(TomlConfigData configInstance) {
        ChunkDeltaCompressor.setEnabled(false);
    }
}
