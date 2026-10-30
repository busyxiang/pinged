package my.pinged.ledger.merchantmonth

import my.pinged.data.dao.MerchantTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.ledger.home.ChooserRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * What Merchant month draws from its read (#344; CONTEXT.md, Merchant
 * month): the header is the merchant's ringgit line, named as `WHO GOT IT`
 * names it, a month with nothing counted is a zero header under the name the
 * merchant goes by, and an untrusted month greys.
 */
class MerchantMonthTest {
    private val september = YearMonth.of(2026, 9)

    private fun read(
        totals: List<MerchantTotal> = listOf(MerchantTotal("FOODPANDA", "foodpanda", "MYR", 4_700L, 3)),
        name: String? = "foodpanda",
        today: LocalDate = LocalDate.of(2026, 10, 30),
        notCaptured: Int = 0,
        keptOut: List<NotInTotal> = emptyList(),
    ) = MerchantMonthRead(
        identity = "FOODPANDA",
        name = name,
        month = september,
        today = today,
        notCaptured = notCaptured,
        totals = totals,
        keptOut = keptOut,
        firstHeld = null,
        rows = emptyList(),
        chooser = ChooserRead(),
    )

    @Test fun theHeaderIsTheMerchantsRinggitLine() {
        val view = merchantMonth(
            read(
                totals = listOf(
                    MerchantTotal("FOODPANDA", "foodpanda", "SGD", 2_000L, 1),
                    MerchantTotal("FOODPANDA", "foodpanda", "MYR", 4_700L, 3),
                ),
            ),
        )

        assertEquals("foodpanda", view.name)
        assertEquals("RM47.00", view.figures.figure)
        assertEquals("3 TRANSACTIONS", view.figures.count)
        assertEquals("SPENT IN SEPTEMBER 2026", view.figures.label)
    }

    @Test fun theNameIsTheLinesBeforeTheOneTheMerchantGoesBy() {
        assertEquals(
            "Its WHO GOT IT row's name",
            "Yuen Kee",
            merchantMonth(read(totals = listOf(MerchantTotal("FOODPANDA", "Yuen Kee", "MYR", 100L, 1)), name = "foodpanda")).name,
        )
        assertEquals("foodpanda", merchantMonth(read(totals = emptyList(), name = "foodpanda")).name)
        assertEquals("FOODPANDA", merchantMonth(read(totals = emptyList(), name = null)).name)
    }

    @Test fun nothingCountedIsAZeroHeaderWithTheKeptOutLine() {
        val view = merchantMonth(
            read(
                totals = listOf(MerchantTotal("FOODPANDA", "foodpanda", "SGD", 2_000L, 1)),
                keptOut = listOf(NotInTotal(NotInTotalLine.USER_EXCLUDED, "MYR", 1, 4_000L)),
            ),
        )

        assertEquals("RM0.00", view.figures.figure)
        assertEquals("0 TRANSACTIONS", view.figures.count)
        assertEquals("NET SPENT IN SEPTEMBER 2026", view.figures.label)
        assertEquals("RM40.00 kept out: 1 excluded, 0 awaiting review", view.figures.keptOut)
        assertNull(view.figures.awaitingReview)
    }

    @Test fun aRefundLargerThanThePaymentsIsNetAndTheMonthRunningIsSoFar() {
        val refunded = merchantMonth(read(totals = listOf(MerchantTotal("FOODPANDA", "foodpanda", "MYR", -2_490L, 2))))
        assertEquals("NET SPENT IN SEPTEMBER 2026", refunded.figures.label)
        assertEquals("−RM24.90", refunded.figures.figure)
        assertEquals("SPENT SO FAR IN SEPTEMBER 2026", merchantMonth(read(today = LocalDate.of(2026, 9, 14))).figures.label)
        assertEquals("1 TRANSACTION", merchantMonth(read(totals = listOf(MerchantTotal("FOODPANDA", "foodpanda", "MYR", 100L, 1)))).figures.count)
    }

    @Test fun anUntrustedMonthGreysAndSaysWhy() {
        val view = merchantMonth(read(notCaptured = 2))

        assertTrue(view.figures.greyed)
        assertEquals("MAY BE INCOMPLETE · 2 DAYS NOT CAPTURED", view.figures.incomplete)
        assertFalse(merchantMonth(read()).figures.greyed)
    }

    @Test fun theKeptOutLineCountsHeldAndExcludedRinggitRows() {
        val view = merchantMonth(
            read(
                keptOut = listOf(
                    NotInTotal(NotInTotalLine.USER_EXCLUDED, "MYR", 1, 5_000L),
                    NotInTotal(NotInTotalLine.PENDING, "MYR", 2, 5_200L),
                    NotInTotal(NotInTotalLine.PENDING, "SGD", 1, 700L),
                ),
            ),
        )

        assertEquals("RM102.00 kept out: 1 excluded, 2 awaiting review", view.figures.keptOut)
        assertEquals("2 awaiting review", view.figures.awaitingReview)
    }
}
