package my.pinged.capture

import my.pinged.capture.ReadingFixtures.AT
import my.pinged.capture.ReadingFixtures.MAE
import my.pinged.capture.ReadingFixtures.MINUTE
import my.pinged.capture.ReadingFixtures.NOTHING
import my.pinged.capture.ReadingFixtures.UNASKED
import my.pinged.capture.ReadingFixtures.capture
import my.pinged.capture.ReadingFixtures.category
import my.pinged.capture.ReadingFixtures.corpus
import my.pinged.capture.ReadingFixtures.firstReading
import my.pinged.capture.ReadingFixtures.id
import my.pinged.capture.ReadingFixtures.inLedger
import my.pinged.capture.ReadingFixtures.matched
import my.pinged.capture.ReadingFixtures.reading
import my.pinged.capture.ReadingFixtures.verdict
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.ExclusionReason
import my.pinged.parse.LearnedRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Spec 5.5's re-reading: a capture's stored transaction as the current pack
 * would write it. Each fixture is a capture the bundled pack reads, its row
 * as an older pack left it ([olderRead]), and the capture stamped as that
 * pack's read.
 *
 * `CorrectionsTest` keeps one end-to-end case of each, through the sweep, the
 * offer and accept, on the device.
 */
class RereadingTest {

    /** [capture] as a pack one version back read it under [oldRule], and the row [olderRead] makes of its first reading. */
    private fun stale(capture: RawCapture, oldRule: String, olderRead: (Txn) -> Txn): Pair<RawCapture, Txn> {
        val stored = olderRead(firstReading(capture).txn.copy(id = id()))
        return capture.copy(packVersion = ReadingFixtures.matcher.packVersion - 1, matchedRuleId = oldRule) to stored
    }

    private fun again(
        stale: Pair<RawCapture, Txn>,
        reading: Reading = reading(),
        pairs: PairCandidates = NOTHING,
    ): Reading.Again {
        val (capture, stored) = stale
        return reading.reread(capture, matched(capture), stored, pairs, now = AT + 60 * MINUTE)
    }

    private val counts = listOf(TxnState.COMMITTED, null, false, null)
    private fun held(reason: PendingReason) = listOf(TxnState.PENDING, reason, false, null)

    private fun payment(rm: String, shop: String = "KEDAI", at: Long = AT) =
        capture("Payment of $rm to $shop successful", title = "Touch 'n Go", postedAt = at)

    // ---- what the pack reads now ---------------------------------------------

    @Test fun aWrongAmountIsReadAgain() {
        val after = again(stale(payment("RM32.00"), "tng-payment-v1") { it.copy(amountSen = 32) }).after

        assertEquals(3_200L, after.amountSen)
        assertEquals(counts, verdict(after))
    }

    @Test fun aMerchantReadAsTheWholeSentenceIsReadAgain() {
        val my50 = capture(
            "Travel Pass: You have paid RM 50.00 for your My50 Pass. View your updated pass details now.",
            title = "Payment successful",
        )
        val sentence = "your My50 Pass. View your updated pass details now."
        val after = again(stale(my50, "tng-paid-for-v1") { it.copy(merchantRaw = sentence, merchantDisplay = sentence, merchantKey = sentence.uppercase()) }).after

        assertEquals("My50 Pass", after.merchantDisplay)
        assertEquals("MY50 PASS", after.merchantKey)
    }

    @Test fun theRereadKeepsTheRowsIdentityDateAndClock() {
        val (capture, stored) = stale(payment("RM32.00"), "tng-payment-v1") { it.copy(amountSen = 32) }
        val after = again(capture to stored).after

        assertEquals(
            listOf<Any?>(stored.id, stored.rawCaptureId, stored.occurredAt, stored.localDate, stored.createdAt, AT + 60 * MINUTE),
            listOf<Any?>(after.id, after.rawCaptureId, after.occurredAt, after.localDate, after.createdAt, after.updatedAt),
        )
    }

    // ---- what a re-reading never does -----------------------------------------

    /** The learned rule's choice survives a pack correction: a re-reading files nothing. */
    @Test fun aRereadingFilesNothing() {
        val learned = LearnedRules.Snapshot(rules = mapOf("KEDAI" to category("Health")))
        val stale = stale(payment("RM32.00"), "tng-payment-v1") { it.copy(amountSen = 32, categoryId = category("Shopping")) }

        assertEquals(category("Shopping"), again(stale, reading(learned = learned)).after.categoryId)
    }

    /** A standing answer made since is not a reason to settle a row already in the inbox. */
    @Test fun aRereadingAsksNoStandingAnswer() {
        val reload = capture("Reload of RM28.77 was successful")
        val answered = reading(answers = setOf(InMemoryStandingAnswers.Answer("tng-reload-v1", reload.sourcePackage, null)))
        val stale = stale(reload, "tng-reload-v1") { it.copy(confidence = Confidence.HIGH) }

        assertEquals(held(PendingReason.TRANSFER_SUSPECT), verdict(again(stale, answered).after))
    }

    // ---- a committed row keeps its verdict ----------------------------------------

    /** Issue #20's card bill, counted before the card-bill rule existed: its confidence is read again, its state is not. */
    @Test fun aCommittedCardBillIsReadItsConfidenceButStaysCommitted() {
        val bill = capture("Successful payment of RM 2,262.76 to Maybank Master Card. REF:000000000M", pkg = MAE, title = "Maybank2u: Payments")
        val after = again(stale(bill, "mae-scan-pay-v1") { it.copy(state = TxnState.COMMITTED, pendingReason = null, confidence = Confidence.HIGH) }).after

        assertEquals(Confidence.REVIEW, after.confidence)
        assertEquals(counts, verdict(after))
    }

    @Test fun aCommittedRowTheGateWouldHoldStaysCommitted() {
        val after = again(stale(payment("RM650.00"), "tng-payment-v1") { it.copy(state = TxnState.COMMITTED, pendingReason = null) }).after

        assertEquals(counts, verdict(after))
    }

    @Test fun aCommittedRowsWrongAmountIsReadAgainAndStaysCommitted() {
        val after = again(stale(payment("RM650.00"), "tng-payment-v1") { it.copy(state = TxnState.COMMITTED, pendingReason = null, amountSen = 650) }).after

        assertEquals(65_000L, after.amountSen)
        assertEquals(counts, verdict(after))
    }

    /** A Keep both row: its `duplicate_of_id` stays set for ever, and a new pack does not re-raise the duplicate. */
    @Test fun aCommittedDuplicateRaisesNoStatusDifference() {
        val other = payment("RM21.00", at = AT - MINUTE)
        val (capture, stored) = stale(payment("RM21.00"), "tng-payment-v1") { it.copy(state = TxnState.COMMITTED, pendingReason = null) }
        val kept = capture.copy(duplicateOfId = other.id)

        val again = again(kept to stored, pairs = UNASKED)

        assertEquals(counts, verdict(again.after))
        assertNull(again.pairCaptureId)
    }

    // ---- a pending row is read again --------------------------------------------

    @Test fun aPendingRowMovesToANewReason() {
        val after = again(stale(payment("RM650.00"), "tng-payment-v1") { it.copy(pendingReason = PendingReason.MERCHANT_MISSING) }).after

        assertEquals(held(PendingReason.OVER_THRESHOLD), verdict(after))
    }

    @Test fun aPendingRowReleases() {
        val after = again(stale(payment("RM23.00"), "tng-payment-v1") { it.copy(state = TxnState.PENDING, pendingReason = PendingReason.RULE_REVIEW, confidence = Confidence.REVIEW) }).after

        assertEquals(counts, verdict(after))
    }

    /** #362: the check limit now, not the one the row arrived under. */
    @Test fun aRowHeldUnderALimitSinceRaisedIsReleased() {
        val stale = stale(payment("RM800.00"), "tng-payment-v1") { it }
        assertEquals("precondition: RM500 held it", PendingReason.OVER_THRESHOLD, stale.second.pendingReason)

        assertEquals(counts, verdict(again(stale, reading(checkLimitSen = 100_000L)).after))
    }

    /** A row already waiting as a suspect stays one, and is not paired again. */
    @Test fun aWaitingSuspectStaysASuspect() {
        val stale = stale(payment("RM23.00"), "tng-payment-v1") { it.copy(state = TxnState.PENDING, pendingReason = PendingReason.DUPLICATE_SUSPECT) }

        val again = again(stale, pairs = UNASKED)

        assertEquals(held(PendingReason.DUPLICATE_SUSPECT), verdict(again.after))
        assertNull(again.pairCaptureId)
    }

    /** A lookup's failure is [Corrections]' to decide, so the re-reading lets it through. */
    @Test fun aPairLookupThatThrowsThrowsOutOfTheRereading() {
        val damage = IllegalStateException("page 7 is damaged")
        val stale = stale(payment("RM23.00"), "tng-payment-v1") { it.copy(state = TxnState.PENDING, pendingReason = PendingReason.RULE_REVIEW, confidence = Confidence.REVIEW) }

        assertSame(damage, runCatching { again(stale, pairs = { throw damage }) }.exceptionOrNull())
    }

    // ---- #255: a waiting FPX payment, paired again ------------------------------

    /** [fixture] read by pack `N - 1` and held for its rule's `REVIEW`, with no pair recorded. */
    private fun fpxHeldForReview(fixture: String, at: Long) = stale(corpus(fixture, postedAt = at), "mae-fpx-v1") {
        it.copy(state = TxnState.PENDING, pendingReason = PendingReason.RULE_REVIEW, confidence = Confidence.REVIEW)
    }

    /** Card-funded: the card spend at the gateway already counts it, so the payment waits as its duplicate. */
    @Test fun aWaitingFpxPaymentBesideItsCardSpendComesBackAsADuplicateSuspect() {
        val card = corpus("mae-card-spend-fpx-credit-card-rps.txt", postedAt = AT)
        val spend = inLedger(card)
        val fpx = fpxHeldForReview("mae-fpx-card-funded-ibilik.txt", AT + 6_000L)

        val again = again(fpx, pairs = InMemoryPairCandidates(listOf(spend)))

        assertEquals(held(PendingReason.DUPLICATE_SUSPECT), verdict(again.after))
        assertEquals(card.id, again.pairCaptureId)
    }

    /**
     * The card spend arrived after the payment, so it is the newer of the pair
     * and the one stage two flagged. Paired again, both would wait as each
     * other's duplicate.
     */
    @Test fun aWaitingFpxPaymentIsNotPairedWithACardSpendThatArrivedAfterIt() {
        val fpx = fpxHeldForReview("mae-fpx-card-funded-ibilik.txt", AT)
        val later = inLedger(corpus("mae-card-spend-fpx-credit-card-rps.txt", postedAt = AT + 6_000L))
        check(later.txn.id > fpx.second.id)

        val again = again(fpx, pairs = InMemoryPairCandidates(listOf(later)))

        assertEquals(counts, verdict(again.after))
        assertNull(again.pairCaptureId)
    }

    /** Account-funded: nothing beside it, so it is released. */
    @Test fun aWaitingFpxPaymentWithNoCardSpendIsReleased() {
        val again = again(fpxHeldForReview("mae-fpx-from-account-digi.txt", AT))

        assertEquals(counts, verdict(again.after))
        assertNull(again.pairCaptureId)
    }

    /** The exclusion a row carries is the user's, and a re-reading leaves it. */
    @Test fun aRereadingLeavesTheRowsExclusion() {
        val stale = stale(payment("RM32.00"), "tng-payment-v1") { it.copy(amountSen = 32, isExcluded = true, exclusionReason = ExclusionReason.TRANSFER) }

        val after = again(stale).after

        assertEquals(true to ExclusionReason.TRANSFER, after.isExcluded to after.exclusionReason)
    }
}
