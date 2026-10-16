package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.TxnState
import my.pinged.parse.ExclusionReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the transaction detail screen (#153) reads and writes: `TxnDao.detail`,
 * and the three writes it offers a captured row beside Not a payment --
 * a note, Not spending and Count as spending.
 */
@RunWith(AndroidJUnit4::class)
class TxnDetailTest {
    private lateinit var db: PingedDatabase
    private var groceries = 0L

    @Before fun setUp() {
        db = freshDatabase()
        groceries = db.categoryId("Groceries")
    }

    @After fun tearDown() = db.close()

    @Test fun detailReadsTheNotificationVerbatimAndTheRuleThatReadIt() {
        val capture = db.rawCaptureDao().insert(
            sampleCapture(hash = "a", text = "Payment of RM32.00 to 99 SPEEDMART successful.", status = ParseStatus.MATCHED)
                .copy(bigText = "  Payment of RM32.00\nto 99 SPEEDMART successful. Balance: RM88.00", matchedRuleId = "TNG-PAYMENT-V1", packVersion = 7),
        )
        val id = db.txnDao().insert(sampleTxn(rawCaptureId = capture, categoryId = groceries))

        val detail = db.txnDao().detail(id)!!

        assertEquals(id, detail.row.txn.id)
        assertEquals("Groceries", detail.categoryName)
        assertEquals("Touch 'n Go eWallet", detail.captureTitle)
        assertEquals("Payment of RM32.00 to 99 SPEEDMART successful.", detail.captureText)
        assertEquals(
            "Read verbatim, the leading spaces and the line break included",
            "  Payment of RM32.00\nto 99 SPEEDMART successful. Balance: RM88.00",
            detail.captureBigText,
        )
        assertEquals("TNG-PAYMENT-V1", detail.matchedRuleId)
        assertEquals(7, detail.packVersion)
    }

    @Test fun aManualEntryHasNoCaptureToRead() {
        val id = db.txnDao().insert(sampleTxn(rawCaptureId = null, pkg = null, categoryId = groceries))

        val detail = db.txnDao().detail(id)!!

        assertNull(detail.captureTitle)
        assertNull(detail.matchedRuleId)
        assertNull("No capture, so no pack read it", detail.packVersion)
    }

    @Test fun aRowThatIsGoneReadsAsNull() {
        assertNull(db.txnDao().detail(404L))
    }

    @Test fun notSpendingLeavesTheMonthTotalAndCountAsSpendingBringsItBack() {
        val id = capturedRow(hash = "b")
        val day = db.txnDao().byId(id)!!.localDate
        val total = { db.txnDao().monthTotals(day, day).sumOf { it.netSen } }
        assertEquals(1_000L, total())

        assertEquals(1, db.txnDao().excludeByUser(id, NOW))
        val excluded = db.txnDao().byId(id)!!
        assertTrue(excluded.isExcluded)
        assertEquals(ExclusionReason.USER, excluded.exclusionReason)
        assertEquals("Not spending is out of every total", 0L, total())
        assertFalse("Not spending is not an edit of the parse", excluded.userEdited)

        assertEquals(1, db.txnDao().countAsSpending(id, NOW))
        val counted = db.txnDao().byId(id)!!
        assertFalse(counted.isExcluded)
        assertNull(counted.exclusionReason)
        assertEquals(1_000L, total())
    }

    @Test fun notSpendingLeavesAPackExclusionAsItIs() {
        val id = transfer(hash = "c")
        val before = db.txnDao().byId(id)

        assertEquals(0, db.txnDao().excludeByUser(id, NOW))
        assertEquals(before, db.txnDao().byId(id))
    }

    @Test fun countAsSpendingLeavesAPackExclusionAsItIs() {
        val id = transfer(hash = "c2")
        val before = db.txnDao().byId(id)

        assertEquals(
            "Count as spending undoes only what the user excluded; a transfer is the inbox's",
            0,
            db.txnDao().countAsSpending(id, NOW),
        )
        assertEquals(before, db.txnDao().byId(id))
    }

    @Test fun countAsSpendingLeavesARejectedRowAsItIs() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "g"))
        val id = db.txnDao().insert(sampleTxn(rawCaptureId = capture, categoryId = groceries, state = TxnState.REJECTED, isExcluded = true))
        val before = db.txnDao().byId(id)

        assertEquals("A rejected row is in no list to count", 0, db.txnDao().countAsSpending(id, NOW))
        assertEquals(before, db.txnDao().byId(id))
    }

    @Test fun notSpendingRefusesARowAwaitingReviewARejectedRowAndAManualEntry() {
        val pendingCapture = db.rawCaptureDao().insert(sampleCapture(hash = "d"))
        val pending = db.txnDao().insert(sampleTxn(rawCaptureId = pendingCapture, categoryId = groceries, state = TxnState.PENDING))
        val rejectedCapture = db.rawCaptureDao().insert(sampleCapture(hash = "e"))
        val rejected = db.txnDao().insert(sampleTxn(rawCaptureId = rejectedCapture, categoryId = groceries, state = TxnState.REJECTED))
        val manual = db.txnDao().insert(sampleTxn(rawCaptureId = null, pkg = null, categoryId = groceries))

        assertEquals("The inbox answers a held row", 0, db.txnDao().excludeByUser(pending, NOW))
        assertEquals(0, db.txnDao().excludeByUser(rejected, NOW))
        assertEquals("A manual entry is deleted, never excluded", 0, db.txnDao().excludeByUser(manual, NOW))
        listOf(pending, rejected, manual).forEach { assertFalse(db.txnDao().byId(it)!!.isExcluded) }
    }

    @Test fun aNoteOnACapturedRowIsStoredTrimmedAndLeavesItUnedited() {
        val id = capturedRow(hash = "f")

        assertEquals(1, db.txnDao().setCapturedNote(id, "  lunch with Aisyah ", NOW))
        val noted = db.txnDao().byId(id)!!
        assertEquals("lunch with Aisyah", noted.note)
        assertFalse("A note is not an edit of what the parser decided", noted.userEdited)
        assertEquals(NOW, noted.updatedAt)

        assertEquals(1, db.txnDao().setCapturedNote(id, "   ", NOW))
        assertNull("A blank note clears it", db.txnDao().byId(id)!!.note)
    }

    @Test fun aNoteIsRefusedOnAManualEntry() {
        val id = db.txnDao().insert(sampleTxn(rawCaptureId = null, pkg = null, categoryId = groceries))

        assertEquals("A manual entry's note is its edit screen's", 0, db.txnDao().setCapturedNote(id, "x", NOW))
        assertNull(db.txnDao().byId(id)!!.note)
    }

    private fun transfer(hash: String): Long {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = hash))
        return db.txnDao().insert(
            sampleTxn(rawCaptureId = capture, categoryId = groceries, isExcluded = true, exclusionReason = ExclusionReason.TRANSFER),
        )
    }

    private fun capturedRow(hash: String): Long {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = hash))
        return db.txnDao().insert(sampleTxn(rawCaptureId = capture, categoryId = groceries))
    }

    private companion object {
        const val NOW = 9_000L
    }
}
