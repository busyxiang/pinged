package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.UserTemplateRule
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 5.8's taught rules, against the real encrypted database. */
@RunWith(AndroidJUnit4::class)
class UserTemplateRuleTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private val taught get() = db.userTemplateRuleDao()
    private val captures get() = db.rawCaptureDao()

    private fun rule(pattern: String = "^Paid RM(?<amount>[0-9]+)$") = UserTemplateRule(
        sourcePackage = MAE,
        pattern = pattern,
        direction = Direction.EXPENSE,
        hasMerchantGroup = false,
        sampleText = "Paid RM5",
        createdAt = 1_000L,
    )

    private fun capture(hash: String, status: ParseStatus) =
        captures.insert(sampleCapture(hash = hash, pkg = MAE, text = "Paid RM5", status = status))

    @Test fun savingHandsTheUnreadCapturesBackToStageTwoAndNoOthers() {
        val unread = capture("unread", ParseStatus.UNMATCHED)
        val matched = capture("matched", ParseStatus.MATCHED)
        val hidden = capture("hidden", ParseStatus.UNMATCHED)
        captures.markOutcome(id = hidden, status = ParseStatus.UNMATCHED, expected = ParseStatus.UNMATCHED, userRejectRuleId = 1L)

        val id = taught.teach(rule(), unread = listOf(unread, matched, hidden))

        assertEquals(ParseStatus.NEW, captures.byId(unread).parseStatus)
        assertEquals(ParseStatus.MATCHED, captures.byId(matched).parseStatus)
        assertEquals("a capture the user said is never a transaction", ParseStatus.UNMATCHED, captures.byId(hidden).parseStatus)
        assertEquals(id, taught.all().single().id)
    }

    @Test fun teachingTheSameShapeTwiceKeepsOneRule() {
        val first = taught.teach(rule(), unread = emptyList())
        val second = taught.teach(rule().copy(sampleText = "Paid RM9", direction = Direction.REFUND), unread = emptyList())

        assertEquals(first, second)
        assertEquals(1, taught.countAll())
        assertEquals("the answer given the second time", Direction.REFUND, taught.all().single().direction)
    }

    /** Spec 5.8's first guard counts transactions, so a commit that writes none counts nothing. */
    @Test fun aMatchIsCountedWithItsTransactionAndOnlyOnce() {
        val id = taught.teach(rule(), unread = emptyList())
        val uncategorized = db.categoryDao().requireUncategorizedId()
        val capture = capture("one", ParseStatus.NEW)
        val txn = sampleTxn(rawCaptureId = capture, categoryId = uncategorized)

        captures.commitCapture(captureId = capture, txn = txn, status = ParseStatus.MATCHED, taughtRuleId = id)
        captures.commitCapture(captureId = capture, txn = txn, status = ParseStatus.MATCHED, taughtRuleId = id)

        assertEquals(1, taught.all().single().matchCount)
    }

    @Test fun deletingARuleLeavesTheTransactionsItWrote() {
        val id = taught.teach(rule(), unread = emptyList())
        val capture = capture("one", ParseStatus.NEW)
        val txnId = captures.commitCapture(
            captureId = capture,
            txn = sampleTxn(rawCaptureId = capture, categoryId = db.categoryDao().requireUncategorizedId()),
            status = ParseStatus.MATCHED,
            ruleId = "taught-$id",
            taughtRuleId = id,
        )

        assertEquals(1, taught.delete(id))

        assertEquals(0, taught.countAll())
        assertNotNull(db.txnDao().byId(txnId))
        assertEquals("taught-$id", captures.byId(capture).matchedRuleId)
    }

    @Test fun theSettingsListNamesTheApp() {
        db.captureSourceDao().insertIfNew(enabledSource(MAE))
        taught.teach(rule(), unread = emptyList())

        val row = taught.rows().single()
        assertEquals("Paid RM5", row.sampleText)
        assertEquals(Direction.EXPENSE, row.direction)
        assertEquals(0, row.matchCount)
        assertEquals(MAE, row.label)
    }

    private companion object {
        const val MAE = "com.maybank2u.life"
    }
}
