package fun.bm.mili.lmili.config.modules.misc;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "server_mod_name")
public class ServerModNameConfig implements IConfigModule {
    @ConfigInfo(name = "name", comments = "Decides the server mod name shown in your F3 debug screen.")
    public static String serverModName = "Luminol";

    @ConfigInfo(name = "vanilla_spoof", comments = "Ignore any plugin's modification and server mod name set in this config block, only force sending brand name of vanilla")
    public static boolean fakeVanilla = false;
}