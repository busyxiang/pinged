package my.pinged.capture

import my.pinged.data.Databases
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import my.pinged.data.LocalDates
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.TxnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage two through the real worker, against the real encrypted database and
 * the pack that ships.
 *
 * The dedup layers and the confidence gate get their own files; this one is
 * about the worker itself -- that it is constructible the way WorkManager
 * constructs it, that it finds the bundled pack, that it drains the queue, and
 * that running it twice does not create money.
 */
@RunWith(AndroidJUnit4::class)
class ParseWorkerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)
    private val txns = Databases.txnDao(context)

    /** Unique per test method, so a re-run of this suite cannot collide with itself. */
    private val marker = "ZZ" + System.nanoTime()
    private val base = System.currentTimeMillis()

    @Before
    fun prepare() {
        ParseFixtures.prepare(context)
    }

    private fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<ParseWorker>(context).build().startWork().get()

    /**
     * The whole job, once, on a payload stage one would actually have written.
     *
     * Deliberately not a hand-built [my.pinged.data.entity.RawCapture]: a real
     * `Notification` with the amount in `EXTRA_TEXT`, wrapped in a real
     * `StatusBarNotification`, put through [CaptureIngest] -- the allow-list gate,
     * `getCharSequence` extraction, `sbn.postTime`, `content_hash` -- and only then
     * through the worker.
     */
    @Test
    fun aRealNotificationBecomesACommittedTransactionOfTheRightSen() {
        val postedAt = base - 5_000L
        val captureId = runBlocking {
            CaptureIngest.ingest(
                context = context,
                sbn = CaptureFixtures.posted(
                    CaptureFixtures.notification(
                        context,
                        title = "Touch 'n Go $marker",
                        text = "Payment of RM32.00 to 99 SPEEDMART successful",
                    ),
                    pkg = ParseFixtures.TNG,
                    id = 4_242,
                    tag = marker,
                    postTime = postedAt,
                ),
                arrival = Arrival.POSTED,
                now = postedAt,
            )
        }
        assertNotNull("Stage one stored nothing, so there is no stage two to test", captureId)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val capture = captures.byId(captureId!!)
        assertEquals(ParseStatus.MATCHED, capture.parseStatus)
        assertEquals("tng-payment-v1", capture.matchedRuleId)
        assertEquals(Graph.ruleMatcher().packVersion, capture.packVersion)
        assertNull(capture.duplicateOfId)

        val txn = ParseFixtures.txnForCapture(context, captureId)
        assertNotNull("The capture produced no transaction", txn)
        requireNotNull(txn)

        assertEquals("RM32.00 must be 3200 sen and nothing else", 3_200L, txn.amountSen)
        assertEquals("MYR", txn.currency)
        assertEquals(captureId, txn.rawCaptureId)
        // Spec 5.4: merchant_raw is preserved as parsed; the cleanup lands on
        // merchant_display.
        assertEquals("99 SPEEDMART", txn.merchantRaw)
        assertEquals("99 Speedmart", txn.merchantDisplay)
        assertEquals(postedAt, txn.occurredAt)
        assertEquals(LocalDates.of(postedAt), txn.localDate)
        assertEquals(ParseFixtures.TNG, txn.sourcePackage)
        assertEquals("Touch 'n Go eWallet", txn.sourceLabel)
        assertEquals(TxnState.COMMITTED, txn.state)
        assertNull("A committed row must carry no review reason", txn.pendingReason)
        assertEquals(false, txn.isExcluded)
        assertNull(txn.exclusionReason)
    }

    /**
     * A merchant that capitalised itself keeps its capitalisation, through the
     * production composition rather than a direct call to the title-caser.
     *
     * Spec 5.4 title-cases "only when the raw string is entirely uppercase", and
     * that guard could not fail to pass while [ParsePass] called
     * `Merchant.display(Merchant.clean(raw))` -- `clean` uppercases, because spec
     * 6.1 keys learned rules off its output. `foodpanda KLCC` was stored as
     * `Foodpanda Klcc`.
     *
     * Asserting against `Merchant.displayFor` directly would prove nothing: the old
     * `display` was also correct when called on the raw string, and the bug was
     * entirely in what [ParsePass] handed it.
     */
    @Test
    fun aMerchantThatCapitalisedItselfKeepsItsCapitalisation() {
        val (sen, rm) = ParseFixtures.uniqueAmount()
        val postedAt = base - 6_000L
        val captureId = runBlocking {
            CaptureIngest.ingest(
                context = context,
                sbn = CaptureFixtures.posted(
                    CaptureFixtures.notification(
                        context,
                        title = "Touch 'n Go $marker",
                        text = "Payment of $rm to foodpanda KLCC successful",
                    ),
                    pkg = ParseFixtures.TNG,
                    id = 4_243,
                    tag = "$marker-mixed",
                    postTime = postedAt,
                ),
                arrival = Arrival.POSTED,
                now = postedAt,
            )
        }
        assertNotNull("Stage one stored nothing, so there is no stage two to test", captureId)

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val txn = ParseFixtures.txnForCapture(context, captureId!!)
        assertNotNull("The capture produced no transaction", txn)
        requireNotNull(txn)

        assertEquals(sen, txn.amountSen)
        // Spec 5.4 and spec 4: merchant_raw is preserved exactly as parsed.
        assertEquals("foodpanda KLCC", txn.merchantRaw)
        assertEquals(
            "The ledger shows a name the notification never said. This is the " +
                "value ParsePass writes, so a display path that only preserves " +
                "case when called directly does not fix it.",
            "foodpanda KLCC",
            txn.merchantDisplay,
        )
    }

    /**
     * The bundled pack is reached as a classpath resource, not as an asset.
     *
     * If this fails, nothing else in stage two works and it fails in the most
     * misleading way available: every capture is recorded `UNMATCHED`, which
     * reads as a rule gap on spec 5.6's authoring screen rather than as a
     * packaging mistake.
     */
    @Test
    fun theBundledPackIsOnTheClasspath() {
        val matcher = Graph.ruleMatcher()
        assertTrue("pack_version should be a real pack version", matcher.packVersion > 0)
        assertTrue(
            "The bundled pack must have rules for the packages it names",
            matcher.match(
                ParseFixtures.TNG,
                title = null,
                text = "Payment of RM1.00 to A SHOP successful",
                bigText = null,
            ) is my.pinged.parse.MatchOutcome.Matched,
        )
    }

    /**
     * The invariant that matters most here: a capture stuck at `NEW` is a
     * silently lost transaction, which is the failure the two-stage design
     * exists to prevent.
     *
     * The three payloads cover the three ways a capture can end without a
     * transaction, so none of them may leave the queue occupied.
     */
    @Test
    fun everyCaptureLeavesTheNewState() {
        val matched = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "Payment of RM1.11 to A SHOP successful",
            sbnKey = "$marker-a",
            postedAt = base - 900_000L,
        )
        val rejected = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "TAC 123456 for RM250.00 transfer. Do not share.",
            sbnKey = "$marker-b",
            postedAt = base - 800_000L,
        )
        val unmatched = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "Your FD of RM5,000.00 has matured.",
            sbnKey = "$marker-c",
            postedAt = base - 700_000L,
        )

        assertEquals(ListenableWorker.Result.success(), runWorker())

        assertEquals(ParseStatus.MATCHED, captures.byId(matched).parseStatus)
        assertEquals(ParseStatus.REJECTED, captures.byId(rejected).parseStatus)
        assertEquals("tng-otp", captures.byId(rejected).rejectedByRuleId)
        assertEquals(ParseStatus.UNMATCHED, captures.byId(unmatched).parseStatus)

        assertNull(
            "A rejected capture must produce no transaction",
            captures.txnIdForCapture(rejected),
        )
        assertNull(
            "An unmatched capture must produce no transaction",
            captures.txnIdForCapture(unmatched),
        )
        assertEquals(
            "The queue must be empty after a successful run",
            emptyList<Long>(),
            captures.claimNext(200).map { it.id },
        )
    }

    /**
     * Running the worker twice must not create money.
     *
     * The transaction id is asserted rather than a count, because that is the
     * thing that must not change: a second row for the same capture is what
     * the unique index on `txn.raw_capture_id` refuses, and a *replacement*
     * row would keep the count right while losing whatever the user had done
     * to the first one.
     */
    @Test
    fun aRerunDoesNotDoubleCount() {
        val id = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "Payment of RM2.22 to RERUN SHOP successful",
            sbnKey = "$marker-rerun",
            postedAt = base - 600_000L,
        )

        runWorker()
        val first = captures.txnIdForCapture(id)
        assertNotNull("The first run must produce a transaction", first)
        val before = txns.countAll()

        runWorker()

        assertEquals(first, captures.txnIdForCapture(id))
        assertEquals("The second run must write nothing", before, txns.countAll())
        assertEquals(ParseStatus.MATCHED, captures.byId(id).parseStatus)
    }

    /**
     * `notification.when` displaces `sbn.postTime` only when it is plausible, and
     * "plausible" is one-sided. The plan's sketch used
     * `abs(when - postTime) < 7 days`, which accepts a `when` a week in the
     * *future* -- dating a transaction into a month that has not happened, the same
     * class of bug as spec 4's zero `when` dating one to 1970.
     */
    @Test
    fun aFutureWhenIsIgnoredAndAModestlyEarlierOneIsTrusted() {
        val postedAt = base - 400_000L

        val trusted = ParseFixtures.insertCapture(
            context,
            title = "$marker-when-ok",
            text = "Payment of RM3.33 to WHEN OK successful",
            sbnKey = "$marker-when-ok",
            postedAt = postedAt,
            whenMillis = postedAt - 2 * 60 * 60 * 1000L,
        )
        val future = ParseFixtures.insertCapture(
            context,
            title = "$marker-when-future",
            text = "Payment of RM3.34 to WHEN FUTURE successful",
            sbnKey = "$marker-when-future",
            postedAt = postedAt,
            whenMillis = postedAt + 2 * 60 * 60 * 1000L,
        )
        val ancient = ParseFixtures.insertCapture(
            context,
            title = "$marker-when-old",
            text = "Payment of RM3.35 to WHEN OLD successful",
            sbnKey = "$marker-when-old",
            postedAt = postedAt,
            whenMillis = postedAt - 5 * 24 * 60 * 60 * 1000L,
        )

        runWorker()

        assertEquals(postedAt - 2 * 60 * 60 * 1000L, occurredAt(trusted))
        assertEquals("A `when` in the future must not date the transaction", postedAt, occurredAt(future))
        assertEquals("A `when` days earlier must not date the transaction", postedAt, occurredAt(ancient))
    }

    private fun occurredAt(captureId: Long): Long {
        val txn = ParseFixtures.txnForCapture(context, captureId)
        assertNotNull("Capture $captureId produced no transaction", txn)
        return requireNotNull(txn).occurredAt
    }
}
