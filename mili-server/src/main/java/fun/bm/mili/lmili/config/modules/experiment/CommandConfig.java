package fun.bm.mili.lmili.config.modules.experiment;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.config.flags.HotReloadUnsupported;
import fun.bm.mili.lmili.config.flags.TransformedConfig;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.EXPERIMENT, name = "command")
public class CommandConfig implements IConfigModule {
    @TransformedConfig(name = "enable", directory = {"experiment", "force_the_data_command_to_be_enabled"})
    @ConfigInfo(name = "enable_data_command")
    @HotReloadUnsupported
    public static boolean data = false;
    @TransformedConfig(name = "enabled", directory = {"experiment", "force_enable_command_block_command_execution"})
    @ConfigInfo(name = "enable_command_block", comments = """
            Force to enable command blocks.
            ATTENTION: WOULD CAUSE SERVER CRASHING AS SOME THREADING ISSUE!!!
            DO NOT ENABLE UNLESS YOU KNOW WHAT YOU ARE DOING!!!
            """)
    public static boolean commandBlock = false;
    @ConfigInfo(name = "enable_waypoints_and_waypoint_command", comments = """
            Enable waypoint and waypoint command.
            WARN: Still under testing
            """)
    @HotReloadUnsupported
    public static boolean waypointsAndWaypointCommand = false;
}
