package fun.bm.mili.config.modules.function;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.HotReloadUnsupported;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "async-keepalive", comments = """
        Async keepalive handling (network stability)""")
public class AsyncKeepaliveConfig implements IConfigModule {
    @HotReloadUnsupported
    @ConfigInfo(name = "enable", comments = """
            Whether keepalive processing runs on the async keepalive manager""")
    public static boolean asyncKeepalive = true;

    @ConfigInfo(name = "timeout-seconds", comments = """
            Keepalive timeout in seconds before a connection is disconnected""")
    public static int asyncKeepaliveTimeoutSeconds = 30;
}
