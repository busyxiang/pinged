package my.pinged.capture

import my.pinged.data.Databases
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.1's gate as stage two stores it: the reason actually written to
 * `txn.pending_reason`, not the one the gate returned.
 *
 * The column is what matters: spec 9.2's inbox reads the row, not the parser.
 * A `PENDING` row with no reason is refused at the write path
 * ([my.pinged.data.entity.requireStorable]), so a gate that decided `PENDING`
 * without naming a reason would take the whole `commitCapture` transaction down
 * and leave the capture on the queue.
 *
 * Each condition on its own is `FirstReadingTest`'s, on the JVM. What stays
 * here needs the device: ICU's reading of a bundled rule, layer one, and the
 * check limit as the worker reads it from the store.
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

    /** The limit is one value for the whole device: left set, every later suite gates by it. */
    @After
    fun resetTheCheckLimit() = runBlocking { CheckLimit.reset(context) }

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

    /**
     * #362: stage two gates by the limit the user saved, read by the worker
     * itself. At RM1,000 an RM800 payment counts, an RM1,200 one waits, and
     * RM1,000.00 exactly counts.
     */
    @Test
    fun theWorkerHoldsByTheSavedCheckLimit() {
        runBlocking { CheckLimit.set(context, 100_000L) }
        fun payment(rm: String, n: Int) = ParseFixtures.insertCapture(
            context,
            text = "Payment of $rm to LIMIT $n $marker successful",
            title = marker,
            sbnKey = "$marker-limit-$n", postedAt = base - 3_360_000L - n * 60_000L,
        )
        val under = payment("RM800.00", 1)
        val over = payment("RM1,200.00", 2)
        val at = payment("RM1,000.00", 3)

        ParseFixtures.runWorker(context)

        fun stored(id: Long) = requireNotNull(ParseFixtures.txnForCapture(context, id)).let { Triple(it.amountSen, it.state, it.pendingReason) }
        assertEquals("RM800 was held under RM1,000", Triple(80_000L, TxnState.COMMITTED, null), stored(under))
        assertEquals(Triple(120_000L, TxnState.PENDING, PendingReason.OVER_THRESHOLD), stored(over))
        assertEquals("The boundary is not strict", Triple(100_000L, TxnState.COMMITTED, null), stored(at))
    }

}
