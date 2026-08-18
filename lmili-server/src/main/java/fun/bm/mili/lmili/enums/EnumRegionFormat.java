package fun.bm.mili.lmili.enums;

import abomination.LinearRegionFile;
import fun.bm.mili.config.modules.function.RegionFormatConfig;
import fun.bm.mili.lmili.data.BufferedLinearRegionFile;
import fun.bm.mili.lmili.utils.IRegionCreateFunction;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.jetbrains.annotations.Nullable;

public enum EnumRegionFormat {
    MCA("mca", "mca", (info) -> new RegionFile(info.info(), info.filePath(), info.folder(), info.sync())),
    LINEAR_V2("linear_v2", "linear", (info) -> new LinearRegionFile(info.info(), info.filePath(), info.folder(), info.sync(), RegionFormatConfig.linearCompressionLevel)),
    B_LINEAR("b_linear", "b_linear", (info) -> new BufferedLinearRegionFile(info.filePath(), RegionFormatConfig.linearCompressionLevel, RegionFormatConfig.blinearFlusher));

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

        return null;
    }

    public IRegionCreateFunction getCreator() {
        return this.creator;
    }

    public String getArgument() {
        return this.argument;
    }
}