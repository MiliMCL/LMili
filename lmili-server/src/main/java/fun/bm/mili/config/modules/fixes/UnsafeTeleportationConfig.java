package fun.bm.mili.config.modules.fixes;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "allow_unsafe_teleportation")
public class UnsafeTeleportationConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = """
            Allow non player entities enter end portals if enabled.
            If you want to use sand duping,please turn on this.
            Warning: This would cause some unsafe issues, you could learn more on : https://github.com/PaperMC/Folia/issues/297""")
    public static boolean enabled = false;
}