package fun.bm.mili.config.modules.fixes;

/**
 * Folia 实体移动修复配置 —— 兼容性保留类。
 *
 * <p>此类为兼容性保留，新功能请使用 {@link LMiliEntityMovingFixConfig}。
 *
 * @since 2.0.0
 * @deprecated 使用 {@link LMiliEntityMovingFixConfig} 替代
 */
@Deprecated
public final class FoliaEntityMovingFixConfig {

    /** @deprecated 使用 {@link LMiliEntityMovingFixConfig#enabled} */
    @Deprecated
    public static volatile boolean enabled = LMiliEntityMovingFixConfig.enabled;

    /** @deprecated 使用 {@link LMiliEntityMovingFixConfig#warnOnDetected} */
    @Deprecated
    public static volatile boolean warnOnDetected = LMiliEntityMovingFixConfig.warnOnDetected;

    private FoliaEntityMovingFixConfig() {}
}
