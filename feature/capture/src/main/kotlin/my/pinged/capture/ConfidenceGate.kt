package my.pinged.capture

import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.PendingReasons
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Kind

/**
 * Spec 7.1's gate: five conditions, any of which sends a transaction to the
 * review inbox instead of the ledger.
 *
 * "The gate is a deterministic rule list, not a numeric score, so the app can
 * always state exactly why an item needs review" -- so it returns the set of
 * conditions that fired, not a boolean, and [PendingReasons.mostSpecific]
 * chooses which is stored. Two or three hold routinely: an RM800 wallet reload
 * with no merchant is three at once.
 *
 * `state` and `pending_reason` are returned together because they cannot
 * legally disagree, and [my.pinged.data.entity.requireStorable] throws at the
 * write path if they do. Producing them in one place makes that throw
 * unreachable from here.
 */
internal object ConfidenceGate {

    /**
     * Spec 7.1's threshold: "`amount_sen` exceeds a user-configurable threshold,
     * default RM500".
     *
     * **The setting is not backed by anything** -- there is no settings store in
     * this milestone -- so this constant is the default and the only value. A
     * parameter of [decide] rather than a literal inside it, so the store, when it
     * exists, has one call site to fill.
     */
    const val DEFAULT_THRESHOLD_SEN: Long = 50_000L // RM500.00

    /** The gate's answer: a state, and the reason if that state is PENDING. */
    data class Decision(val state: TxnState, val reason: PendingReason?)

    /**
     * Every condition of spec 7.1 that holds for this transaction.
     *
     * A set rather than a reduction, so the choice of which reason to store stays
     * [PendingReasons.mostSpecific]'s and a test can assert that three conditions
     * fired even though one is recorded.
     *
     * `amountSen > threshold`, strictly: spec 7.1 says "exceeds".
     */
    fun reasons(
        confidence: Confidence,
        kind: Kind?,
        merchantRaw: String?,
        amountSen: Long,
        duplicateSuspect: Boolean,
        thresholdSen: Long = DEFAULT_THRESHOLD_SEN,
    ): Set<PendingReason> = buildSet {
        if (duplicateSuspect) add(PendingReason.DUPLICATE_SUSPECT)
        if (kind == Kind.TRANSFER_SUSPECT) add(PendingReason.TRANSFER_SUSPECT)
        if (confidence == Confidence.REVIEW) add(PendingReason.RULE_REVIEW)
        // "amount was extracted but merchant is missing or empty". An amount
        // was extracted by construction -- there is no Matched outcome without
        // one -- so this is just the merchant half. Blank counts as empty: the
        // column stores null for it (see ParsePass), which keeps
        // `merchant_raw IS NULL OR merchant_raw = ''` an exact recomputation of
        // this decision forever.
        if (merchantRaw.isNullOrBlank()) add(PendingReason.MERCHANT_MISSING)
        if (amountSen > thresholdSen) add(PendingReason.OVER_THRESHOLD)
    }

    fun decide(
        confidence: Confidence,
        kind: Kind?,
        merchantRaw: String?,
        amountSen: Long,
        duplicateSuspect: Boolean,
        thresholdSen: Long = DEFAULT_THRESHOLD_SEN,
    ): Decision {
        val reason = PendingReasons.mostSpecific(
            reasons(confidence, kind, merchantRaw, amountSen, duplicateSuspect, thresholdSen),
        )
        return Decision(
            state = if (reason == null) TxnState.COMMITTED else TxnState.PENDING,
            reason = reason,
        )
    }
}
