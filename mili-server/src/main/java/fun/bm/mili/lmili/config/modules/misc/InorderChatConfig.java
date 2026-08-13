package fun.bm.mili.lmili.config.modules.misc;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "mojang_out_of_order_chat_check")
public class InorderChatConfig implements IConfigModule {
    @ConfigInfo(name = "enabled")
    public static boolean enabled = true;
}