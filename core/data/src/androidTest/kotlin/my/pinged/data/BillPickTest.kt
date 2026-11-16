package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.ManualEdit
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reads and writes behind spec 7.4's linking screens (#392, step 4): what
 * the bill chooser offers and finds, the inbox card's guarded pick, a typed
 * payback's insert, and the edit that may not leave a bill after its payback
 * in either direction (spec 9.4).
 */
@RunWith(AndroidJUnit4::class)
class BillPickTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() {
        db = freshDatabase()
    }

    @After fun tearDown() = db.close()

    // ---- the chooser's offer ---------------------------------------------

    @Test fun theChooserOffersOnlyCountedExpensesInTheWindowNewestFirst() {
        val older = expense(DAY_1, name = "Older")
        val newer = expense(DAY_2, name = "Newer")
        expense(DAY_4, name = "After the payback")
        expense(DAY_1 - 10 * DAY, name = "Before the window")
        insert(sampleTxn(occurredAt = DAY_2, isExcluded = true))
        insert(sampleTxn(occurredAt = DAY_2, state = TxnState.PENDING))
        insert(sampleTxn(occurredAt = DAY_2, state = TxnState.REJECTED))
        insert(sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND))
        insert(sampleTxn(occurredAt = DAY_2, currency = "USD"))

        val offered = db.txnDao().billChoices("MYR", LocalDates.of(DAY_1 - 2 * DAY), LocalDates.of(DAY_3), 200)

        assertEquals(listOf(newer, older), offered.map { it.txn.id })
        assertEquals("Newer", offered.first().displayName)
    }

    @Test fun aBillsPaidBackHoldsOnlyItsCountedPaybacks() {
        val bill = expense(DAY_1, amountSen = 9_600L)
        payback(bill, 3_200L)
        payback(bill, 3_200L)
        val held = insert(sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND, state = TxnState.PENDING, amountSen = 1_000L))
        db.txnDao().setBillAwaiting(held, PendingReason.RULE_REVIEW, bill, NOW)
        val leftOut = insert(sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND, amountSen = 500L, isExcluded = true))
        assertEquals(1, db.txnDao().setBill(leftOut, bill, NOW))

        val choice = db.txnDao().billChoice(bill)!!

        assertEquals(6_400L, choice.paidBackSen)
        assertEquals(3_200L, choice.shareSen)
    }

    @Test fun aSearchFindsByNameOrAmountOverTheWholeHistory() {
        val dinner = expense(DAY_1 - 300 * DAY, name = "Yuen Kee", amountSen = 9_600L)
        val groceries = expense(DAY_1, name = "Mydin USJ", amountSen = 6_400L)
        expense(DAY_2, name = "Tealive", amountSen = 890L)
        expense(DAY_4, name = "Yuen Kee", amountSen = 6_400L) // after the payback

        fun search(text: String, sen: Long?) =
            db.txnDao().billSearchRaw(BillSearchSql.search(text, sen, "MYR", LocalDates.of(DAY_3), 200)!!).map { it.txn.id }

        assertEquals(listOf(dinner), search("yuen", null))
        assertEquals(listOf(groceries), search("64", 6_400L))
        assertNull("punctuation searched for something", BillSearchSql.search("·", null, "MYR", LocalDates.of(DAY_3), 200))
    }

    // ---- the inbox card's pick -------------------------------------------

    @Test fun theCardsPickIsGuardedOnTheCardItDrew() {
        val bill = expense(DAY_1)
        val held = insert(sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND, state = TxnState.PENDING))

        assertEquals("another reason's card", 0, db.txnDao().setBillAwaiting(held, PendingReason.MERCHANT_MISSING, bill, NOW))
        assertEquals(1, db.txnDao().setBillAwaiting(held, PendingReason.RULE_REVIEW, bill, NOW))
        assertEquals(bill, billOf(held))
        assertEquals("the pick answered the card", TxnState.PENDING, db.txnDao().byId(held)!!.state)

        assertEquals(1, db.txnDao().clearBillAwaiting(held, PendingReason.RULE_REVIEW, NOW))
        assertNull(billOf(held))
    }

    @Test fun theCardsPickIsRefusedOnceTheRowIsAnswered() {
        val bill = expense(DAY_1)
        val answered = refund(DAY_2)

        assertEquals(0, db.txnDao().setBillAwaiting(answered, PendingReason.RULE_REVIEW, bill, NOW))
        assertNull(billOf(answered))
    }

    @Test fun theCardsPickHoldsSetBillsGuards() {
        val later = expense(DAY_3)
        val held = insert(sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND, state = TxnState.PENDING))

        assertEquals(0, db.txnDao().setBillAwaiting(held, PendingReason.RULE_REVIEW, later, NOW))
        assertNull(billOf(held))
    }

    // ---- a typed payback --------------------------------------------------

    @Test fun aTypedPaybackIsInsertedWithItsBill() {
        val bill = expense(DAY_1)

        val id = db.txnDao().insertManual(sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND), bill)!!

        assertEquals(bill, billOf(id))
    }

    @Test fun aTypedPaybackRefusedItsBillIsNotWrittenAtAll() {
        val later = expense(DAY_3)
        val before = db.txnDao().countAll()

        assertNull(db.txnDao().insertManual(sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND), later))
        assertEquals("the refused payback was kept", before, db.txnDao().countAll())
    }

    @Test fun aTypedPaybackMayNotMoveBeforeItsBill() {
        val bill = expense(DAY_2)
        val payback = typedPayback(bill, DAY_3)
        val stored = db.txnDao().byId(payback)!!

        val wrote = db.txnDao().editManualEntry(payback, stored.createdAt, ManualEdit(amountSen = 99L, occurredAt = DAY_1), NOW)

        assertFalse(wrote)
        assertEquals("a refused edit wrote part of itself", stored, db.txnDao().byId(payback))
    }

    @Test fun aTypedBillMayNotMoveAfterAPayback() {
        val bill = typed(sampleTxn(occurredAt = DAY_1))
        val payback = refund(DAY_2)
        db.txnDao().setBill(payback, bill, NOW)
        val stored = db.txnDao().byId(bill)!!

        assertFalse(db.txnDao().editManualEntry(bill, stored.createdAt, ManualEdit(amountSen = 99L, occurredAt = DAY_3), NOW))
        assertEquals(stored, db.txnDao().byId(bill))
        assertTrue("its own payback's day was refused", db.txnDao().editManualEntry(bill, stored.createdAt, ManualEdit(occurredAt = DAY_2), NOW))
    }

    @Test fun aTypedPaybackSwitchedToPaidLosesItsBill() {
        val bill = expense(DAY_1)
        val payback = typedPayback(bill, DAY_2)
        val createdAt = db.txnDao().byId(payback)!!.createdAt

        val wrote = db.txnDao().editManualEntry(payback, createdAt, ManualEdit(direction = Direction.EXPENSE, bill = ManualEdit.Bill(null)), NOW)

        assertTrue(wrote)
        assertEquals(Direction.EXPENSE, db.txnDao().byId(payback)!!.direction)
        assertNull(billOf(payback))
    }

    @Test fun aTypedEntryTakesABillInAnEditOrNothing() {
        val bill = expense(DAY_1)
        val later = expense(DAY_3)
        val entry = typed(sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND))
        val stored = db.txnDao().byId(entry)!!

        assertFalse(db.txnDao().editManualEntry(entry, stored.createdAt, ManualEdit(amountSen = 99L, bill = ManualEdit.Bill(later)), NOW))
        assertEquals(stored, db.txnDao().byId(entry))

        assertTrue(db.txnDao().editManualEntry(entry, stored.createdAt, ManualEdit(bill = ManualEdit.Bill(bill)), NOW))
        assertEquals(bill, billOf(entry))
    }

    @Test fun aBillsEarliestPaybackIsItsLatestDay() {
        val bill = expense(DAY_1)
        db.txnDao().setBill(refund(DAY_3), bill, NOW)
        db.txnDao().setBill(refund(DAY_2), bill, NOW)

        assertEquals(LocalDates.of(DAY_2), db.txnDao().earliestPaybackDate(bill))
        assertNull(db.txnDao().earliestPaybackDate(refund(DAY_2)))
    }

    private fun insert(txn: Txn): Long = db.txnDao().insert(txn)

    private fun expense(at: Long, name: String = "A", amountSen: Long = 1_000L): Long =
        insert(sampleTxn(occurredAt = at, amountSen = amountSen).copy(merchantRaw = name.uppercase(), merchantDisplay = name, merchantKey = name.uppercase()))

    private fun refund(at: Long, amountSen: Long = 1_000L): Long =
        insert(sampleTxn(occurredAt = at, direction = Direction.REFUND, amountSen = amountSen))

    private fun payback(bill: Long, amountSen: Long): Long =
        refund(DAY_2, amountSen).also { assertEquals(1, db.txnDao().setBill(it, bill, NOW)) }

    /** A manual entry: no capture, and its own `created_at`, which an edit is checked against. */
    private fun typed(txn: Txn): Long = insert(txn.copy(rawCaptureId = null, sourcePackage = null))

    private fun typedPayback(bill: Long, at: Long): Long =
        db.txnDao().insertManual(sampleTxn(occurredAt = at, direction = Direction.REFUND).copy(sourcePackage = null), bill)!!

    private fun billOf(id: Long): Long? = db.txnDao().byId(id)!!.billTxnId

    private companion object {
        /** 1 September 2026, Malaysia time, and the days after it. */
        const val DAY_1 = 1_788_192_000_000L
        const val DAY = 86_400_000L
        const val DAY_2 = DAY_1 + DAY
        const val DAY_3 = DAY_2 + DAY
        const val DAY_4 = DAY_3 + DAY
        const val NOW = 1_790_000_000_000L
    }
}
