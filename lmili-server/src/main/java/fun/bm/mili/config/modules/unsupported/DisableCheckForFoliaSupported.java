package fun.bm.mili.config.modules.unsupported;

/**
 * 禁用 Folia 兼容性检查配置。
 *
 * <p>此类用于控制是否在 Paper/Bukkit 环境中禁用 Folia 兼容性检查。
 * 在纯 Paper 环境中，Folia 检查是不必要的，可以安全禁用。
 *
 * <p>注意：此类为兼容性保留类，新功能请使用 {@link LMiliDisableFoliaCheckConfig}。
 *
 * @since 2.0.0
 */
public final class DisableCheckForFoliaSupported {

    /**
     * 是否在 Paper 环境中禁用 Folia 兼容性检查。
     *
     * <p>在纯 Paper 环境中，Folia 检查是不必要的，可以安全禁用。
     */
    public static volatile boolean disableForPaper = true;

    private DisableCheckForFoliaSupported() {}
}
