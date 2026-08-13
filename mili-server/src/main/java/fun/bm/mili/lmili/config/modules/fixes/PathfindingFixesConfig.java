package fun.bm.mili.lmili.config.modules.fixes;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FIXES, name = "pathfinding_fixes")
public class PathfindingFixesConfig implements IConfigModule {
    @ConfigInfo(name = "break_down_pathfinding_when_out_of_region", comments = "Recompute path or stop pathfinding when it's touching the blocks out of current tick region")
    public static boolean breakDownPathfindingWhenOutOfRegion = false;
    @ConfigInfo(name = "do_not_pathfind_to_not_owned_targets", comments = "Skip pathfinding target when it's out of current tick region")
    public static boolean doNotPathfindToNotOwnedTargets = false;
}
