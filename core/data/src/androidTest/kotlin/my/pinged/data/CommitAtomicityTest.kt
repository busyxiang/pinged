package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.StaleCaptureException
import my.pinged.data.entity.ParseStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage two's two writes -- the transaction and the capture's outcome -- as
 * one unit, and the queue-poisoning they caused when they were two.
 */
@RunWith(AndroidJUnit4::class)
class CommitAtomicityTest {
    private lateinit var db: PingedDatabase
    private var uncategorized = 0L

    @Before fun setUp() {
        db = freshDatabase()
        uncategorized = db.categoryDao().requireUncategorizedId()
    }

    @After fun tearDown() = db.close()

    private fun txnFor(captureId: Long, amountSen: Long = 1_000L) =
        sampleTxn(rawCaptureId = captureId, amountSen = amountSen, categoryId = uncategorized)

    // ---- the happy path -------------------------------------------------

    @Test fun commitWritesTheTransactionAndTakesTheCaptureOffTheQueue() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "one"))

        val txnId = db.rawCaptureDao().commitCapture(
            captureId = capture,
            txn = txnFor(capture),
            status = ParseStatus.MATCHED,
            ruleId = "tng.payment.v1",
            packVersion = 3,
        )

        assertTrue(txnId > 0)
        assertEquals(1, db.txnDao().countAll())
        assertEquals(0, db.rawCaptureDao().claimNext(10).size)
        assertEquals(ParseStatus.MATCHED, db.rawCaptureDao().byId(capture).parseStatus)
        assertEquals("tng.payment.v1", db.rawCaptureDao().byId(capture).matchedRuleId)
    }

    /**
     * Both writes or neither. A bad `category_id` fails the foreign key on the
     * insert (spec 4: SQLite error 787), which must take the `markOutcome`
     * down with it -- otherwise the capture leaves the queue having produced
     * no transaction, and the money is gone with the row marked done.
     */
    @Test fun aFailedInsertRollsBackTheOutcome() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "fk"))

        val failed = runCatching {
            db.rawCaptureDao().commitCapture(
                captureId = capture,
                txn = sampleTxn(rawCaptureId = capture, categoryId = 999_999L),
                status = ParseStatus.MATCHED,
            )
        }

        assertTrue("The foreign key did not fire", failed.isFailure)
        assertEquals(0, db.txnDao().countAll())
        assertEquals(
            "The capture left the queue although no transaction was written",
            ParseStatus.NEW,
            db.rawCaptureDao().byId(capture).parseStatus,
        )
        assertEquals(1, db.rawCaptureDao().claimNext(10).size)
    }

    // ---- the poisoned row ----------------------------------------------

    /**
     * **The state the old two-call sequence produced, and why it did not merely
     * lose work.**
     *
     * Killed between the `Txn` insert and `markOutcome`, the transaction is
     * committed and the capture is still `NEW` -- which sorts *first* under
     * `claimNext`'s `posted_at ASC`, so that row is the very next thing the worker
     * picks up, its re-insert hits the unique index and throws, and a worker that
     * answers a throw with `Result.retry()` stalls at the head of the queue while
     * the listener keeps writing behind it.
     *
     * This reconstructs that state by hand and asserts the commit heals it.
     */
    @Test fun aCommitOverAPoisonedRowHealsInsteadOfStallingTheQueue() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "poison"))
        // Exactly what the process kill left: transaction written, capture NEW.
        val orphan = db.txnDao().insert(txnFor(capture))
        assertEquals(ParseStatus.NEW, db.rawCaptureDao().byId(capture).parseStatus)
        assertEquals(
            "The poisoned row must sort first for this test to mean anything",
            capture,
            db.rawCaptureDao().claimNext(10).first().id,
        )

        val txnId = db.rawCaptureDao().commitCapture(
            captureId = capture,
            txn = txnFor(capture),
            status = ParseStatus.MATCHED,
            ruleId = "tng.payment.v1",
        )

        assertEquals("The existing transaction should be returned, not a new one", orphan, txnId)
        assertEquals("The money was double-counted", 1, db.txnDao().countAll())
        assertEquals(
            "The capture is still NEW, so the queue is still stalled at its head",
            ParseStatus.MATCHED,
            db.rawCaptureDao().byId(capture).parseStatus,
        )
        assertEquals(0, db.rawCaptureDao().claimNext(10).size)
    }

    /** A re-delivered worker on already-finished work is a no-op, not a throw. */
    @Test fun commitIsIdempotentOnWorkThatIsAlreadyFinished() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "twice"))
        val first = db.rawCaptureDao().commitCapture(
            captureId = capture,
            txn = txnFor(capture),
            status = ParseStatus.MATCHED,
        )

        val second = db.rawCaptureDao().commitCapture(
            captureId = capture,
            txn = txnFor(capture, amountSen = 99_999L),
            status = ParseStatus.MATCHED,
        )

        assertEquals(first, second)
        assertEquals(1, db.txnDao().countAll())
        // Spec 5.5: re-parse never modifies an existing transaction.
        assertEquals(1_000L, db.txnDao().recent(10).single().amountSen)
    }

    /**
     * A writer holding a stale read finds nothing to do and writes nothing.
     * Not silence: the capture's outcome was decided by somebody else, and the
     * transaction this writer was carrying was a second opinion on it.
     */
    @Test fun aStaleWriterWritesNothingAndSaysSo() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "stale"))
        // Another writer got there first and rejected it.
        db.rawCaptureDao().markOutcome(
            id = capture,
            status = ParseStatus.REJECTED,
            ruleId = null,
            rejectId = "tng.reject.balance",
            collisionId = null,
            duplicateOf = null,
            packVersion = 2,
        )

        val thrown = assertThrows(StaleCaptureException::class.java) {
            db.rawCaptureDao().commitCapture(
                captureId = capture,
                txn = txnFor(capture),
                status = ParseStatus.MATCHED,
            )
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "stale writer: ${thrown.message}")

        assertEquals("A stale writer created a transaction", 0, db.txnDao().countAll())
        assertEquals(ParseStatus.REJECTED, db.rawCaptureDao().byId(capture).parseStatus)
    }

    // ---- markOutcome's guard -------------------------------------------

    /**
     * Without `AND parse_status = :expected` this update was unconditional, so
     * any writer holding a stale read could revert an outcome another had
     * already committed. Re-parse (spec 5.5) and the live listener both write
     * here, and the case that matters is a `REJECTED` capture a pack fix has
     * just turned into `MATCHED` being pushed back to `REJECTED` by a worker
     * that read it before the fix -- which puts the transaction back in the
     * bin the pack was shipped to empty.
     */
    @Test fun markOutcomeRefusesToWriteOverAnUnexpectedStatus() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "guard"))
        assertEquals(
            1,
            db.rawCaptureDao().markOutcome(
                id = capture,
                status = ParseStatus.MATCHED,
                ruleId = "new.rule",
                rejectId = null,
                collisionId = null,
                duplicateOf = null,
                packVersion = 5,
                expected = ParseStatus.NEW,
            ),
        )

        val rows = db.rawCaptureDao().markOutcome(
            id = capture,
            status = ParseStatus.REJECTED,
            ruleId = null,
            rejectId = "stale.reject",
            collisionId = null,
            duplicateOf = null,
            packVersion = 1,
            expected = ParseStatus.NEW,
        )

        assertEquals("A stale writer overwrote a committed outcome", 0, rows)
        val row = db.rawCaptureDao().byId(capture)
        assertEquals(ParseStatus.MATCHED, row.parseStatus)
        assertEquals("new.rule", row.matchedRuleId)
        assertEquals(5, row.packVersion)
    }

    /**
     * `rejected_by_rule_id` was assigned unconditionally, so the first later
     * write with nothing to say erased it. Spec 5.6's authoring loop is built
     * on knowing which reject pattern ate a capture -- spec 5.5 calls fixing
     * an over-broad reject pattern one of the likeliest reasons to ship a pack
     * at all -- and after a successful re-parse that evidence was exactly what
     * got wiped. The pack that rescued the capture destroyed the record of
     * what needed rescuing.
     */
    @Test fun aLaterOutcomeDoesNotEraseWhichRejectPatternAteTheCapture() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "reject"))
        db.rawCaptureDao().markOutcome(
            id = capture,
            status = ParseStatus.REJECTED,
            ruleId = null,
            rejectId = "tng.reject.balance-summary",
            collisionId = null,
            duplicateOf = null,
            packVersion = 2,
        )
        assertEquals(
            "tng.reject.balance-summary",
            db.rawCaptureDao().byId(capture).rejectedByRuleId,
        )

        // The pack fix lands and re-parse rescues the capture.
        db.rawCaptureDao().commitCapture(
            captureId = capture,
            txn = txnFor(capture),
            status = ParseStatus.MATCHED,
            ruleId = "tng.payment.v2",
            rejectId = null,
            packVersion = 3,
            expected = ParseStatus.REJECTED,
        )

        val row = db.rawCaptureDao().byId(capture)
        assertEquals(ParseStatus.MATCHED, row.parseStatus)
        assertEquals("tng.payment.v2", row.matchedRuleId)
        assertEquals(
            "The record of which reject pattern ate this capture was erased",
            "tng.reject.balance-summary",
            row.rejectedByRuleId,
        )
    }

    /** A caller with a reject id to record still overwrites the old one. */
    @Test fun aNewRejectIdStillReplacesTheOldOne() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "rereject"))
        db.rawCaptureDao().markOutcome(
            id = capture, status = ParseStatus.REJECTED, ruleId = null,
            rejectId = "old.pattern", collisionId = null, duplicateOf = null, packVersion = 1,
        )
        db.rawCaptureDao().markOutcome(
            id = capture, status = ParseStatus.REJECTED, ruleId = null,
            rejectId = "new.pattern", collisionId = null, duplicateOf = null, packVersion = 2,
            expected = ParseStatus.REJECTED,
        )
        assertEquals("new.pattern", db.rawCaptureDao().byId(capture).rejectedByRuleId)
    }

    @Test fun txnIdForCaptureIsNullWhenNothingWasWritten() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "none"))
        assertNull(db.rawCaptureDao().txnIdForCapture(capture))
    }
}
