package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What enters a total, and what does not.
 *
 * §8: "Excluded and pending rows never enter a total." Every assertion here is
 * that rule from a different direction, because the failure it prevents is a
 * number on screen that looks right.
 */
@RunWith(AndroidJUnit4::class)
class LedgerAggregateTest {
    private lateinit var db: PingedDatabase

    /** 2026-09-07T00:00:00Z and 2026-09-08T00:00:00Z. */
    private val day1 = 1_788_739_200_000L
    private val day2 = day1 + 86_400_000L

    private val sept = LocalDate(20260901) to LocalDate(20260930)

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private fun uncategorized() = db.categoryDao().requireUncategorizedId()

    @Test fun aDayWhoseOnlyTransactionsAreExcludedHasNoSubtotalAndStillHasRows() {
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = uncategorized(), isExcluded = true),
        )

        val totals = db.txnDao().dayTotals(sept.first, sept.second)

        assertTrue(
            "An excluded row produced a subtotal, so the day reads as money spent " +
                "that §8 says must stay out of the arithmetic: $totals",
            totals.isEmpty(),
        )
        assertEquals("The row itself must still exist to be shown", 1, db.txnDao().countAll())
    }

    @Test fun aRefundReducesItsDayRatherThanAddingToIt() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = cat))
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 2_000L, categoryId = cat, direction = Direction.REFUND),
        )

        val totals = db.txnDao().dayTotals(sept.first, sept.second)

        assertEquals(
            "One day, one row -- a second means the sign changed the grouping: $totals",
            1, totals.size,
        )
        assertEquals(
            "A REFUND was added instead of subtracted, so a refunded purchase " +
                "reads as twice the spending",
            3_000L, totals.single().netSen,
        )
    }

    @Test fun aPendingRowIsInNeitherTotal() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = cat))
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 9_900L, categoryId = cat, state = TxnState.PENDING),
        )

        assertEquals(
            "A PENDING row entered the day subtotal. §7.1 sent it to a review " +
                "inbox that does not exist yet, so counting it states money as " +
                "spent that nothing has confirmed",
            5_000L, db.txnDao().dayTotals(sept.first, sept.second).single().netSen,
        )
        assertEquals(
            "A PENDING row entered the month total, which the day subtotal " +
                "above already excluded -- so the two disagree and the screen " +
                "reads as an arithmetic bug",
            5_000L, db.txnDao().monthTotals(sept.first, sept.second).single().netSen,
        )
    }

    @Test fun twoCurrenciesProduceTwoLinesAndNeverOneSum() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = cat))
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 700L, categoryId = cat, currency = "SGD"),
        )

        val totals = db.txnDao().monthTotals(sept.first, sept.second).sortedBy { it.currency }

        assertEquals("Currencies were summed together", 2, totals.size)
        assertEquals(
            "The two lines are not the two currencies that were written",
            listOf("MYR", "SGD"), totals.map { it.currency },
        )
        assertEquals("The ringgit line is not the ringgit total", 5_000L, totals[0].netSen)
        assertEquals("The SGD line is not the SGD total", 700L, totals[1].netSen)
    }

    @Test fun dayTotalsAreKeyedByLocalDateNotByOccurredAt() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 1_000L, categoryId = cat))
        db.txnDao().insert(sampleTxn(occurredAt = day2, amountSen = 2_000L, categoryId = cat))

        val totals = db.txnDao().dayTotals(sept.first, sept.second).sortedBy { it.localDate.yyyymmdd }

        assertEquals(
            "Two days produced one subtotal, so the grouping is not on the " +
                "stored local_date: $totals",
            2, totals.size,
        )
        assertEquals("The first day's subtotal is not its own", 1_000L, totals[0].netSen)
        assertEquals("The second day's subtotal is not its own", 2_000L, totals[1].netSen)
    }

    @Test fun anExcludedRowIsAbsentFromTheMonthByCategoryTotal() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = cat))
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 9_900L, categoryId = cat, isExcluded = true),
        )

        val top = db.txnDao().monthByCategory(sept.first, sept.second, limit = 10)

        assertEquals(
            "An excluded row's amount leaked into the category the review found unguarded",
            5_000L, top.single().netSen,
        )
    }

    @Test fun aPendingRowIsAbsentFromTheMonthByCategoryTotal() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = cat))
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 9_900L, categoryId = cat, state = TxnState.PENDING),
        )

        val top = db.txnDao().monthByCategory(sept.first, sept.second, limit = 10)

        assertEquals(
            "A PENDING row's amount leaked into the category the review found unguarded",
            5_000L, top.single().netSen,
        )
    }

    @Test fun anExcludedRowIsAbsentFromTheMonthTotal() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = cat))
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 9_900L, categoryId = cat, isExcluded = true),
        )

        assertEquals(
            "monthTotals only ever had a PENDING fixture; is_excluded was never exercised here",
            5_000L, db.txnDao().monthTotals(sept.first, sept.second).single().netSen,
        )
    }

    @Test fun twoCurrenciesOnTheSameDayProduceTwoDayTotals() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = cat))
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 700L, categoryId = cat, currency = "SGD"),
        )

        val totals = db.txnDao().dayTotals(sept.first, sept.second).sortedBy { it.currency }

        assertEquals("Currencies were summed together", 2, totals.size)
        assertEquals(
            "The two lines are not the two currencies that were written",
            listOf("MYR", "SGD"), totals.map { it.currency },
        )
        assertEquals("The ringgit line is not the ringgit total", 5_000L, totals[0].netSen)
        assertEquals("The SGD line is not the SGD total", 700L, totals[1].netSen)
    }

    /**
     * §9.1's chip, at the layer that has to make it true.
     *
     * The `UPDATE` is targeted rather than a whole-row write for the reason
     * `capture_source` has none: it names its own columns, so nothing else on
     * the row can be reverted by it. That is asserted here rather than assumed
     * -- the amount and the state are read back -- because a row that changed
     * category and lost its amount is money quietly rewritten.
     */
    @Test fun reassigningACategoryMovesItInTheSummaryAndKeepsEveryOtherColumn() {
        val cats = db.categoryDao().all().filter { !it.isProtected }.take(2).map { it.id }
        val id = db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 4_200L, categoryId = cats[0]),
        )

        val rows = db.txnDao().setCategory(id, cats[1], updatedAt = day1 + 1)

        assertEquals(
            "setCategory did not report writing the row it was given, so the " +
                "caller cannot tell a moved transaction from a deleted one",
            1, rows,
        )
        val after = db.txnDao().byId(id)!!
        assertEquals("The row is not in the category it was moved to", cats[1], after.categoryId)
        assertTrue(
            "user_edited was not set, so §5.5's re-parse could silently undo a " +
                "decision a person made",
            after.userEdited,
        )
        assertEquals("A targeted UPDATE must not disturb the amount", 4_200L, after.amountSen)
        assertEquals("...nor the state", TxnState.COMMITTED, after.state)
        assertEquals("...nor the day the money was spent on", day1, after.occurredAt)
        assertEquals(
            "...and updated_at must be the one the caller passed: an unmoved " +
                "timestamp is a row an export cannot order against its neighbours",
            day1 + 1, after.updatedAt,
        )
        assertEquals(
            "The summary still credits the category the row was moved off, so " +
                "the top three names a category the money is no longer in",
            cats[1],
            db.txnDao().monthByCategory(sept.first, sept.second, limit = 3).single().categoryId,
        )
    }

    @Test fun theMonthSummaryRanksCategoriesAndCapsThem() {
        val cats = db.categoryDao().all().filter { !it.isProtected }.take(4).map { it.id }
        assertTrue("The seed did not provide four unprotected categories", cats.size == 4)
        cats.forEachIndexed { i, id ->
            db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = (i + 1) * 1_000L, categoryId = id))
        }

        val top = db.txnDao().monthByCategory(sept.first, sept.second, limit = 3)

        assertEquals(
            "The LIMIT did not cap the summary at the three §9.1 asks for, so " +
                "the query and the layout disagree about how many lines exist",
            3, top.size,
        )
        assertEquals(
            "Categories were not ordered by size, so the summary names the wrong three",
            listOf(4_000L, 3_000L, 2_000L), top.map { it.netSen },
        )
    }
}
