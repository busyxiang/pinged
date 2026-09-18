package my.pinged.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
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
 * The behaviour under test is the one that cost two releases: a capture the
 * rules of the day could not read is settled at a terminal status, and a later
 * pack that *can* read it never looks. Every assertion here is either "the
 * money arrives" or "the money is not counted twice", because those are the
 * only two ways this can be wrong.
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
     * The sweep is table-wide, and this suite shares one database, so a count
     * returned here is about every capture on the table and not only this
     * test's. Earlier tests leave rows settled at an older pack version, which
     * made `aCaptureThatAlreadyPaidIsNeverRequeued` report a paid capture as
     * requeued when what moved was a leftover from the test before it.
     *
     * Draining first is what makes a count mean something: after this, every
     * row is stamped with the current pack version, so nothing but this test's
     * own fixtures can qualify. The mark is forgotten around each sweep
     * because it would otherwise stop the loop before the table was clear.
     */
    @Before
    fun prepare() {
        ParseFixtures.prepare(context)
        runBlocking {
            while (true) {
                Reparse.forgetSweptVersion(context)
                if (Reparse.sweep(context, captures, packVersion) == 0) break
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

    private fun sweep(limit: Int = Reparse.MAX_ROWS_PER_RUN): Int =
        runBlocking { Reparse.sweep(context, captures, packVersion, limit) }

    /**
     * The whole point: a payment captured before the rule that reads it
     * existed becomes a transaction, from the text that was stored at the time.
     */
    @Test
    fun aPaymentAnOlderPackCouldNotReadBecomesATransaction() {
        val id = staleCapture(
            text = "Versatile Wisdom $marker Sdn Bhd: RM16.80 has been deducted from " +
                "your TNG eWallet. Merchant Reference No. T178745100726",
            status = ParseStatus.UNMATCHED,
            at = base - 9_000L,
        )

        assertEquals("The sweep did not requeue the stale capture", 1, sweep())
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
        assertEquals("A settled, paid capture was requeued", 0, sweep())
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
     * ...through the real worker, which is the only thing that runs the sweep
     * in production.
     *
     * Every other test here calls [Reparse.sweep] directly, so all of them
     * would still pass with the call missing from [ParseWorker] entirely --
     * the feature would be correct and unreachable.
     */
    @Test
    fun theWorkerSweepsBeforeItDrains() {
        val id = staleCapture(
            text = "Kedai Kopi $marker Sdn Bhd: RM4.20 has been deducted from your TNG eWallet.",
            status = ParseStatus.UNMATCHED,
            at = base - 9_500L,
        )

        assertEquals(
            ListenableWorker.Result.success(),
            TestListenableWorkerBuilder<ParseWorker>(context).build().startWork().get(),
        )

        assertEquals(
            "The worker drained the queue without sweeping, so the capture is still unread",
            ParseStatus.MATCHED,
            captures.byId(id).parseStatus,
        )
        assertEquals(420L, ParseFixtures.txnForCapture(context, id)?.amountSen)
    }

    /** A reject was a decision, not a failure to read, so it stays one. */
    @Test
    fun aRejectedCaptureIsLeftAlone() {
        val id = staleCapture(
            text = "Your OTP is 123456 $marker. Do not share it with anyone.",
            status = ParseStatus.REJECTED,
            at = base - 7_000L,
        )
        assertEquals("A deliberate reject was requeued", 0, sweep())
        assertEquals(
            "The rejected capture left REJECTED",
            ParseStatus.REJECTED,
            captures.byId(id).parseStatus,
        )
    }

    /** `GAVE_UP` is not evidence that no rule matches, so it is revisited. */
    @Test
    fun aCaptureThatRanOutOfTimeIsRevisited() {
        val id = staleCapture(
            text = "You have paid RM6.25 for THONG KEE $marker.",
            status = ParseStatus.GAVE_UP,
            at = base - 6_000L,
        )
        assertEquals(1, sweep())
        assertEquals(ParseStatus.NEW, captures.byId(id).parseStatus)
    }

    /**
     * Once per pack version. Without the mark this walks the table on every
     * notification, for an answer that is no except just after an upgrade.
     */
    @Test
    fun theSweepRunsOncePerPackVersion() {
        staleCapture(
            text = "You have paid RM1.50 for A SHOP $marker.",
            status = ParseStatus.UNMATCHED,
            at = base - 5_000L,
        )
        assertEquals(1, sweep())
        ParseFixtures.pass(context).run()

        staleCapture(
            text = "You have paid RM2.50 for B SHOP $marker.",
            status = ParseStatus.UNMATCHED,
            at = base - 4_000L,
        )
        assertEquals(
            "The sweep ran again for a version it had already swept",
            0,
            sweep(),
        )
    }

    /**
     * A full batch means there is more, and must not record the version --
     * doing so would strand whatever did not fit, permanently.
     */
    @Test
    fun aFullBatchLeavesTheVersionUnsweptSoTheRestIsNotStranded() {
        repeat(3) { n ->
            staleCapture(
                text = "You have paid RM${n + 1}.00 for SHOP $n $marker.",
                status = ParseStatus.UNMATCHED,
                at = base - (3_000L - n * 100L),
            )
        }

        assertEquals("The batch was not bounded by the limit", 2, sweep(limit = 2))
        assertEquals(
            "The version was recorded on a full batch, stranding the remainder",
            1,
            sweep(limit = 2),
        )
    }
}
