package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.BreakdownLine
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
 * Breakdown's reads (#342; CONTEXT.md, Breakdown): one category's month by
 * merchant identity, the rows behind each line, and what was kept out.
 *
 * The month is Food & Drinks in September with a refund, a merchant that nets
 * negative, two rows with no merchant (one of them a manual entry), and around
 * it rows each read must leave out: another category, another month, another
 * currency, an excluded row and one awaiting review. The expected figures are
 * worked by hand below, not recomputed from the rows.
 */
@RunWith(AndroidJUnit4::class)
class BreakdownReadsTest {
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
        manual: Boolean = false,
    ): Long = db.txnDao().insert(
        sampleTxn(
            amountSen = amountSen,
            occurredAt = at,
            categoryId = category,
            direction = direction,
            state = state,
            isExcluded = isExcluded,
            currency = currency,
            pkg = if (manual) null else "my.com.tngdigital.ewallet",
            rawCaptureId = if (manual) null else at,
        ).copy(merchantRaw = key, merchantDisplay = key?.lowercase(), merchantKey = key),
    )

    private fun foodSeptember() {
        // Yuen Kee under two rails: 12.50 + 9.80 + 15.00 = 37.30.
        pay("YUENKEEHOMETOWNCAFE", 1_250L, at = day + 1)
        pay("YUENKEEHOMETOWNCAFE", 980L, at = day + 2)
        pay("RESTORAN YUEN KEE", 1_500L, at = day + 3)
        // foodpanda 40.00, less a 15.00 refund: 25.00 over two rows.
        pay("FOODPANDA", 4_000L, at = day + 4)
        pay("FOODPANDA", 1_500L, at = day + 5, direction = Direction.REFUND)
        // Domino's: a refund larger than the order, -24.90.
        pay("DOMINOS", 1_000L, at = day + 6)
        pay("DOMINOS", 3_490L, at = day + 7, direction = Direction.REFUND)
        // No merchant: a captured 8.00 and a manual 4.00.
        pay(null, 800L, at = day + 8)
        pay(null, 400L, at = day + 9, manual = true)

        // Each of these is outside one read or another.
        pay("FOODPANDA", 9_900L, at = day + 10, category = groceries)
        pay("FOODPANDA", 7_700L, at = day + 40L * 86_400_000L)
        pay("FOODPANDA", 2_000L, at = day + 11, currency = "SGD")
        pay("FOODPANDA", 5_000L, at = day + 12, isExcluded = true)
        pay("FOODPANDA", 4_500L, at = day + 13, state = TxnState.PENDING)
        pay("FOODPANDA", 6_000L, at = day + 14, category = groceries, isExcluded = true)
        pay("FOODPANDA", 6_600L, at = day + 15, category = groceries, state = TxnState.PENDING)
    }

    private fun lines(): List<BreakdownLine> = db.txnDao().breakdownLines(food, from, to)

    @Test fun theRinggitLinesSumToTheCategorysBarAndCountItsRows() {
        foodSeptember()
        val myr = lines().filter { it.currency == "MYR" }

        assertEquals(
            mapOf(
                "YUENKEEHOMETOWNCAFE" to (2_230L to 2),
                "RESTORAN YUEN KEE" to (1_500L to 1),
                "FOODPANDA" to (2_500L to 2),
                "DOMINOS" to (-2_490L to 2),
                null to (1_200L to 2),
            ),
            myr.associate { it.identityKey to (it.netSen to it.txnCount) },
        )
        // 37.30 + 25.00 - 24.90 + 12.00, and the bar Charts draws for it.
        val bar = db.txnDao().monthByCategory(from, to, Int.MAX_VALUE).single { it.categoryId == food && it.currency == "MYR" }
        assertEquals(4_940L, bar.netSen)
        assertEquals(bar.netSen, myr.sumOf { it.netSen })
        assertEquals(9, myr.sumOf { it.txnCount })
    }

    @Test fun anotherCurrencyIsALineOfItsOwn() {
        foodSeptember()
        assertEquals(
            listOf(Triple("FOODPANDA", 2_000L, 1)),
            lines().filter { it.currency != "MYR" }.map { Triple(it.identityKey, it.netSen, it.txnCount) },
        )
    }

    @Test fun aMergedMerchantIsOneLineUntilItIsSeparated() {
        foodSeptember()
        db.merchantIdentityDao().merge(source = "YUENKEEHOMETOWNCAFE", target = "RESTORAN YUEN KEE", targetName = "Yuen Kee")

        val merged = lines().single { it.identityKey == "RESTORAN YUEN KEE" }
        assertEquals(BreakdownLine("RESTORAN YUEN KEE", "Yuen Kee", "MYR", 3_730L, 3), merged)
        assertEquals(0, lines().count { it.identityKey == "YUENKEEHOMETOWNCAFE" })

        db.merchantIdentityDao().separate("YUENKEEHOMETOWNCAFE")

        assertEquals(
            setOf("YUENKEEHOMETOWNCAFE" to 2_230L, "RESTORAN YUEN KEE" to 1_500L),
            lines().filter { it.identityKey?.contains("YUEN") == true }.map { it.identityKey to it.netSen }.toSet(),
        )
    }

    @Test fun eachLinesRowsAreExactlyTheRowsBehindItsFigure() {
        foodSeptember()
        db.merchantIdentityDao().merge(source = "YUENKEEHOMETOWNCAFE", target = "RESTORAN YUEN KEE", targetName = "Yuen Kee")

        val yuenKee = db.txnDao().breakdownRows(food, from, to, "RESTORAN YUEN KEE", "MYR")
        // The feed's order: newest first.
        assertEquals(listOf(1_500L, 980L, 1_250L), yuenKee.map { it.txn.amountSen })
        assertEquals(setOf("Yuen Kee"), yuenKee.map { it.displayName }.toSet())

        val foodpanda = db.txnDao().breakdownRows(food, from, to, "FOODPANDA", "MYR")
        assertEquals("Only the counted ringgit rows of this category and month", listOf(1_500L, 4_000L), foodpanda.map { it.txn.amountSen })

        val noMerchant = db.txnDao().breakdownRows(food, from, to, null, "MYR")
        assertEquals(listOf(400L, 800L), noMerchant.map { it.txn.amountSen })
    }

    @Test fun excludedAndHeldRowsAreOnTheKeptOutLinesAndNoOther() {
        foodSeptember()
        assertEquals(
            setOf(NotInTotalLine.USER_EXCLUDED to (1 to 5_000L), NotInTotalLine.PENDING to (1 to 4_500L)),
            db.txnDao().breakdownKeptOut(food, from, to).filter { it.currency == "MYR" }.map { it.line to (it.txnCount to it.netSen) }.toSet(),
        )
        val rowIds = lines().map { it.identityKey }.flatMap { db.txnDao().breakdownRows(food, from, to, it, "MYR") }.map { it.txn.amountSen }
        assertEquals("An excluded or held row is behind no line", emptyList<Long>(), rowIds.filter { it == 5_000L || it == 4_500L })
    }

    @Test fun theFirstHeldRowIsTheNewestOfTheCategorysMonthInRinggit() {
        foodSeptember()
        val newer = pay("ZUS", 700L, at = day + 20, state = TxnState.PENDING)
        pay("ZUS", 700L, at = day + 30, state = TxnState.PENDING, category = groceries)
        pay("ZUS", 700L, at = day + 31, state = TxnState.PENDING, currency = "SGD")

        assertEquals(newer, db.txnDao().breakdownFirstHeld(food, from, to, "MYR"))
        assertNull(db.txnDao().breakdownFirstHeld(db.categoryId("Health"), from, to, "MYR"))
    }
}
