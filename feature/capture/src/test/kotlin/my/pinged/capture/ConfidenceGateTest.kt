package my.pinged.capture

import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.PendingReasons
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 7.1's gate as a function, with no database in the way.
 *
 * `PendingReasonRecordedTest` asserts the same decisions as they land in the
 * column, which is what the review inbox reads; this file asserts the shape of
 * the rule list -- that all five conditions exist, that they combine, and that
 * combining them stores exactly one reason.
 */
class ConfidenceGateTest {

    private fun decide(
        confidence: Confidence = Confidence.HIGH,
        kind: Kind? = null,
        merchantRaw: String? = "SOME SHOP",
        amountSen: Long = 1_000L,
        duplicateSuspect: Boolean = false,
        thresholdSen: Long = ConfidenceGate.DEFAULT_THRESHOLD_SEN,
    ) = ConfidenceGate.decide(
        confidence, kind, merchantRaw, amountSen, duplicateSuspect, thresholdSen,
    )

    @Test
    fun anOrdinaryMatchCommitsWithNoReason() {
        val decision = decide()
        assertEquals(TxnState.COMMITTED, decision.state)
        assertNull(decision.reason)
    }

    @Test
    fun eachOfTheFiveConditionsOnItsOwnSendsTheRowToReview() {
        assertEquals(PendingReason.RULE_REVIEW, decide(confidence = Confidence.REVIEW).reason)
        assertEquals(
            PendingReason.TRANSFER_SUSPECT,
            decide(kind = Kind.TRANSFER_SUSPECT).reason,
        )
        assertEquals(PendingReason.MERCHANT_MISSING, decide(merchantRaw = null).reason)
        assertEquals(
            PendingReason.DUPLICATE_SUSPECT,
            decide(duplicateSuspect = true).reason,
        )
        assertEquals(PendingReason.OVER_THRESHOLD, decide(amountSen = 50_001L).reason)

        // And every one of them is PENDING, not merely reasoned.
        listOf(
            decide(confidence = Confidence.REVIEW),
            decide(kind = Kind.TRANSFER_SUSPECT),
            decide(merchantRaw = null),
            decide(duplicateSuspect = true),
            decide(amountSen = 50_001L),
        ).forEach { assertEquals(TxnState.PENDING, it.state) }
    }

    /**
     * Spec 7.1's threshold is "exceeds", so the threshold value itself is not
     * over it. One sen either side of RM500.00.
     */
    @Test
    fun theThresholdIsExclusive() {
        assertEquals(50_000L, ConfidenceGate.DEFAULT_THRESHOLD_SEN)
        assertNull(decide(amountSen = 50_000L).reason)
        assertEquals(PendingReason.OVER_THRESHOLD, decide(amountSen = 50_001L).reason)
    }

    /**
     * A blank merchant is a missing merchant. `RuleMatcher` already trims and
     * nulls an empty group, so this is defence rather than a live path -- but
     * a whitespace-only merchant would otherwise commit a row the review inbox
     * cannot label and the categorizer cannot learn from.
     */
    @Test
    fun aBlankMerchantCountsAsMissing() {
        assertEquals(PendingReason.MERCHANT_MISSING, decide(merchantRaw = "   ").reason)
        assertEquals(PendingReason.MERCHANT_MISSING, decide(merchantRaw = "").reason)
    }

    /**
     * Spec 7.1's gate is "if any of the following hold", so several hold at
     * once routinely. All of them are computed; exactly one is stored, and the
     * choice is `PendingReasons.mostSpecific`'s rather than this file's.
     */
    @Test
    fun everyConditionThatHoldsIsReportedAndOnlyTheMostSpecificIsStored() {
        val fired = ConfidenceGate.reasons(
            confidence = Confidence.REVIEW,
            kind = Kind.TRANSFER_SUSPECT,
            merchantRaw = null,
            amountSen = 80_000L,
            duplicateSuspect = true,
        )
        assertEquals(
            "An RM800 duplicate wallet reload with no merchant fires all five",
            PendingReason.entries.toSet(),
            fired,
        )
        assertEquals(PendingReason.DUPLICATE_SUSPECT, PendingReasons.mostSpecific(fired))
        assertEquals(
            PendingReason.DUPLICATE_SUSPECT,
            decide(
                confidence = Confidence.REVIEW,
                kind = Kind.TRANSFER_SUSPECT,
                merchantRaw = null,
                amountSen = 80_000L,
                duplicateSuspect = true,
            ).reason,
        )
    }

    /**
     * The threshold is a parameter because the setting behind it does not
     * exist yet: spec 7.1 calls it user-configurable and there is no store for
     * it anywhere in this milestone. When one arrives it has exactly one call
     * site to fill.
     */
    @Test
    fun theThresholdIsSubstitutable() {
        assertEquals(
            PendingReason.OVER_THRESHOLD,
            decide(amountSen = 20_001L, thresholdSen = 20_000L).reason,
        )
        assertNull(decide(amountSen = 60_000L, thresholdSen = 100_000L).reason)
    }

    /**
     * Every reason this gate can produce has a place in the stored precedence
     * order, or `mostSpecific` would silently return null for it and a
     * `PENDING` row with no reason would be refused at the write path.
     */
    @Test
    fun everyReasonThisGateCanProduceIsRankedByCoreData() {
        assertTrue(PendingReasons.PRECEDENCE.containsAll(PendingReason.entries))
    }
}
