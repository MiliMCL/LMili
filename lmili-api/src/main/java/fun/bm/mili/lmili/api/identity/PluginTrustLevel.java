package fun.bm.mili.lmili.api.identity;

/**
 * Trust level of a registered plugin identity.
 *
 * <p>In this stage every plugin starts at {@link #UNKNOWN}. Trust is never
 * promoted automatically based on metadata (e.g. a GitHub URL in {@code lmili.json}
 * does <em>not</em> make a plugin {@link #VERIFIED}) — that requires the future
 * signature subsystem.</p>
 */
public enum PluginTrustLevel {
    /** Default. No signal either way. */
    UNKNOWN,
    /** Installed on the local disk, not signed. */
    LOCAL,
    /** Signed by a known publisher key. Reserved for the future signature subsystem. */
    VERIFIED,
    /** Operator or runtime has explicitly downgraded this plugin. */
    RESTRICTED
}