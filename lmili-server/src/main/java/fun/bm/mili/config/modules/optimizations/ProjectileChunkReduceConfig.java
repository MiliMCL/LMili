package fun.bm.mili.config.modules.optimizations;

import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.OPTIMIZATIONS, name = "projectile")
public class ProjectileChunkReduceConfig implements IConfigModule {
    @ConfigInfo(name = "max-loads-per-tick", comments = "Controls how many chunks are allowed to be sync loaded by projectiles in a tick.")
    public static int maxProjectileLoadsPerTick;
    @ConfigInfo(name = "max-loads-per-projectile", comments = "Controls how many chunks a projectile can load in its lifetime before it gets automatically removed.")
    public static int maxProjectileLoadsPerProjectile;
}