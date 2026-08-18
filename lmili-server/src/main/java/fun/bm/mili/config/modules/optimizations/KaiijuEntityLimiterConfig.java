package fun.bm.mili.config.modules.optimizations;

import fun.bm.mili.config.TomlConfigData;
import dev.kaiijumc.kaiiju.KaiijuEntityLimits;
import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "kaiiju_entity_limiter")
public class KaiijuEntityLimiterConfig implements IConfigModule {
    @Override
    public void onLoaded(TomlConfigData configInstance, @Nullable Set<Exception> e) {
        KaiijuEntityLimits.init();
    }
}