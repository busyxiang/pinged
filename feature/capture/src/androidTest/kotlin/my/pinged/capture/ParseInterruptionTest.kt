package my.pinged.capture

import my.pinged.data.Databases
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CancellationException
import my.pinged.data.entity.ParseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What happens when stage two is killed halfway.
 *
 * Not hypothetical: spec 11.1's own example is an OEM process killer arriving
 * mid-write. The two properties that have to survive it are that money is never
 * counted twice and that the queue never stalls -- and the second used to fail
 * catastrophically, because a poisoned row sorts *first* under `claimNext`'s
 * `posted_at ASC` and took every later capture down with it.
 */
@RunWith(AndroidJUnit4::class)
class ParseInterruptionTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)

    private val marker = "ZZ" + System.nanoTime()
    private val base = System.currentTimeMillis()

    @Before
    fun prepare() {
        ParseFixtures.prepare(context)
    }

    private fun amountOf(captureId: Long): Long =
        requireNotNull(ParseFixtures.txnForCapture(context, captureId)) {
            "Capture $captureId produced no transaction"
        }.amountSen

    /**
     * Killed after two of four captures: the two are committed, the other two are
     * still `NEW`, and the next run finishes without re-creating anything.
     *
     * The transaction ids are compared across the two runs, because "no
     * double-counted money" is not only about the total: a second insert would be
     * refused by the unique index, but a *replacement* would keep every count right
     * and quietly discard whatever the user had already done to the row.
     */
    @Test
    fun aKillMidBatchCommitsWhatItFinishedAndTheNextRunResumes() {
        val ids = (0..3).map { i ->
            ParseFixtures.insertCapture(
                context,
                text = "Payment of RM1.0$i to KILL SHOP $i successful",
                title = marker,
                sbnKey = "$marker-kill-$i",
                postedAt = base - 4_000_000L + i * 1_000L,
            )
        }

        val finished = mutableListOf<Long>()
        val interrupted = runCatching {
            ParseFixtures.pass(
                context,
                onCaptureFinished = { id ->
                    finished += id
                    if (finished.size == 2) {
                        throw CancellationException("simulated process death mid-batch")
                    }
                },
            ).run()
        }

        assertTrue(
            "The pass should have been interrupted, not completed",
            interrupted.exceptionOrNull() is CancellationException,
        )

        val committedFirst = ids.take(2).map { captures.txnIdForCapture(it) }
        committedFirst.forEach { assertNotNull("A finished capture must have its transaction", it) }
        ids.take(2).forEach { assertEquals(ParseStatus.MATCHED, captures.byId(it).parseStatus) }

        ids.drop(2).forEach {
            assertEquals(
                "An unreached capture must still be on the queue",
                ParseStatus.NEW,
                captures.byId(it).parseStatus,
            )
            assertNull(captures.txnIdForCapture(it))
        }

        val summary = ParseFixtures.pass(context).run()
        assertEquals(0, summary.failed)

        assertEquals(
            "The resumed run must not re-create the transactions the first one wrote",
            committedFirst,
            ids.take(2).map { captures.txnIdForCapture(it) },
        )
        ids.forEach { assertEquals(ParseStatus.MATCHED, captures.byId(it).parseStatus) }
        assertEquals(
            "One transaction per capture and no more",
            4,
            ids.mapNotNull { captures.txnIdForCapture(it) }.distinct().size,
        )
        assertEquals(
            "RM1.00 + RM1.01 + RM1.02 + RM1.03, counted exactly once each",
            100L + 101L + 102L + 103L,
            ids.sumOf { amountOf(it) },
        )
    }

    /**
     * The specific failure `commitCapture` exists for: a transaction written whose
     * capture never left `NEW`.
     *
     * Unreachable from stage two now -- the two writes are one `@Transaction` -- so
     * it is manufactured here. It is still reachable in the field: a row left this
     * way by a build predating `commitCapture` is on disk, and it is the shape that
     * poisoned the whole queue.
     */
    @Test
    fun aTransactionWrittenWithoutItsOutcomeHealsInsteadOfPoisoningTheQueue() {
        val head = ParseFixtures.insertCapture(
            context,
            text = "Payment of RM9.01 to POISON HEAD successful",
            title = marker,
            sbnKey = "$marker-poison-head",
            postedAt = base - 4_200_000L,
        )
        val behind = ParseFixtures.insertCapture(
            context,
            text = "Payment of RM9.02 to BEHIND THE HEAD successful",
            title = marker,
            sbnKey = "$marker-poison-behind",
            postedAt = base - 4_200_000L + 1_000L,
        )

        val orphan = ParseFixtures.orphanTransaction(context, head, amountSen = 901L)
        assertEquals(ParseStatus.NEW, captures.byId(head).parseStatus)

        val summary = ParseFixtures.pass(context).run()

        assertEquals("A poisoned row must not be recorded as a failure", 0, summary.failed)
        assertEquals(
            "The stored transaction wins; a second one is never written",
            orphan,
            captures.txnIdForCapture(head),
        )
        assertEquals(
            "The heal is the capture leaving the queue",
            ParseStatus.MATCHED,
            captures.byId(head).parseStatus,
        )
        assertNotNull(
            "The row behind the poisoned head must be processed, not stalled",
            captures.txnIdForCapture(behind),
        )
        assertEquals(902L, amountOf(behind))
        assertEquals(
            "No capture may be left on the queue",
            emptyList<Long>(),
            captures.claimNext(200).map { it.id },
        )
    }

    /**
     * Spec 15.5: bounded. A run that hits its row cap reports work remaining
     * rather than working until WorkManager's ten-minute limit kills it at an
     * arbitrary point, and the next run resumes from the table.
     */
    @Test
    fun aRunThatHitsItsRowCapReportsWorkRemainingAndTheNextOneResumes() {
        // Drain whatever the rest of this APK's tests left on the queue first,
        // so the cap below applies to this test's own two rows and to nothing
        // else. The database is shared and it survives the run.
        ParseFixtures.pass(context).run()

        val ids = (0..1).map { i ->
            ParseFixtures.insertCapture(
                context,
                text = "Payment of RM2.0$i to CAPPED SHOP $i successful",
                title = marker,
                sbnKey = "$marker-cap-$i",
                postedAt = base - 4_400_000L + i * 1_000L,
            )
        }

        val capped = ParseFixtures.pass(context, maxRows = 1).run()

        assertEquals(1, capped.processed)
        assertTrue("A capped run has work left", capped.remaining)

        val finishing = ParseFixtures.pass(context).run()
        assertEquals(1, finishing.processed)
        ids.forEach { assertEquals(ParseStatus.MATCHED, captures.byId(it).parseStatus) }
        assertEquals(200L + 201L, ids.sumOf { amountOf(it) })
    }
}
