package fun.bm.mili.config.modules.misc;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "disable-check")
public class DisableCheckConfig implements IConfigModule {
    @ConfigInfo(name = "disable-op-move-check", comments = """
            禁用 OP 移动检查""")
    public static boolean disableOpMoveCheck = false;

    @ConfigInfo(name = "disable-op-fly-check", comments = """
            禁用 OP 飞行检查""")
    public static boolean disableOpFlyCheck = false;
}