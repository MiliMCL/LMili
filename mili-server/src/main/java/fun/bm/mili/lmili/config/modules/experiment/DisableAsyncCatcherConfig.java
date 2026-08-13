package fun.bm.mili.lmili.config.modules.experiment;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "disable_async_catchers")
public class DisableAsyncCatcherConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            Disable async catcher to prevent some crashes caused by some plugins which supports folia but has issuable logics.
            ATTENTION: Would cause region deadlock when getChunkAt was incorrectly called!
                       See: https://github.com/PaperMC/Folia/issues/280 which is resolved in folia(https://github.com/PaperMC/Folia/commit/2e7bc0721af95196c85500c7bb136aeea0bc12ce)
            DO NOT ENABLE UNLESS YOU KNOW WHAT YOU ARE DOING!!!
            """)
    public static boolean enabled = false;
}