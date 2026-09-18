package my.pinged.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.entity.ParseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 5.5's re-parse, against the real encrypted database and the pack that
 * ships.
 *
 * Every assertion here is either "the money arrives" or "the money is not
 * counted twice", because those are the only two ways this can be wrong.
 */
@RunWith(AndroidJUnit4::class)
class ReparseTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)
    private val packVersion = Graph.ruleMatcher().packVersion

    /** Unique per test method, so a re-run of this suite cannot collide with itself. */
    private val marker = "ZZ" + System.nanoTime()
    private val base = System.currentTimeMillis()

    /**
     * The sweep is table-wide and this suite shares one database, so a count
     * here is about every row on the table. Leftovers from an earlier test made
     * `aCaptureThatAlreadyPaidIsNeverRequeued` report a paid capture as
     * requeued when what moved was someone else's row.
     *
     * Draining first stamps every row with the current pack version, so only
     * this test's own fixtures can qualify. The mark is cleared before the loop
     * so the first sweep runs, and again after it so the test's own does.
     */
    @Before
    fun prepare() {
        ParseFixtures.prepare(context)
        runBlocking {
            Reparse.forgetSweptVersion(context)
            while (Reparse.sweep(context, captures, packVersion).moved > 0) {
                ParseFixtures.pass(context).run()
            }
            Reparse.forgetSweptVersion(context)
        }
    }

    /** A capture settled at [status] by a pack older than the bundled one. */
    private fun staleCapture(text: String, status: ParseStatus, at: Long): Long {
        val id = ParseFixtures.insertCapture(
            context = context,
            text = text,
            title = "Payment $marker",
            sbnKey = "$marker-$at",
            postedAt = at,
        )
        val marked = captures.markOutcome(
            id = id,
            status = status,
            packVersion = packVersion - 1,
            expected = ParseStatus.NEW,
        )
        assertEquals("The fixture did not reach $status, so nothing below is about re-parse", 1, marked)
        return id
    }

    private fun sweep(limit: Int = Reparse.MAX_REQUEUE_PER_RUN): Reparse.Swept =
        runBlocking { Reparse.sweep(context, captures, packVersion, limit) }

    /**
     * The whole point: a payment captured before the rule that reads it existed
     * becomes a transaction, from the text stored at the time.
     */
    @Test
    fun aPaymentAnOlderPackCouldNotReadBecomesATransaction() {
        val id = staleCapture(
            text = "Versatile Wisdom $marker Sdn Bhd: RM16.80 has been deducted from " +
                "your TNG eWallet. Merchant Reference No. T178745100726",
            status = ParseStatus.UNMATCHED,
            at = base - 9_000L,
        )

        assertEquals("The sweep did not requeue the stale capture", 1, sweep().moved)
        assertEquals(ParseStatus.NEW, captures.byId(id).parseStatus)

        ParseFixtures.pass(context).run()

        val capture = captures.byId(id)
        assertEquals("The requeued capture was not read by the current pack", ParseStatus.MATCHED, capture.parseStatus)
        assertEquals("tng-deducted-v1", capture.matchedRuleId)
        assertEquals(packVersion, capture.packVersion)

        val txn = ParseFixtures.txnForCapture(context, id)
        assertNotNull("The re-parsed capture produced no transaction", txn)
        assertEquals(1680L, txn!!.amountSen)
    }

    /**
     * **The one that must never fail.** A `MATCHED` capture has already written
     * a transaction, so requeueing it is how spending gets counted twice.
     */
    @Test
    fun aCaptureThatAlreadyPaidIsNeverRequeued() {
        val id = ParseFixtures.insertCapture(
            context = context,
            text = "Payment of RM32.00 to 99 SPEEDMART $marker successful",
            title = "Touch 'n Go $marker",
            sbnKey = "$marker-matched",
            postedAt = base - 8_000L,
        )
        ParseFixtures.pass(context).run()
        assertEquals(ParseStatus.MATCHED, captures.byId(id).parseStatus)
        val txnId = ParseFixtures.txnForCapture(context, id)?.id
        assertNotNull("The fixture wrote no transaction, so this proves nothing", txnId)

        // Stamped back to an older pack, so the version filter would admit it
        // and only the status keeps it out. `expected = MATCHED` because the
        // row is no longer NEW, and the rule id is restated so the row stays
        // the one the pass actually wrote.
        captures.markOutcome(
            id = id,
            status = ParseStatus.MATCHED,
            ruleId = "tng-payment-v1",
            packVersion = packVersion - 1,
            expected = ParseStatus.MATCHED,
        )
        assertEquals("A settled, paid capture was requeued", 0, sweep().moved)
        assertEquals(
            "The paid capture left MATCHED, so it would be read again",
            ParseStatus.MATCHED,
            captures.byId(id).parseStatus,
        )
        ParseFixtures.pass(context).run()
        assertEquals(
            "Re-parsing produced a second transaction for one payment",
            txnId,
            ParseFixtures.txnForCapture(context, id)?.id,
        )
    }

    /**
     * ...through the real worker, which is the only thing that runs the sweep in
     * production. Every other test calls [Reparse.sweep] directly and would
     * still pass with the call missing from [ParseWorker] entirely -- correct,
     * and unreachable.
     */
    @Test
    fun theWorkerSweepsBeforeItDrains() {
        val id = staleCapture(
            text = "Kedai Kopi $marker Sdn Bhd: RM4.20 has been deducted from your TNG eWallet.",
            status = ParseStatus.UNMATCHED,
            at = base - 9_500L,
        )

        assertEquals(ListenableWorker.Result.success(), ParseFixtures.runWorker(context))

        assertEquals(
            "The worker drained the queue without sweeping, so the capture is still unread",
            ParseStatus.MATCHED,
            captures.byId(id).parseStatus,
        )
        assertEquals(420L, ParseFixtures.txnForCapture(context, id)?.amountSen)
    }

    /**
     * A pack reject is revisited, which spec 5.5 calls non-optional: fixing an
     * over-broad reject pattern is one of the likeliest reasons to ship a pack,
     * and never revisiting cannot recover the transactions it ate.
     *
     * This asserted the opposite until the spec was re-read. `markOutcome`'s
     * `COALESCE(:rejectId, rejected_by_rule_id)` exists for this path and was
     * unreachable without it.
     */
    @Test
    fun aRejectedCaptureIsRevisitedSoAnOverBroadPatternCanBeUndone() {
        val id = staleCapture(
            text = "Your OTP is 123456 $marker. Do not share it with anyone.",
            status = ParseStatus.REJECTED,
            at = base - 7_000L,
        )
        assertEquals("A pack reject was not revisited", 1, sweep().moved)
        assertEquals(ParseStatus.NEW, captures.byId(id).parseStatus)

        // Still rejected by the current pack, and the evidence of which pattern
        // ate it survives the round trip.
        ParseFixtures.pass(context).run()
        val after = captures.byId(id)
        assertEquals(ParseStatus.REJECTED, after.parseStatus)
        assertEquals("tng-otp", after.rejectedByRuleId)
    }

    /** `GAVE_UP` is not evidence that no rule matches, so it is revisited. */
    @Test
    fun aCaptureThatRanOutOfTimeIsRevisited() {
        val id = staleCapture(
            text = "You have paid RM6.25 for THONG KEE $marker.",
            status = ParseStatus.GAVE_UP,
            at = base - 6_000L,
        )
        assertEquals(1, sweep().moved)
        assertEquals(ParseStatus.NEW, captures.byId(id).parseStatus)
    }

    /** Once per pack version; without the mark this walks the table on every
     * notification. */
    @Test
    fun theSweepRunsOncePerPackVersion() {
        staleCapture(
            text = "You have paid RM1.50 for A SHOP $marker.",
            status = ParseStatus.UNMATCHED,
            at = base - 5_000L,
        )
        assertEquals(1, sweep().moved)
        ParseFixtures.pass(context).run()

        staleCapture(
            text = "You have paid RM2.50 for B SHOP $marker.",
            status = ParseStatus.UNMATCHED,
            at = base - 4_000L,
        )
        assertEquals(
            "The sweep ran again for a version it had already swept",
            0,
            sweep().moved,
        )
    }

    /** A full batch means there is more, and recording the version there would
     * strand whatever did not fit, permanently. */
    @Test
    fun aFullBatchLeavesTheVersionUnsweptSoTheRestIsNotStranded() {
        repeat(3) { n ->
            staleCapture(
                text = "You have paid RM${n + 1}.00 for SHOP $n $marker.",
                status = ParseStatus.UNMATCHED,
                at = base - (3_000L - n * 100L),
            )
        }

        val first = sweep(limit = 2)
        assertEquals("The batch was not bounded by the limit", 2, first.moved)
        assertEquals("A full batch did not report more", true, first.more)
        assertEquals(
            "The version was recorded on a full batch, stranding the remainder",
            1,
            sweep(limit = 2).moved,
        )
    }
}
