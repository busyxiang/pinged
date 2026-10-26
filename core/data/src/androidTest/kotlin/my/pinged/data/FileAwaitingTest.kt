package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TxnDao.fileAwaiting`, the review card's category choice for money back
 * (§9.2): one targeted `UPDATE` that files a row still awaiting review under a
 * category and changes nothing else, guarded as `settle` is.
 */
@RunWith(AndroidJUnit4::class)
class FileAwaitingTest {
    private lateinit var db: PingedDatabase
    private var uncategorized = 0L
    private var food = 0L

    @Before fun setUp() {
        db = freshDatabase()
        uncategorized = db.categoryDao().requireUncategorizedId()
        food = db.categoryDao().all().first { it.id != uncategorized }.id
    }

    @After fun tearDown() = db.close()

    private fun pending(reason: PendingReason = PendingReason.RULE_REVIEW) = db.txnDao().insert(
        sampleTxn(state = TxnState.PENDING, pendingReason = reason, direction = Direction.REFUND, categoryId = uncategorized),
    )

    private fun stored(id: Long): List<Any?> {
        val row = db.txnDao().byId(id)!!
        return listOf(row.categoryId, row.userEdited, row.state, row.pendingReason)
    }

    @Test fun aCardStillAwaitingReviewIsFiledAsAOneOffAndStaysACard() {
        val id = pending()

        assertEquals(1, db.txnDao().fileAwaiting(id, PendingReason.RULE_REVIEW, food, NOW))

        assertEquals(
            "Filing must set the category and user_edited, and leave the row awaiting review for its reason",
            listOf(food, true, TxnState.PENDING, PendingReason.RULE_REVIEW),
            stored(id),
        )
    }

    @Test fun aRowAnAnswerHasSettledIsNotFiled() {
        val id = pending()
        assertEquals(1, db.txnDao().settle(id, PendingReason.RULE_REVIEW, TxnState.COMMITTED, NOW))

        assertEquals(0, db.txnDao().fileAwaiting(id, PendingReason.RULE_REVIEW, food, NOW))

        assertEquals(listOf(uncategorized, false, TxnState.COMMITTED, null), stored(id))
    }

    @Test fun aCardDrawnForAnotherReasonIsNotFiled() {
        val id = pending(PendingReason.OVER_THRESHOLD)

        assertEquals(
            "A card a re-read has since moved to another reason was filed from the old card",
            0,
            db.txnDao().fileAwaiting(id, PendingReason.RULE_REVIEW, food, NOW),
        )

        assertEquals(listOf(uncategorized, false, TxnState.PENDING, PendingReason.OVER_THRESHOLD), stored(id))
    }

    @Test fun aCategoryDeletedSinceTheSheetDrewIsNotWritten() {
        val id = pending()

        assertEquals(0, db.txnDao().fileAwaiting(id, PendingReason.RULE_REVIEW, 9_999L, NOW))

        assertEquals(listOf(uncategorized, false, TxnState.PENDING, PendingReason.RULE_REVIEW), stored(id))
    }

    private companion object {
        const val NOW = 5_000L
    }
}
