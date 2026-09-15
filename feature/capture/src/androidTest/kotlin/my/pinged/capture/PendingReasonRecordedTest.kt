package my.pinged.capture

import my.pinged.data.Databases
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.1's gate, one test per condition, each asserting the reason actually
 * written to `txn.pending_reason` rather than the one the gate returned.
 *
 * The column is what matters: spec 9.2's inbox reads the row, not the parser.
 * A `PENDING` row with no reason is refused at the write path
 * ([my.pinged.data.entity.requireStorable]), so a gate that decided `PENDING`
 * without naming a reason would take the whole `commitCapture` transaction down
 * and leave the capture on the queue.
 *
 * Two of the five need a rule the bundled pack does not contain: `tng-reload-v1`
 * is the only `confidence: REVIEW` rule that ships and it *also* declares
 * `kind: TRANSFER_SUSPECT` and extracts no merchant, so it fires three
 * conditions at once. See [ParseFixtures.syntheticMatcher].
 */
@RunWith(AndroidJUnit4::class)
class PendingReasonRecordedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)

    private val marker = "ZZ" + System.nanoTime()
    private val base = System.currentTimeMillis()

    @Before
    fun prepare() {
        ParseFixtures.prepare(context)
    }

    /** Condition 1: the matched rule declares `confidence: REVIEW`. */
    @Test
    fun ruleReviewIsRecordedForAReviewConfidenceRule() {
        val id = ParseFixtures.insertCapture(
            context,
            text = "Maybe spent RM7.10 at SOME STALL today",
            title = marker, pkg = ParseFixtures.SYNTHETIC,
            sbnKey = "$marker-review", postedAt = base - 3_000_000L,
        )

        ParseFixtures.pass(context, matcher = ParseFixtures.syntheticMatcher()).run()

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, id))
        assertEquals(710L, txn.amountSen)
        assertEquals(TxnState.PENDING, txn.state)
        assertEquals(PendingReason.RULE_REVIEW, txn.pendingReason)
        assertEquals("synthetic-review-only-v1", captures.byId(id).matchedRuleId)
    }

    /**
     * A merchant that is nothing but payment rail is not a merchant.
     *
     * A pattern can capture `TNG*`, `DUITNOWQR-` or a bare terminal code, each of
     * which `Merchant.clean` reduces to the empty string. Keeping the raw string
     * left the gate seeing a non-null `merchant_raw`, no `MERCHANT_MISSING`, and a
     * straight commit -- with `merchant_display` and `merchant_key` both null,
     * because those *did* drop the empty result, so section 8's `GROUP BY
     * merchant_key` swept it into the NULL bucket.
     *
     * `Txn.merchantKey`'s KDoc has said "null exactly when merchantRaw is" since
     * the column was added. This is what makes that true.
     */
    @Test
    fun aMerchantThatIsOnlyPaymentRailIsMissingRatherThanKept() {
        // A unique amount, well clear of this class's other captures. A null
        // merchant agrees with every merchant by design (spec 7.2 layer 2), so
        // a shared amount would land this row in the review inbox for
        // DUPLICATE_SUSPECT instead -- which is the right answer to a different
        // question, and would not test this one.
        val (sen, rm) = ParseFixtures.uniqueAmount()
        val id = ParseFixtures.insertCapture(
            context,
            text = "Payment of $rm to TNG* successful",
            title = marker, pkg = ParseFixtures.TNG,
            sbnKey = "$marker-rail", postedAt = base - 20_000_000L,
        )

        ParseFixtures.pass(context).run()

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, id))
        assertEquals("the amount is not in doubt", sen, txn.amountSen)
        assertNull(
            "An acquirer prefix on its own was kept as the merchant, so the " +
                "gate saw one and the row committed",
            txn.merchantRaw,
        )
        assertNull(txn.merchantKey)
        assertEquals(TxnState.PENDING, txn.state)
        assertEquals(PendingReason.MERCHANT_MISSING, txn.pendingReason)
    }

    /**
     * The bundled fallback for a DuitNow payment with no one-line summary stores
     * the reason it was written to store.
     *
     * `MERCHANT_MISSING` did fire, into the *set* -- but the rule also declared
     * `confidence: REVIEW`, and `RULE_REVIEW` outranks it in
     * `PendingReasons.PRECEDENCE`. So the column held `RULE_REVIEW` and spec 9.2's
     * card offered the actions for "I am not sure", when the one thing the app
     * needs is the name of the shop. Nothing connected that rule to a stored
     * reason: `ConfidenceGateTest` covers each condition in isolation.
     */
    @Test
    fun theDuitnowFallbackAsksWhoTheMerchantWas() {
        val (sen, rm) = ParseFixtures.uniqueAmount()
        val id = ParseFixtures.insertCapture(
            context,
            // No `text`: that is what sends this past the scoped rule and on to
            // the fallback.
            text = null,
            bigText = "You have paid $rm to Restoran Yuen Kee\nRef: ABC123\nBaki: RM500.00",
            title = marker, pkg = ParseFixtures.MAE,
            sbnKey = "$marker-fallback", postedAt = base - 21_000_000L,
        )

        ParseFixtures.pass(context).run()

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, id))
        assertEquals("duitnow-paid-body-v1", captures.byId(id).matchedRuleId)
        assertEquals("the amount is not the uncertain part", sen, txn.amountSen)
        assertNull(txn.merchantRaw)
        assertEquals(TxnState.PENDING, txn.state)
        assertEquals(
            "The inbox will ask the wrong question: this card says 'I am not " +
                "sure' when the only thing missing is who the shop was",
            PendingReason.MERCHANT_MISSING,
            txn.pendingReason,
        )
    }

    /**
     * Condition 2: the matched rule declares `kind: TRANSFER_SUSPECT`.
     *
     * Isolated on a synthetic rule so that this test says something about
     * `TRANSFER_SUSPECT` alone; the bundled reload rule fires three conditions
     * and is what [transferSuspectOutranksTheWeakerReasonsThatFireWithIt]
     * covers.
     */
    @Test
    fun transferSuspectIsRecordedForATransferSuspectRule() {
        val id = ParseFixtures.insertCapture(
            context,
            text = "Moved RM7.20 to SAVINGS account",
            title = marker, pkg = ParseFixtures.SYNTHETIC,
            sbnKey = "$marker-transfer", postedAt = base - 3_060_000L,
        )

        ParseFixtures.pass(context, matcher = ParseFixtures.syntheticMatcher()).run()

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, id))
        assertEquals(TxnState.PENDING, txn.state)
        assertEquals(PendingReason.TRANSFER_SUSPECT, txn.pendingReason)
        // Spec 7.3: the exclusion is the user's answer in the review inbox, not
        // the parser's. A row already excluded would be absent from every total
        // without anyone having been asked.
        assertEquals(false, txn.isExcluded)
        assertNull(txn.exclusionReason)
    }

    /** Condition 3: an amount was extracted but the merchant is missing. */
    @Test
    fun merchantMissingIsRecordedWhenTheRuleExtractsNoMerchant() {
        val id = ParseFixtures.insertCapture(
            context,
            text = "Charge of RM7.30 applied",
            title = marker, pkg = ParseFixtures.SYNTHETIC,
            sbnKey = "$marker-nomerchant", postedAt = base - 3_120_000L,
        )

        ParseFixtures.pass(context, matcher = ParseFixtures.syntheticMatcher()).run()

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, id))
        assertEquals(730L, txn.amountSen)
        assertEquals(TxnState.PENDING, txn.state)
        assertEquals(PendingReason.MERCHANT_MISSING, txn.pendingReason)
        // Null and not "", so that `merchant_raw IS NULL OR merchant_raw = ''`
        // recomputes this decision exactly, forever.
        assertNull(txn.merchantRaw)
        assertNull(txn.merchantDisplay)
    }

    /** Condition 4: the duplicate detector flagged it (spec 7.2, both layers). */
    @Test
    fun duplicateSuspectIsRecordedForAPairBeyondTheSlotWindow() {
        val text = "Payment of RM7.40 to GATE DUPLICATE successful"
        val first = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-gate-dup", postedAt = base - 3_600_000L,
        )
        val second = ParseFixtures.insertCapture(
            context, text = text, title = marker,
            sbnKey = "$marker-gate-dup", postedAt = base - 3_600_000L + 20 * 60_000L,
        )

        ParseFixtures.pass(context).run()

        assertEquals(
            TxnState.COMMITTED,
            requireNotNull(ParseFixtures.txnForCapture(context, first)).state,
        )
        val txn = requireNotNull(ParseFixtures.txnForCapture(context, second))
        assertEquals(TxnState.PENDING, txn.state)
        assertEquals(PendingReason.DUPLICATE_SUSPECT, txn.pendingReason)
    }

    /** Condition 5: the amount exceeds the threshold, default RM500. */
    @Test
    fun overThresholdIsRecordedForAnAmountAboveTheDefaultThreshold() {
        val id = ParseFixtures.insertCapture(
            context,
            text = "Payment of RM600.00 to BIG TICKET successful",
            title = marker,
            sbnKey = "$marker-over", postedAt = base - 3_180_000L,
        )

        ParseFixtures.pass(context).run()

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, id))
        assertEquals(60_000L, txn.amountSen)
        assertEquals(TxnState.PENDING, txn.state)
        assertEquals(PendingReason.OVER_THRESHOLD, txn.pendingReason)
    }

    /** "Exceeds", so the threshold itself is not over it. */
    @Test
    fun theThresholdBoundaryIsExclusive() {
        val id = ParseFixtures.insertCapture(
            context,
            text = "Payment of RM500.00 to ON THE LINE successful",
            title = marker,
            sbnKey = "$marker-boundary", postedAt = base - 3_240_000L,
        )

        ParseFixtures.pass(context).run()

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, id))
        assertEquals(ConfidenceGate.DEFAULT_THRESHOLD_SEN, txn.amountSen)
        assertEquals(TxnState.COMMITTED, txn.state)
        assertNull(txn.pendingReason)
    }

    /**
     * Three conditions at once, and exactly one recorded.
     *
     * `tng-reload-v1` declares `confidence: REVIEW` **and** `kind:
     * TRANSFER_SUSPECT` and extracts no merchant, so "if any of the following hold"
     * holds three times. `PendingReasons.mostSpecific` picks the transfer question,
     * which decides whether the money counts at all.
     */
    @Test
    fun transferSuspectOutranksTheWeakerReasonsThatFireWithIt() {
        val id = ParseFixtures.insertCapture(
            context,
            text = "Reload of RM100.00 was successful",
            title = marker,
            sbnKey = "$marker-reload", postedAt = base - 3_300_000L,
        )

        ParseFixtures.pass(context).run()

        assertEquals(ParseStatus.MATCHED, captures.byId(id).parseStatus)
        assertEquals("tng-reload-v1", captures.byId(id).matchedRuleId)

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, id))
        assertEquals(10_000L, txn.amountSen)
        assertNull("The reload rule extracts no merchant", txn.merchantRaw)
        assertEquals(TxnState.PENDING, txn.state)
        assertEquals(
            "Three conditions fired; the transfer question is the one to ask",
            PendingReason.TRANSFER_SUSPECT,
            txn.pendingReason,
        )
    }
}
