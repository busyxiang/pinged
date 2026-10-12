package my.pinged.data

import android.database.SQLException
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.Answered
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TxnDao.settleAll`, the review inbox's Accept all (§9.2): each row guarded
 * as one answer is, and the batch one transaction. The holder's side -- which
 * cards go in, and the Undo -- is `AcceptAllTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class SettleAllTest {
    private lateinit var db: PingedDatabase
    private var uncategorized = 0L

    @Before fun setUp() {
        db = freshDatabase()
        uncategorized = db.categoryDao().requireUncategorizedId()
    }

    @After fun tearDown() = db.close()

    @Test fun everyRowStillAwaitingItsReasonIsSettledAndOnlyThoseAreReported() {
        val dao = db.txnDao()
        val first = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = 1_000L))
        val moved = dao.insert(pending(PendingReason.RULE_REVIEW, at = 2_000L))
        val last = dao.insert(pending(PendingReason.MERCHANT_MISSING, at = 3_000L))
        val asked = listOf(
            Answered(first, PendingReason.OVER_THRESHOLD, TxnState.COMMITTED),
            Answered(moved, PendingReason.RULE_REVIEW, TxnState.COMMITTED),
            Answered(last, PendingReason.MERCHANT_MISSING, TxnState.COMMITTED),
        )
        // A Corrections re-read between the cards being drawn and the tap.
        sql("UPDATE txn SET pending_reason = 'MERCHANT_MISSING' WHERE id = $moved")

        val wrote = dao.settleAll(asked, NOW)

        assertEquals(
            "Accept all reported rows other than the ones it wrote, so its Undo would take back the wrong set",
            listOf(asked[0], asked[2]),
            wrote,
        )
        assertEquals(
            "Accept all did not commit exactly the rows still awaiting the reason their cards showed",
            listOf(
                Row(first, "COMMITTED", null),
                Row(moved, "PENDING", "MERCHANT_MISSING"),
                Row(last, "COMMITTED", null),
            ),
            rows(),
        )
    }

    @Test fun aBatchThatFailsPartWayWritesNothing() {
        val dao = db.txnDao()
        val first = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = 1_000L))
        val last = dao.insert(pending(PendingReason.RULE_REVIEW, at = 2_000L))
        // The last row's write fails, so the first has been committed when it
        // does: only one transaction around the batch can take it out again.
        sql(
            "CREATE TEMP TRIGGER refuse_last BEFORE UPDATE OF state ON txn WHEN OLD.id = $last " +
                "BEGIN SELECT RAISE(ABORT, 'refused by the test'); END",
        )
        val before = rows()

        try {
            dao.settleAll(
                listOf(
                    Answered(first, PendingReason.OVER_THRESHOLD, TxnState.COMMITTED),
                    Answered(last, PendingReason.RULE_REVIEW, TxnState.COMMITTED),
                ),
                NOW,
            )
            fail("precondition: the trigger did not refuse the last row")
        } catch (_: SQLException) {
            // The refusal; what matters is what it left behind.
        }

        assertEquals("Accept all left part of a failed batch written: it is one transaction", before, rows())
    }

    private fun pending(reason: PendingReason, at: Long) =
        sampleTxn(occurredAt = at, categoryId = uncategorized, state = TxnState.PENDING, pendingReason = reason)

    private data class Row(val id: Long, val state: String, val reason: String?)

    private fun rows(): List<Row> =
        db.openHelper.readableDatabase
            .query("SELECT id, state, pending_reason FROM txn ORDER BY id")
            .use { c -> buildList { while (c.moveToNext()) add(Row(c.getLong(0), c.getString(1), c.getString(2))) } }

    private fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

    private companion object {
        const val NOW = 9_000L
    }
}
