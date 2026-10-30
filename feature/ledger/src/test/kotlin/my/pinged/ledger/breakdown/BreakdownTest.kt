package my.pinged.ledger.breakdown

import my.pinged.data.dao.BreakdownLine
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.Category
import my.pinged.ledger.home.ChooserRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * What Breakdown draws from its read (#342; CONTEXT.md, Breakdown): the
 * header, the lines in `WHO GOT IT`'s order, `No merchant` among them, the
 * kept-out line, and the grey of an untrusted month. Figures are the
 * `design/Breakdown.dc.html` board's.
 */
class BreakdownTest {
    private val september = YearMonth.of(2026, 9)
    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 0)

    private fun line(key: String?, name: String?, sen: Long, count: Int, currency: String = "MYR") =
        BreakdownLine(key, name, currency, sen, count)

    private val board = listOf(
        line("YUEN KEE", "Yuen Kee", 15_200L, 14),
        line("DOMINOS", "Domino's", -2_490L, 1),
        line(null, "a manual note", 9_600L, 7),
        line("FOODPANDA", "foodpanda", 18_690L, 9),
        line("ZUS", "ZUS Coffee", 9_240L, 8),
        line("TEALIVE", "Tealive", 9_240L, 5),
    )

    private fun read(
        lines: List<BreakdownLine> = board,
        today: LocalDate = LocalDate.of(2026, 10, 30),
        notCaptured: Int = 0,
        keptOut: List<NotInTotal> = emptyList(),
        category: Category? = food,
    ) = BreakdownRead(
        category = category,
        month = september,
        today = today,
        notCaptured = notCaptured,
        lines = lines,
        keptOut = keptOut,
        firstHeld = null,
        rows = emptyMap(),
        chooser = ChooserRead(),
    )

    @Test fun theLinesRankLargestFirstThenNegativeTiesByIdentityAndNoMerchantAmongThem() {
        val view = breakdown(read())!!

        assertEquals(
            listOf("foodpanda", "Yuen Kee", "No merchant", "Tealive", "ZUS Coffee", "Domino's"),
            view.lines.map { it.name },
        )
        assertEquals(listOf("186.90", "152.00", "96.00", "92.40", "92.40", "−24.90"), view.lines.map { it.amount })
        assertEquals(listOf("9×", "14×", "7×", "5×", "8×", "1×"), view.lines.map { it.count })
        assertEquals(listOf(false, false, true, false, false, false), view.lines.map { it.noMerchant })
    }

    @Test fun theHeaderIsTheLinesSumAndTheirCount() {
        val view = breakdown(read())!!

        // 186.90 + 152.00 + 96.00 + 92.40 + 92.40 - 24.90
        assertEquals("RM594.80", view.figures.figure)
        assertEquals("44 TRANSACTIONS", view.figures.count)
        assertEquals("SPENT IN SEPTEMBER 2026", view.figures.label)
        assertEquals("Food & Drinks", view.name)
        assertEquals("utensils", view.iconKey)
    }

    @Test fun anotherCurrencyIsNowhere() {
        val view = breakdown(read(lines = board + line("FOODPANDA", "foodpanda", 50_000L, 3, currency = "SGD")))!!

        assertEquals("RM594.80", view.figures.figure)
        assertEquals("44 TRANSACTIONS", view.figures.count)
        assertEquals(1, view.lines.count { it.name == "foodpanda" })
        assertEquals("186.90", view.lines.single { it.name == "foodpanda" }.amount)
    }

    @Test fun theMonthStillRunningIsSoFarAndANetBelowZeroIsNet() {
        assertEquals("SPENT SO FAR IN SEPTEMBER 2026", breakdown(read(today = LocalDate.of(2026, 9, 14)))!!.figures.label)
        val refunded = breakdown(read(lines = listOf(line("DOMINOS", "Domino's", -2_490L, 2))))!!
        assertEquals("NET SPENT IN SEPTEMBER 2026", refunded.figures.label)
        assertEquals("−RM24.90", refunded.figures.figure)
        assertEquals("2 TRANSACTIONS", refunded.figures.count)
        assertEquals("1 TRANSACTION", breakdown(read(lines = listOf(line("ZUS", "ZUS", 100L, 1))))!!.figures.count)
    }

    @Test fun anUntrustedMonthGreysAndSaysWhy() {
        val view = breakdown(read(notCaptured = 1))!!

        assertTrue(view.figures.greyed)
        assertEquals("MAY BE INCOMPLETE · 1 DAY NOT CAPTURED", view.figures.incomplete)
        val trusted = breakdown(read())!!
        assertFalse(trusted.figures.greyed)
        assertNull(trusted.figures.incomplete)
    }

    @Test fun theKeptOutLineCountsItsRowsWhateverTheyNetTo() {
        val view = breakdown(
            read(
                keptOut = listOf(
                    NotInTotal(NotInTotalLine.USER_EXCLUDED, "MYR", 1, 4_500L),
                    NotInTotal(NotInTotalLine.PENDING, "MYR", 1, -4_500L),
                    NotInTotal(NotInTotalLine.TRANSFERS, "SGD", 3, 9_000L),
                ),
            ),
        )!!

        assertEquals("RM0.00 kept out: 1 excluded, 1 awaiting review", view.figures.keptOut)
        assertEquals("1 awaiting review", view.figures.awaitingReview)
        assertNull(breakdown(read())!!.figures.keptOut)
        val excludedOnly = breakdown(read(keptOut = listOf(NotInTotal(NotInTotalLine.TRANSFERS, "MYR", 2, 1_000L))))!!
        assertEquals("RM10.00 kept out: 2 excluded, 0 awaiting review", excludedOnly.figures.keptOut)
        assertNull("Nothing held, nothing to open", excludedOnly.figures.awaitingReview)
    }

    @Test fun aCategoryThatIsGoneHasNoBreakdown() {
        assertNull(breakdown(read(category = null)))
    }
}
