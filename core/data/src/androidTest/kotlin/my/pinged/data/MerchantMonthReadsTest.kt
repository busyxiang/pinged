package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.MerchantTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Merchant month's reads (#344; CONTEXT.md, Merchant month): one merchant
 * identity's September across every category, the rows behind it, what was
 * kept out, and the name it goes by when nothing is counted.
 *
 * foodpanda is paid in two categories and refunded once; Yuen Kee is one
 * identity over two keys once merged; around them sit rows each read must
 * leave out: another month, another currency, an excluded row, one awaiting
 * review and another merchant. The figures are worked by hand below.
 */
@RunWith(AndroidJUnit4::class)
class MerchantMonthReadsTest {
    private lateinit var db: PingedDatabase
    private var food = 0L
    private var groceries = 0L

    /** 2026-09-07T00:00:00Z. */
    private val day = 1_788_739_200_000L
    private val from = LocalDate(20260901)
    private val to = LocalDate(20260930)

    @Before fun setUp() {
        db = freshDatabase()
        food = db.categoryId("Food & Drinks")
        groceries = db.categoryId("Groceries")
    }

    @After fun tearDown() { db.close() }

    private fun pay(
        key: String?,
        amountSen: Long,
        at: Long = day,
        category: Long = food,
        direction: Direction = Direction.EXPENSE,
        state: TxnState = TxnState.COMMITTED,
        isExcluded: Boolean = false,
        currency: String = "MYR",
    ): Long = db.txnDao().insert(
        sampleTxn(
            amountSen = amountSen,
            occurredAt = at,
            categoryId = category,
            direction = direction,
            state = state,
            isExcluded = isExcluded,
            currency = currency,
            rawCaptureId = at,
        ).copy(merchantRaw = key, merchantDisplay = key?.lowercase(), merchantKey = key),
    )

    private fun september() {
        // foodpanda over two categories: 40.00 + 22.00, less a 15.00 refund, 47.00 over three rows.
        pay("FOODPANDA", 4_000L, at = day + 1)
        pay("FOODPANDA", 2_200L, at = day + 2, category = groceries)
        pay("FOODPANDA", 1_500L, at = day + 3, direction = Direction.REFUND)
        // Yuen Kee under two rails: 12.50 + 9.80 = 22.30, and 15.00.
        pay("YUENKEEHOMETOWNCAFE", 1_250L, at = day + 4)
        pay("YUENKEEHOMETOWNCAFE", 980L, at = day + 5)
        pay("RESTORAN YUEN KEE", 1_500L, at = day + 6, category = groceries)

        // Each of these is outside one read or another.
        pay("FOODPANDA", 7_700L, at = day + 40L * 86_400_000L)
        pay("FOODPANDA", 2_000L, at = day + 7, currency = "SGD")
        pay("FOODPANDA", 5_000L, at = day + 8, isExcluded = true)
        pay("FOODPANDA", 4_500L, at = day + 9, state = TxnState.PENDING)
        pay("ZUS", 900L, at = day + 10)
        pay(null, 800L, at = day + 11)
    }

    private fun merge() =
        db.merchantIdentityDao().merge(source = "YUENKEEHOMETOWNCAFE", target = "RESTORAN YUEN KEE", targetName = "Yuen Kee")

    private fun ranking(): List<MerchantTotal> = db.txnDao().merchantTotals(from, to, Int.MAX_VALUE)

    @Test fun theMonthIsTheMerchantsWhoGotItRowToTheSen() {
        september()
        merge()

        val foodpanda = db.txnDao().merchantMonth("FOODPANDA", from, to)
        assertEquals(
            listOf("MYR" to (4_700L to 3), "SGD" to (2_000L to 1)),
            foodpanda.sortedBy { it.currency }.map { it.currency to (it.netSen to it.txnCount) },
        )
        // Every identity in the month, merged ones included, as Charts ranks it.
        for (row in ranking()) {
            assertEquals(
                "${row.identityKey} in ${row.currency}",
                row,
                db.txnDao().merchantMonth(row.identityKey, from, to).single { it.currency == row.currency },
            )
        }
        assertEquals(
            MerchantTotal("RESTORAN YUEN KEE", "Yuen Kee", "MYR", 3_730L, 3),
            db.txnDao().merchantMonth("RESTORAN YUEN KEE", from, to).single(),
        )
    }

    @Test fun aKeyMergedAwayReadsAsTheMerchantItWasMergedInto() {
        september()
        assertEquals(2_230L, db.txnDao().merchantMonth("YUENKEEHOMETOWNCAFE", from, to).single().netSen)

        merge()

        assertEquals(
            "The key's month is not its merchant's now",
            MerchantTotal("RESTORAN YUEN KEE", "Yuen Kee", "MYR", 3_730L, 3),
            db.txnDao().merchantMonth("YUENKEEHOMETOWNCAFE", from, to).single(),
        )
        db.merchantIdentityDao().separate("YUENKEEHOMETOWNCAFE")
        assertEquals(1_500L, db.txnDao().merchantMonth("RESTORAN YUEN KEE", from, to).single().netSen)
    }

    @Test fun theRowsAreExactlyTheCountedRinggitRowsInEveryCategoryInFeedOrder() {
        september()
        merge()

        val foodpanda = db.txnDao().merchantMonthRows("FOODPANDA", from, to, "MYR")
        assertEquals(listOf(1_500L, 2_200L, 4_000L), foodpanda.map { it.txn.amountSen })
        assertEquals(setOf(food, groceries), foodpanda.map { it.txn.categoryId }.toSet())

        val yuenKee = db.txnDao().merchantMonthRows("RESTORAN YUEN KEE", from, to, "MYR")
        assertEquals(listOf(1_500L, 980L, 1_250L), yuenKee.map { it.txn.amountSen })
        assertEquals("The feed's names, through the merge", setOf("Yuen Kee"), yuenKee.map { it.displayName }.toSet())
        assertEquals(setOf("RESTORAN YUEN KEE"), yuenKee.map { it.identityKey }.toSet())
    }

    @Test fun excludedAndHeldRowsAreKeptOutAndTheFirstHeldIsTheInboxsFirst() {
        september()
        val newer = pay("FOODPANDA", 700L, at = day + 20, state = TxnState.PENDING, category = groceries)
        pay("FOODPANDA", 700L, at = day + 21, state = TxnState.PENDING, currency = "SGD")
        pay("ZUS", 700L, at = day + 22, state = TxnState.PENDING)

        assertEquals(
            setOf(NotInTotalLine.USER_EXCLUDED to (1 to 5_000L), NotInTotalLine.PENDING to (2 to 5_200L)),
            db.txnDao().merchantMonthKeptOut("FOODPANDA", from, to).filter { it.currency == "MYR" }
                .map { it.line to (it.txnCount to it.netSen) }.toSet(),
        )
        assertEquals(newer, db.txnDao().merchantMonthFirstHeld("FOODPANDA", from, to, "MYR"))
        assertNull(db.txnDao().merchantMonthFirstHeld("RESTORAN YUEN KEE", from, to, "MYR"))
    }

    @Test fun theKeptOutRowsOfAMergedKeyAreItsMerchants() {
        september()
        val held = pay("YUENKEEHOMETOWNCAFE", 600L, at = day + 30, state = TxnState.PENDING)
        merge()

        assertEquals(
            listOf(NotInTotalLine.PENDING to 1),
            db.txnDao().merchantMonthKeptOut("RESTORAN YUEN KEE", from, to).map { it.line to it.txnCount },
        )
        assertEquals(held, db.txnDao().merchantMonthFirstHeld("RESTORAN YUEN KEE", from, to, "MYR"))
    }

    @Test fun theNameIsTheUsersOrTheLatestRowsWhenNothingIsCounted() {
        september()
        assertEquals("zus", db.txnDao().merchantName("ZUS"))
        merge()
        assertEquals("Yuen Kee", db.txnDao().merchantName("YUENKEEHOMETOWNCAFE"))
        assertNull(db.txnDao().merchantName("NOBODY"))
    }
}
