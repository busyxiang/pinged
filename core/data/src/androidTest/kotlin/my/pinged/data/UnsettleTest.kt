package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.Answered
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.parse.ExclusionReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TxnDao.unsettle`, the review inbox's Undo (§9.2), over a set of rows:
 * every state an answer can write is taken back by it, and a set returns all
 * together or not at all. The set is what Accept all undoes; the one-row case
 * is `ReviewInboxTest`'s, through the holder.
 */
@RunWith(AndroidJUnit4::class)
class UnsettleTest {
    private lateinit var db: PingedDatabase
    private var uncategorized = 0L

    @Before fun setUp() {
        db = freshDatabase()
        uncategorized = db.categoryDao().requireUncategorizedId()
    }

    @After fun tearDown() = db.close()

    @Test fun everyStateAnAnswerWritesIsTakenBackToItsReason() {
        val answered = answerThree()

        assertTrue("Undo of a set no other writer touched reported a miss", db.txnDao().unsettle(answered, NOW))

        assertEquals(
            "Undo did not return every row to awaiting review for its own reason, counted in and unexcluded",
            answered.map { Row(it.id, "PENDING", it.reason!!.name, 0, null) },
            rows(),
        )
    }

    @Test fun aSetReturnsAllTogetherOrNotAtAll() {
        val answered = answerThree()
        val moved = answered.last().id
        // The last row of the set, so the two before it have been put back
        // when the miss is found: only a rollback can take them out again.
        sql("UPDATE txn SET exclusion_reason = 'USER' WHERE id = $moved")
        val before = rows()

        assertFalse("Undo reported success over a row another writer changed", db.txnDao().unsettle(answered, NOW))

        assertEquals(
            "Undo of a set with one moved row put some of it back: all of them return, or none do",
            before,
            rows(),
        )
    }

    /**
     * Three rows awaiting review, answered three ways: Accept, Not a payment,
     * and Leave out (written here by hand until its own answer exists).
     */
    private fun answerThree(): List<Answered> {
        val dao = db.txnDao()
        val accepted = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = 1_000L))
        val rejected = dao.insert(pending(PendingReason.RULE_REVIEW, at = 2_000L))
        val leftOut = dao.insert(pending(PendingReason.TRANSFER_SUSPECT, at = 3_000L))
        assertEquals(1, dao.settle(accepted, PendingReason.OVER_THRESHOLD, TxnState.COMMITTED, NOW))
        assertEquals(1, dao.settle(rejected, PendingReason.RULE_REVIEW, TxnState.REJECTED, NOW))
        sql(
            "UPDATE txn SET state = 'COMMITTED', pending_reason = NULL, is_excluded = 1, " +
                "exclusion_reason = 'TRANSFER' WHERE id = $leftOut",
        )
        return listOf(
            Answered(accepted, PendingReason.OVER_THRESHOLD, TxnState.COMMITTED),
            Answered(rejected, PendingReason.RULE_REVIEW, TxnState.REJECTED),
            Answered(leftOut, PendingReason.TRANSFER_SUSPECT, TxnState.COMMITTED, ExclusionReason.TRANSFER),
        )
    }

    private fun pending(reason: PendingReason, at: Long) =
        sampleTxn(occurredAt = at, categoryId = uncategorized, state = TxnState.PENDING, pendingReason = reason)

    private data class Row(val id: Long, val state: String, val reason: String?, val excluded: Int, val exclusion: String?)

    private fun rows(): List<Row> =
        db.openHelper.readableDatabase
            .query("SELECT id, state, pending_reason, is_excluded, exclusion_reason FROM txn ORDER BY id")
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Row(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4)))
                    }
                }
            }

    private fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

    private companion object {
        const val NOW = 9_000L
    }
}
