package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.BreakdownLine
import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.DayTotal
import my.pinged.data.dao.MerchantTotal
import my.pinged.data.dao.MonthSource
import my.pinged.data.dao.MonthSplit
import my.pinged.data.dao.MonthlyTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 7.4's counting fragment (`TxnDao.COUNTED_AS`) through every aggregate
 * that reads it: a payback counts in its bill's month, category and merchant
 * while the bill counts, lowers spent and never came back, adds nothing to a
 * count, and counts on its own again once its bill stops counting (#392).
 *
 * The ledger, worked by hand rather than recomputed from the rows:
 *
 * - **Yuen Kee**, Food, 18 Sep: a 96.00 bill, paid back 32.00 by Arjun on
 *   19 Sep and 32.00 by Mei on 2 Oct, the month after.
 * - **Jaya Grocer**, Groceries, 20 Aug: a 60.00 bill in an ended month, paid
 *   back 20.00 by Arjun on 3 Sep.
 * - **Domino's**, Food, 10 Sep: a 50.00 bill paid back 25.00 by Mei on
 *   12 Sep, then left out by the user (Not spending), so its payback counts
 *   on its own, Uncategorized under Mei.
 * - **foodpanda**, Food: 40.00 on 7 Sep and a 15.00 refund on 8 Sep, plain.
 * - **Jaya Grocer**, Groceries, 5 Sep: 30.00, plain.
 * - Arjun's 10.00 on 20 Sep, picked as a payback of Yuen Kee's bill and still
 *   awaiting review, so it counts nowhere.
 *
 * September nets 62.00: Food 57.00 (96.00 - 64.00 + 40.00 - 15.00),
 * Groceries 30.00, Uncategorized -25.00. August nets 40.00, all Groceries.
 * October has nothing counted in it.
 */
@RunWith(AndroidJUnit4::class)
class PaybackCountingTest {
    private lateinit var db: PingedDatabase
    private var food = 0L
    private var groceries = 0L
    private var uncategorized = 0L
    private var capture = 0L

    private val sep = LocalDate(20260901) to LocalDate(20260930)
    private val aug = LocalDate(20260801) to LocalDate(20260831)
    private val oct = LocalDate(20261001) to LocalDate(20261031)

    private var yuenKee = 0L
    private var arjunForYuenKee = 0L
    private var dominos = 0L

    @Before fun setUp() {
        db = freshDatabase()
        food = db.categoryId("Food & Drinks")
        groceries = db.categoryId("Groceries")
        uncategorized = db.categoryDao().requireUncategorizedId()

        yuenKee = pay("YUEN KEE", 9_600L, 20260918, food)
        arjunForYuenKee = payback(yuenKee, "ARJUN", 3_200L, 20260919)
        payback(yuenKee, "MEI", 3_200L, 20261002)

        val jaya = pay("JAYA GROCER", 6_000L, 20260820, groceries)
        payback(jaya, "ARJUN", 2_000L, 20260903)

        dominos = pay("DOMINOS", 5_000L, 20260910, food)
        payback(dominos, "MEI", 2_500L, 20260912)
        assertEquals(1, db.txnDao().excludeByUser(dominos, NOW))

        pay("FOODPANDA", 4_000L, 20260907, food)
        pay("FOODPANDA", 1_500L, 20260908, food, Direction.REFUND)
        pay("JAYA GROCER", 3_000L, 20260905, groceries)

        val held = pay("ARJUN", 1_000L, 20260920, uncategorized, Direction.REFUND, TxnState.PENDING, pkg = TNG)
        assertEquals(1, db.txnDao().setBill(held, yuenKee, NOW))
    }

    @After fun tearDown() { db.close() }

    // ---- where a payback counts -----------------------------------------

    @Test fun aPaybackCountsInItsBillsMonthAndNotItsOwn() {
        assertEquals(listOf(CurrencyTotal("MYR", 6_200L)), db.txnDao().monthTotals(sep.first, sep.second))
        assertEquals(
            "Jaya Grocer's August lost Arjun's 20.00, paid in September",
            listOf(CurrencyTotal("MYR", 4_000L)),
            db.txnDao().monthTotals(aug.first, aug.second),
        )
        assertEquals(
            "Mei's 32.00 arrived in October and counts in September with its bill",
            emptyList<CurrencyTotal>(),
            db.txnDao().monthTotals(oct.first, oct.second),
        )
        assertEquals(
            listOf(MonthlyTotal(202608, "MYR", 4_000L), MonthlyTotal(202609, "MYR", 6_200L)),
            db.txnDao().monthlyTotals(aug.first, oct.second).sortedBy { it.month },
        )
    }

    @Test fun aPaybackCountsOnItsBillsDay() {
        assertEquals(
            setOf(
                DayTotal(LocalDate(20260905), "MYR", 3_000L),
                DayTotal(LocalDate(20260907), "MYR", 4_000L),
                DayTotal(LocalDate(20260908), "MYR", -1_500L),
                DayTotal(LocalDate(20260912), "MYR", -2_500L),
                // 96.00 less both paybacks; 19 Sep and 3 Sep, where they
                // arrived, have no subtotal.
                DayTotal(LocalDate(20260918), "MYR", 3_200L),
            ),
            db.txnDao().dayTotals(sep.first, sep.second).toSet(),
        )
        assertEquals(
            listOf(DayTotal(LocalDate(20260820), "MYR", 4_000L)),
            db.txnDao().dayTotals(aug.first, aug.second),
        )
    }

    @Test fun aPaybackCountsInItsBillsCategory() {
        assertEquals(
            setOf(
                CategoryTotal(food, "MYR", 5_700L),
                CategoryTotal(groceries, "MYR", 3_000L),
                CategoryTotal(uncategorized, "MYR", -2_500L),
            ),
            db.txnDao().monthByCategory(sep.first, sep.second, 100).toSet(),
        )
        assertEquals(
            listOf(CategoryTotal(food, "MYR", 5_700L), CategoryTotal(groceries, "MYR", 3_000L)),
            db.txnDao().monthTopNamed(sep.first, sep.second, 3),
        )
        assertEquals(listOf(CategoryTotal(groceries, "MYR", 4_000L)), db.txnDao().monthByCategory(aug.first, aug.second, 100))
    }

    @Test fun aPaybackLowersSpentAndNeverCameBack() {
        assertEquals(
            // Spent 96.00 + 40.00 + 30.00 - 64.00; came back the 15.00 refund
            // and Mei's 25.00, whose bill does not count.
            listOf(MonthSplit("MYR", spentSen = 10_200L, cameBackSen = 4_000L)),
            db.txnDao().monthSplit(sep.first, sep.second),
        )
        assertEquals(listOf(MonthSplit("MYR", spentSen = 4_000L, cameBackSen = 0L)), db.txnDao().monthSplit(aug.first, aug.second))
    }

    @Test fun aPaybackCountsUnderItsBillsMerchantAndAddsNothingToItsCount() {
        assertEquals(
            setOf(
                MerchantTotal("YUEN KEE", "yuen kee", "MYR", 3_200L, 1),
                MerchantTotal("FOODPANDA", "foodpanda", "MYR", 2_500L, 2),
                MerchantTotal("JAYA GROCER", "jaya grocer", "MYR", 3_000L, 1),
                // Its bill left out, Mei's 25.00 is plain money received.
                MerchantTotal("MEI", "mei", "MYR", -2_500L, 1),
            ),
            db.txnDao().merchantTotals(sep.first, sep.second, 100).toSet(),
        )
    }

    @Test fun aLaterPaybackDoesNotNameItsBillsMerchant() {
        // Mei's payback is the latest row of Yuen Kee's group by occurred_at,
        // which is what names a line; it must still read as the bill.
        val rows = db.txnDao().merchantTotals(sep.first, sep.second, 100)
        assertEquals("yuen kee", rows.single { it.identityKey == "YUEN KEE" }.displayName)
    }

    @Test fun aPaybackIsNoTransactionToSort() {
        assertEquals("Only Mei's, whose bill is left out", 1, db.txnDao().toSortCount(sep.first, sep.second))
    }

    @Test fun aPaybackOfABillToSortIsNotASecondOne() {
        val mamak = pay("MAMAK", 1_000L, 20260915, uncategorized)
        payback(mamak, "ARJUN", 500L, 20260916)

        assertEquals("Mei's and the mamak bill; its payback is the bill's", 2, db.txnDao().toSortCount(sep.first, sep.second))
    }

    @Test fun theSourcesLineNamesTheAppAPaybackCameFromInTheMonthItCounts() {
        assertEquals(
            setOf(MonthSource(false, MAE), MonthSource(false, TNG)),
            db.txnDao().monthSources(aug.first, aug.second).toSet(),
        )
        assertEquals(emptyList<MonthSource>(), db.txnDao().monthSources(oct.first, oct.second))
    }

    // ---- the sums hold to the sen ---------------------------------------

    @Test fun theMonthIsItsCategoriesAndEachBreakdownIsItsBar() {
        for ((from, to) in listOf(sep, aug, oct)) {
            val total = db.txnDao().monthTotals(from, to).sumOf { it.netSen }
            val bars = db.txnDao().monthByCategory(from, to, 100)
            assertEquals("$from: the bars do not sum to the month", total, bars.sumOf { it.netSen })
            for (bar in bars) {
                val lines = db.txnDao().breakdownLines(bar.categoryId, from, to)
                assertEquals("$from: category ${bar.categoryId}'s lines are not its bar", bar.netSen, lines.sumOf { it.netSen })
            }
        }
    }

    @Test fun eachMerchantMonthIsItsWhoGotItRow() {
        for ((from, to) in listOf(sep, aug)) {
            for (row in db.txnDao().merchantTotals(from, to, 100)) {
                assertEquals(listOf(row), db.txnDao().merchantMonth(row.identityKey, from, to))
            }
        }
    }

    @Test fun aBreakdownLineHoldsTheShareAndItsRowsAreTheBillsAlone() {
        assertEquals(
            setOf(
                BreakdownLine("YUEN KEE", "yuen kee", "MYR", 3_200L, 1),
                BreakdownLine("FOODPANDA", "foodpanda", "MYR", 2_500L, 2),
            ),
            db.txnDao().breakdownLines(food, sep.first, sep.second).toSet(),
        )
        assertEquals(
            "Arjun and Mei are no line under Uncategorized while their bills count",
            listOf(BreakdownLine("MEI", "mei", "MYR", -2_500L, 1)),
            db.txnDao().breakdownLines(uncategorized, sep.first, sep.second),
        )
        assertEquals(
            listOf(yuenKee),
            db.txnDao().breakdownRows(food, sep.first, sep.second, "YUEN KEE", "MYR").map { it.txn.id },
        )
    }

    @Test fun aPaybackIsNoRowOfItsOwnMerchantsMonth() {
        // Arjun has a plain 5.00 of his own in September, so his line is
        // there to open, and holds only that.
        val plain = pay("ARJUN", 500L, 20260925, uncategorized, Direction.REFUND, pkg = TNG)
        assertEquals(listOf(MerchantTotal("ARJUN", "arjun", "MYR", -500L, 1)), db.txnDao().merchantMonth("ARJUN", sep.first, sep.second))
        assertEquals(listOf(plain), db.txnDao().merchantMonthRows("ARJUN", sep.first, sep.second, "MYR").map { it.txn.id })
        assertEquals(listOf(plain), db.txnDao().breakdownRows(uncategorized, sep.first, sep.second, "ARJUN", "MYR").map { it.txn.id })
    }

    // ---- when the bill stops counting, and again ------------------------

    @Test fun aPaybackCountsAgainstItsBillAgainOnceTheBillCounts() {
        assertEquals(1, db.txnDao().countAsSpending(dominos, NOW))

        assertEquals(listOf(CurrencyTotal("MYR", 6_200L + 5_000L)), db.txnDao().monthTotals(sep.first, sep.second))
        assertEquals(
            BreakdownLine("DOMINOS", "dominos", "MYR", 2_500L, 1),
            db.txnDao().breakdownLines(food, sep.first, sep.second).single { it.identityKey == "DOMINOS" },
        )
        assertEquals(
            "Mei's 25.00 left Uncategorized for Domino's",
            emptyList<BreakdownLine>(),
            db.txnDao().breakdownLines(uncategorized, sep.first, sep.second),
        )
        assertEquals(0, db.txnDao().toSortCount(sep.first, sep.second))
        assertEquals(
            DayTotal(LocalDate(20260910), "MYR", 2_500L),
            db.txnDao().dayTotals(sep.first, sep.second).single { it.localDate == LocalDate(20260910) },
        )
    }

    @Test fun notAPaybackCountsItOnItsOwnAgain() {
        assertEquals(1, db.txnDao().clearBill(arjunForYuenKee, NOW))

        assertEquals(
            DayTotal(LocalDate(20260919), "MYR", -3_200L),
            db.txnDao().dayTotals(sep.first, sep.second).single { it.localDate == LocalDate(20260919) },
        )
        assertEquals(
            MerchantTotal("YUEN KEE", "yuen kee", "MYR", 6_400L, 1),
            db.txnDao().merchantTotals(sep.first, sep.second, 100).single { it.identityKey == "YUEN KEE" },
        )
        assertEquals(listOf(MonthSplit("MYR", spentSen = 13_400L, cameBackSen = 7_200L)), db.txnDao().monthSplit(sep.first, sep.second))
    }

    // ---- a payback that does not count itself ---------------------------

    @Test fun aHeldPaybackIsKeptOutOnItsOwnDayAndCategory() {
        assertEquals(
            setOf(
                NotInTotal(NotInTotalLine.PENDING, "MYR", 1, -1_000L),
                NotInTotal(NotInTotalLine.USER_EXCLUDED, "MYR", 1, 5_000L),
            ),
            db.txnDao().notInTotal(sep.first, sep.second).toSet(),
        )
        assertEquals(
            listOf(NotInTotal(NotInTotalLine.PENDING, "MYR", 1, -1_000L)),
            db.txnDao().notInTotal(LocalDate(20260920), LocalDate(20260920)),
        )
        assertEquals(
            listOf(NotInTotal(NotInTotalLine.PENDING, "MYR", 1, -1_000L)),
            db.txnDao().breakdownKeptOut(uncategorized, sep.first, sep.second),
        )
    }

    // ---- Search's totals --------------------------------------------------

    @Test fun aMonthsSearchNetsAsTheMonthDoes() {
        // Found: every row dated in September but Mei's October payback.
        // Counted here: Yuen Kee, Mei's 25.00, foodpanda twice, Jaya Grocer.
        // Found and counted under a bill: both of Arjun's. In the net:
        // the month's 62.00, Mei's October 32.00 with its bill.
        assertEquals(
            listOf(SearchTotal("MYR", rowCount = 9, countedCount = 5, paymentCount = 3, netSen = 6_200L, paybackCount = 2)),
            db.txnDao().searchTotals("", SearchFilter(from = sep.first, to = sep.second)),
        )
    }

    @Test fun aCategorysSearchNetsAsItsBarDoes() {
        assertEquals(
            listOf(SearchTotal("MYR", rowCount = 4, countedCount = 3, paymentCount = 2, netSen = 5_700L, paybackCount = 0)),
            db.txnDao().searchTotals("", SearchFilter(from = sep.first, to = sep.second, categoryId = food)),
        )
    }

    @Test fun aSearchForTheFriendFindsPaybacksWhoseMoneyIsTheirBills() {
        // Arjun's two paybacks and his held 10.00; their bills are not found,
        // so their money is in no net here.
        assertEquals(
            listOf(SearchTotal("MYR", rowCount = 3, countedCount = 0, paymentCount = 0, netSen = 0L, paybackCount = 2)),
            db.txnDao().searchTotals("arjun", SearchFilter()),
        )
    }

    @Test fun aSearchForTheBillNetsItsShare() {
        assertEquals(
            listOf(SearchTotal("MYR", rowCount = 1, countedCount = 1, paymentCount = 1, netSen = 3_200L, paybackCount = 0)),
            db.txnDao().searchTotals("yuen", SearchFilter()),
        )
    }

    private fun pay(
        key: String,
        amountSen: Long,
        date: Int,
        category: Long,
        direction: Direction = Direction.EXPENSE,
        state: TxnState = TxnState.COMMITTED,
        pkg: String = MAE,
    ): Long = db.txnDao().insert(
        sampleTxn(
            amountSen = amountSen,
            occurredAt = date * 1_000L + ++capture,
            localDate = LocalDate(date),
            categoryId = category,
            direction = direction,
            state = state,
            pkg = pkg,
            rawCaptureId = capture,
        ).copy(merchantRaw = key, merchantDisplay = key.lowercase(), merchantKey = key),
    )

    /** A friend's [amountSen] received from Touch 'n Go on [date], Uncategorized, made a payback of [bill]. */
    private fun payback(bill: Long, friend: String, amountSen: Long, date: Int): Long {
        val id = pay(friend, amountSen, date, uncategorized, Direction.REFUND, pkg = TNG)
        assertEquals(1, db.txnDao().setBill(id, bill, NOW))
        return id
    }

    private companion object {
        const val NOW = 9_999_999_999_999L
        const val MAE = "com.maybank2u.life"
        const val TNG = "my.com.tngdigital.ewallet"
    }
}
