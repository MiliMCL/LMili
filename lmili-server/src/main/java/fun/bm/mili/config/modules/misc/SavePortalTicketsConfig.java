package fun.bm.mili.config.modules.misc;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "save_portal_tickets")
public class SavePortalTicketsConfig implements IConfigModule {
    @ConfigInfo(name = "do_save", comments = "whether or not to save the portal tickets when server stopping," +
            " this would make it acts like mc before 1.21.5," +
            " and won't auto active the portal chunk loader when server started again.")
    public static boolean doSave = true;
}
