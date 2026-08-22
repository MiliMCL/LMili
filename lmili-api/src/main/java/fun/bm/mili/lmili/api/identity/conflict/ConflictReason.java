package fun.bm.mili.lmili.api.identity.conflict;

/**
 * Why an identity registration was rejected.
 *
 * <p>Per the V2 spec this is extensible: future signature and publisher-mismatch
 * checks should add values rather than overload the existing ones.</p>
 */
public enum ConflictReason {
    /** Two plugins registered the same id (regardless of version). */
    DUPLICATE_ID,
    /** Addon declared a parent id that does not match its id-derived parent. */
    INVALID_PARENT,
    /** {@code lmili.json} parsed but failed structural validation. */
    INVALID_METADATA,

    /** Future: signature subsystem reports a hash mismatch. Reserved. */
    SIGNATURE_MISMATCH,
    /** Future: declared publisher does not match the signed publisher. Reserved. */
    PUBLISHER_MISMATCH
}