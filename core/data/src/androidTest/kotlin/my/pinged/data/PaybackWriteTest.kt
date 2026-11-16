package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Callable
import my.pinged.data.dao.ManualEdit
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.4's link, `txn.bill_txn_id`, as the data layer writes it (#392):
 * a payback is always a `REFUND` and its bill always an `EXPENSE`, every
 * writer holds that, and nothing but the user's own pick writes the column.
 */
@RunWith(AndroidJUnit4::class)
class PaybackWriteTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() {
        db = freshDatabase()
    }

    @After fun tearDown() = db.close()

    // ---- setBill and clearBill ------------------------------------------

    @Test fun aRefundBecomesAPaybackOfACountedExpense() {
        val bill = expense()
        val payback = refund()

        assertEquals(1, db.txnDao().setBill(payback, bill, NOW))

        assertEquals(bill, billOf(payback))
        assertEquals("the pick did not stamp updated_at", NOW, db.txnDao().byId(payback)!!.updatedAt)
    }

    @Test fun anExpenseIsNeverAPayback() {
        val bill = expense()
        val other = expense(at = DAY_2)

        assertEquals(0, db.txnDao().setBill(other, bill, NOW))
        assertNull(billOf(other))
    }

    @Test fun moneyReceivedIsNeverABill() {
        val notABill = refund(at = DAY_1)
        val payback = refund()

        assertEquals(0, db.txnDao().setBill(payback, notABill, NOW))
        assertNull(billOf(payback))
    }

    @Test fun aBillThatDoesNotCountIsRefused() {
        val payback = refund()
        val pending = db.txnDao().insert(sampleTxn(occurredAt = DAY_1, state = TxnState.PENDING))
        val excluded = db.txnDao().insert(sampleTxn(occurredAt = DAY_1, isExcluded = true))
        val rejected = db.txnDao().insert(sampleTxn(occurredAt = DAY_1, state = TxnState.REJECTED))

        listOf(pending, excluded, rejected).forEach { bill ->
            assertEquals("bill $bill was taken", 0, db.txnDao().setBill(payback, bill, NOW))
        }
        assertNull(billOf(payback))
    }

    @Test fun aBillDatedAfterThePaybackIsRefused() {
        val payback = refund(at = DAY_1)
        val later = expense(at = DAY_2)

        assertEquals(0, db.txnDao().setBill(payback, later, NOW))
        assertNull(billOf(payback))
    }

    @Test fun aBillOnTheSameDayIsTaken() {
        val bill = expense(at = DAY_2)
        val payback = refund(at = DAY_2)

        assertEquals(1, db.txnDao().setBill(payback, bill, NOW))
    }

    @Test fun aRejectedRefundIsRefused() {
        val bill = expense()
        val rejected = db.txnDao().insert(
            sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND, state = TxnState.REJECTED),
        )

        assertEquals(0, db.txnDao().setBill(rejected, bill, NOW))
    }

    @Test fun aBillThatIsGoneIsRefused() {
        val payback = refund()

        assertEquals(0, db.txnDao().setBill(payback, 999L, NOW))
    }

    @Test fun notAPaybackClearsTheLinkAndNothingElse() {
        val bill = expense()
        val payback = refund()
        db.txnDao().setBill(payback, bill, NOW)
        val linked = db.txnDao().byId(payback)!!

        assertEquals(1, db.txnDao().clearBill(payback, NOW + 1))

        assertEquals(linked.copy(billTxnId = null, updatedAt = NOW + 1), db.txnDao().byId(payback))
        assertEquals("a plain refund was cleared again", 0, db.txnDao().clearBill(payback, NOW + 2))
    }

    // ---- insert ----------------------------------------------------------

    @Test fun anExpenseInsertedWithABillIsRefused() {
        val bill = expense()

        assertThrows(IllegalArgumentException::class.java) {
            db.txnDao().insert(sampleTxn(occurredAt = DAY_2).copy(billTxnId = bill))
        }
    }

    @Test fun aPaybackInsertedNamingMoneyReceivedIsRefused() {
        val notABill = refund(at = DAY_1)

        assertThrows(IllegalArgumentException::class.java) {
            db.txnDao().insert(sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND).copy(billTxnId = notABill))
        }
    }

    @Test fun aPaybackInsertedNamingAnExpenseIsKept() {
        val bill = expense()
        val payback = db.txnDao().insert(
            sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND).copy(billTxnId = bill),
        )

        assertEquals(bill, billOf(payback))
    }

    // ---- the foreign key -------------------------------------------------

    @Test fun deletingATypedBillLeavesItsPaybacksAsMoneyReceived() {
        val bill = db.txnDao().insert(sampleTxn(occurredAt = DAY_1, pkg = null))
        val payback = refund()
        db.txnDao().setBill(payback, bill, NOW)

        assertEquals(1, db.txnDao().deleteManualEntry(bill, DAY_1))

        assertNull("ON DELETE SET NULL did not clear the payback's bill", billOf(payback))
    }

    /**
     * The deferral a restore needs: a payback inserted before the bill it
     * names, in one transaction, commits once the bill is in.
     */
    @Test fun aPaybackMayNameABillLaterInTheSameTransaction() {
        db.runInTransaction(
            Callable {
                db.txnDao().insertAll(
                    listOf(
                        sampleTxn(occurredAt = DAY_2, direction = Direction.REFUND).copy(id = 1, billTxnId = 2),
                        sampleTxn(occurredAt = DAY_1).copy(id = 2),
                    ),
                )
            },
        )

        assertEquals(2L, billOf(1))
    }

    // ---- direction, on a typed row and on a re-read ------------------------

    @Test fun aTypedBillWithPaybacksCannotBecomeMoneyReceived() {
        val bill = db.txnDao().insert(sampleTxn(occurredAt = DAY_1, pkg = null))
        val payback = refund()
        db.txnDao().setBill(payback, bill, NOW)
        val before = db.txnDao().byId(bill)

        val edited = db.txnDao().editManualEntry(
            bill,
            DAY_1,
            ManualEdit(amountSen = 5_000L, direction = Direction.REFUND),
            NOW,
        )

        assertFalse("a bill was turned into money received", edited)
        assertEquals("a refused edit wrote part of itself", before, db.txnDao().byId(bill))
    }

    @Test fun aTypedPaybackCannotBecomeSpending() {
        val bill = expense()
        val payback = db.txnDao().insert(
            sampleTxn(occurredAt = DAY_2, pkg = null, direction = Direction.REFUND),
        )
        db.txnDao().setBill(payback, bill, NOW)

        assertFalse(db.txnDao().editManualEntry(payback, DAY_2, ManualEdit(direction = Direction.EXPENSE), NOW))
        assertEquals(Direction.REFUND, db.txnDao().byId(payback)!!.direction)
    }

    @Test fun aTypedBillKeepsItsDirectionAndTakesTheRestOfAnEdit() {
        val bill = db.txnDao().insert(sampleTxn(occurredAt = DAY_1, pkg = null))
        val payback = refund()
        db.txnDao().setBill(payback, bill, NOW)

        val edited = db.txnDao().editManualEntry(
            bill,
            DAY_1,
            ManualEdit(amountSen = 5_000L, direction = Direction.EXPENSE),
            NOW,
        )

        assertEquals(true, edited)
        assertEquals(5_000L, db.txnDao().byId(bill)!!.amountSen)
    }

    @Test fun aTypedRowWithNoLinkStillChangesDirection() {
        val row = db.txnDao().insert(sampleTxn(occurredAt = DAY_1, pkg = null))

        assertEquals(true, db.txnDao().editManualEntry(row, DAY_1, ManualEdit(direction = Direction.REFUND), NOW))
        assertEquals(Direction.REFUND, db.txnDao().byId(row)!!.direction)
    }

    /** Spec 7.4: a re-read never touches the link, and cannot turn a payback into spending. */
    @Test fun aRereadThatWouldTurnAPaybackIntoSpendingWritesNothing() {
        val bill = expense()
        val (capture, payback) = capturedRefund()
        db.txnDao().setBill(payback, bill, NOW)
        val before = db.txnDao().byId(payback)!!

        val applied = reread(capture, before, before.copy(direction = Direction.EXPENSE, amountSen = 7_000L))

        assertFalse("a re-read turned a payback into spending", applied)
        assertEquals(before, db.txnDao().byId(payback))
    }

    @Test fun aRereadThatWouldTurnABillIntoMoneyReceivedWritesNothing() {
        val (capture, bill) = capturedExpense()
        val payback = refund()
        db.txnDao().setBill(payback, bill, NOW)
        val before = db.txnDao().byId(bill)!!

        assertFalse(reread(capture, before, before.copy(direction = Direction.REFUND)))
        assertEquals(before, db.txnDao().byId(bill))
    }

    @Test fun aRereadKeepingThePaybacksDirectionKeepsItsBill() {
        val bill = expense()
        val (capture, payback) = capturedRefund()
        db.txnDao().setBill(payback, bill, NOW)
        val before = db.txnDao().byId(payback)!!

        assertEquals(true, reread(capture, before, before.copy(amountSen = 7_000L, billTxnId = null)))

        val after = db.txnDao().byId(payback)!!
        assertEquals("the re-read did not land", 7_000L, after.amountSen)
        assertEquals("a re-read wrote the link", bill, after.billTxnId)
    }

    // ---- fixtures --------------------------------------------------------

    private fun expense(at: Long = DAY_1): Long = db.txnDao().insert(sampleTxn(occurredAt = at))

    private fun refund(at: Long = DAY_2): Long =
        db.txnDao().insert(sampleTxn(occurredAt = at, direction = Direction.REFUND))

    private fun capturedRefund(): Pair<Long, Long> = captured(Direction.REFUND, DAY_2, "refund")

    private fun capturedExpense(): Pair<Long, Long> = captured(Direction.EXPENSE, DAY_1, "expense")

    private fun captured(direction: Direction, at: Long, hash: String): Pair<Long, Long> {
        val capture = db.rawCaptureDao().insert(
            sampleCapture(hash = hash, postedAt = at, status = ParseStatus.MATCHED),
        )
        val txn = db.txnDao().insert(sampleTxn(occurredAt = at, rawCaptureId = capture, direction = direction))
        return capture to txn
    }

    private fun reread(capture: Long, compared: my.pinged.data.entity.Txn, corrected: my.pinged.data.entity.Txn) =
        db.rawCaptureDao().applyReread(
            captureId = capture,
            fromPackVersion = 0,
            toPackVersion = 1,
            ruleId = "tng-received-v1",
            compared = compared,
            corrected = corrected.copy(updatedAt = NOW),
        )

    private fun billOf(id: Long): Long? = db.txnDao().byId(id)!!.billTxnId

    private companion object {
        /** 1 and 2 September 2026, Malaysia time; any two days in order. */
        const val DAY_1 = 1_788_192_000_000L
        const val DAY_2 = DAY_1 + 86_400_000L
        const val NOW = 1_790_000_000_000L
    }
}
