package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.RowBill
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the list reads of spec 7.4's link (#392, spec 9.1, 9.4, 9.7): a
 * payback's row names its bill, by the name the user gave it, and whether it
 * counts; a bill's row carries what its counted paybacks add up to; a bill's
 * paybacks are listed oldest first; and the `Day` header's `N PAYMENTS`
 * counts as every total counts, so a payback is in no count.
 *
 * - **Yuen Kee**, 18 Sep: a 96.00 bill, renamed `Restoran Yuen Kee` by the
 *   user. Mei paid 32.00 back on 20 Sep at 09:00 and Arjun 32.00 at 08:00;
 *   Farid 32.00 the same day, still awaiting review; Siti 10.00 on 21 Sep,
 *   then left out; Daniel 5.00 on 22 Sep, then Not a payment.
 * - **Tealive**, 20 Sep: 8.90, plain.
 */
@RunWith(AndroidJUnit4::class)
class PaybackRowsTest {
    private lateinit var db: PingedDatabase
    private var uncategorized = 0L
    private var food = 0L
    private var capture = 0L

    private var yuenKee = 0L
    private var mei = 0L
    private var arjun = 0L
    private var farid = 0L
    private var siti = 0L
    private var daniel = 0L
    private var tealive = 0L

    @Before fun setUp() {
        db = freshDatabase()
        food = db.categoryId("Food & Drinks")
        uncategorized = db.categoryDao().requireUncategorizedId()
        yuenKee = pay("YUEN KEE", 9_600L, 20260918, 20, food)
        db.merchantIdentityDao().setName("YUEN KEE", "Restoran Yuen Kee")
        mei = payback("MEI", 3_200L, 20260920, 9)
        arjun = payback("ARJUN", 3_200L, 20260920, 8)
        farid = pay("FARID", 3_200L, 20260920, 10, uncategorized, Direction.REFUND, TxnState.PENDING)
        assertEquals(1, db.txnDao().setBillAwaiting(farid, my.pinged.data.entity.PendingReason.RULE_REVIEW, yuenKee, NOW))
        siti = payback("SITI", 1_000L, 20260921, 9)
        assertEquals(1, db.txnDao().excludeByUser(siti, NOW))
        daniel = payback("DANIEL", 500L, 20260922, 9)
        assertEquals(1, db.txnDao().removeCaptured(daniel, NOW))
        tealive = pay("TEALIVE", 890L, 20260920, 15, food)
    }

    @After fun tearDown() { db.close() }

    private fun day(date: Int): List<FeedRow> = db.txnDao().dayRows(LocalDate(date))

    private fun FeedRow.isId(id: Long) = txn.id == id

    @Test fun aPaybacksRowNamesItsBillAsTheListNamesIt() {
        val row = day(20260920).single { it.isId(arjun) }
        val bill = db.txnDao().byId(yuenKee)!!
        assertEquals(
            RowBill(yuenKee, bill.createdAt, typed = false, name = "Restoran Yuen Kee", localDate = LocalDate(20260918), counts = true),
            row.bill,
        )
    }

    @Test fun aPaybackWhoseBillIsLeftOutSaysItDoesNotCount() {
        assertEquals(1, db.txnDao().excludeByUser(yuenKee, NOW))

        assertEquals(false, day(20260920).single { it.isId(arjun) }.bill?.counts)
    }

    @Test fun aRowThatPaysBackNothingNamesNoBill() {
        val row = day(20260920).single { it.isId(tealive) }
        assertNull("A row naming no bill must read as none, every bill_ column null", row.bill)
        assertEquals(0L, row.paidBackSen)
    }

    @Test fun aBillsRowCarriesOnlyWhatItsCountedPaybacksAddUpTo() {
        assertEquals(
            "Mei's and Arjun's 32.00 each; Farid's held 32.00, Siti's left-out 10.00 and Daniel's rejected 5.00 count nowhere",
            6_400L,
            day(20260918).single { it.isId(yuenKee) }.paidBackSen,
        )
        assertEquals("The bill's detail reads the same", 6_400L, db.txnDao().detail(yuenKee)!!.row.paidBackSen)
        val (from, to) = LocalDate(20260901) to LocalDate(20260930)
        assertEquals(
            "Breakdown's Yuen Kee line draws its bill's row with what is paid back (spec 9.3)",
            listOf(yuenKee to 6_400L),
            db.txnDao().breakdownRows(food, from, to, "YUEN KEE", "MYR").map { it.txn.id to it.paidBackSen },
        )
        assertEquals(
            "And so does Merchant month's",
            listOf(yuenKee to 6_400L),
            db.txnDao().merchantMonthRows("YUEN KEE", from, to, "MYR").map { it.txn.id to it.paidBackSen },
        )
    }

    @Test fun aBillsPaybacksAreListedOldestFirstAndARejectedOneIsNot() {
        assertEquals(listOf(arjun, mei, farid, siti), db.txnDao().paybacksOf(yuenKee).map { it.txn.id })
        assertEquals("Every row naming it keeps its direction, the rejected one too", 5, db.txnDao().paybackCount(yuenKee))
    }

    @Test fun aPaybackAddsNothingToADaysPaymentsOnItsOwnDayOrItsBills() {
        assertEquals("20 Sep: Tealive alone; Mei and Arjun count on the 18th, Farid is held", 1, db.txnDao().paymentCount(LocalDate(20260920), LocalDate(20260920), "MYR"))
        assertEquals("18 Sep: the bill alone; its paybacks are in its money, not its count", 1, db.txnDao().paymentCount(LocalDate(20260918), LocalDate(20260918), "MYR"))
        assertEquals("A day in another currency counts nothing", 0, db.txnDao().paymentCount(LocalDate(20260920), LocalDate(20260920), "SGD"))
    }

    @Test fun onceItsBillIsLeftOutAPaybackIsAPaymentOnItsOwnDay() {
        assertEquals(1, db.txnDao().excludeByUser(yuenKee, NOW))

        assertEquals("Tealive, Mei and Arjun", 3, db.txnDao().paymentCount(LocalDate(20260920), LocalDate(20260920), "MYR"))
        assertEquals(0, db.txnDao().paymentCount(LocalDate(20260918), LocalDate(20260918), "MYR"))
    }

    private fun pay(
        key: String,
        amountSen: Long,
        date: Int,
        hour: Int,
        category: Long,
        direction: Direction = Direction.EXPENSE,
        state: TxnState = TxnState.COMMITTED,
    ): Long = db.txnDao().insert(
        sampleTxn(
            amountSen = amountSen,
            occurredAt = date * 1_000L + hour * 10L + ++capture % 10,
            localDate = LocalDate(date),
            categoryId = category,
            direction = direction,
            state = state,
            rawCaptureId = capture,
        ).copy(merchantRaw = key, merchantDisplay = key.lowercase(), merchantKey = key),
    )

    /** A friend's [amountSen] received on [date] at [hour], made a payback of Yuen Kee's bill. */
    private fun payback(friend: String, amountSen: Long, date: Int, hour: Int): Long {
        val id = pay(friend, amountSen, date, hour, uncategorized, Direction.REFUND)
        assertEquals(1, db.txnDao().setBill(id, yuenKee, NOW))
        return id
    }

    private companion object {
        const val NOW = 9_999_999_999_999L
    }
}
