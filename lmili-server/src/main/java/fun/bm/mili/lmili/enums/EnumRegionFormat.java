package fun.bm.mili.lmili.enums;

import fun.bm.mili.config.modules.function.RegionFormatConfig;
import fun.bm.mili.lmili.data.OptimizedLinearRegionFile;
import fun.bm.mili.lmili.utils.IRegionCreateFunction;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

public enum EnumRegionFormat {
    MCA("mca", "mca", (info) -> new RegionFile(info.info(), info.filePath(), info.folder(), info.sync())),
    O_LINEAR("o_linear", "o_linear", (info) -> new OptimizedLinearRegionFile(info.filePath(), RegionFormatConfig.linearCompressionLevel, RegionFormatConfig.olinearFlusher));

    // Mili - compatibility: map legacy region-format names to the surviving formats so existing
    // world configs keep working. LINEAR_V2 support has been removed; its files are migrated to
    // O_LINEAR on load (see LinearFormatMigrator), and the old b_linear name is an alias.
    private static final Map<String, EnumRegionFormat> LEGACY_ALIASES = Map.of(
            "b_linear", O_LINEAR,
            "olinear", O_LINEAR,
            "linear", O_LINEAR,
            "linear_v2", O_LINEAR
    );

    private final String name;
    private final String argument;
    private final IRegionCreateFunction creator;

    EnumRegionFormat(String name, String argument, IRegionCreateFunction creator) {
        this.name = name;
        this.argument = argument;
        this.creator = creator;
    }

    @Nullable
    public static EnumRegionFormat fromString(String string) {
        for (EnumRegionFormat format : values()) {
            if (format.name.equalsIgnoreCase(string)) {
                return format;
            }
        }

        final EnumRegionFormat alias = LEGACY_ALIASES.get(string.toLowerCase(Locale.ROOT));
        return alias;
    }

    public IRegionCreateFunction getCreator() {
        return this.creator;
    }

    public String getArgument() {
        return this.argument;
    }
}
