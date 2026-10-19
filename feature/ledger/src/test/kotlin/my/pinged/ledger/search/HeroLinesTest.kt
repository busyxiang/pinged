package my.pinged.ledger.search

import java.time.YearMonth
import my.pinged.data.SearchTotal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What search's hero says over its totals. Every expected string is written
 * out, not rebuilt the way the code builds it.
 */
class HeroLinesTest {

    /**
     * #216: 34 payments, a counted refund and 5 rows kept out are 40 rows,
     * the number "Export these N rows" names. The hero named 34 and 5 and
     * left the refund in neither.
     */
    @Test fun theHeroCountsEveryRowTheExportWrites() {
        val totals = listOf(SearchTotal("MYR", rowCount = 40, countedCount = 35, paymentCount = 34, netSen = 100_000))
        val hero = heroLines(totals, YearMonth.of(2026, 10), shown = 40)

        val said = (listOf(hero.label) + hero.notes).sumOf { line ->
            Regex("^([0-9]+) (PAYMENTS?|REFUNDS?|MORE NOT IN THE TOTAL)").find(line)?.groupValues?.get(1)?.toInt() ?: 0
        }
        assertEquals("the hero said ${hero.label} / ${hero.notes}", 40, said)
    }

    @Test fun aCountedRefundIsNamedApartFromThePayments() {
        val totals = listOf(SearchTotal("MYR", rowCount = 4, countedCount = 4, paymentCount = 3, netSen = 4_530))
        val hero = heroLines(totals, YearMonth.of(2026, 10), shown = 4)

        assertEquals("3 PAYMENTS · OCTOBER 2026", hero.label)
        assertEquals(listOf("RM15.10 A PAYMENT", "1 REFUND IN THE TOTAL"), hero.notes)
    }

    @Test fun refundsInAnotherCurrencyAreCountedToo() {
        val totals = listOf(
            SearchTotal("MYR", rowCount = 2, countedCount = 2, paymentCount = 1, netSen = 500),
            SearchTotal("USD", rowCount = 3, countedCount = 3, paymentCount = 1, netSen = 100),
        )
        val hero = heroLines(totals, null, shown = 5)

        assertEquals("1 PAYMENT · ALL TIME", hero.label)
        assertEquals(
            listOf("RM5.00 A PAYMENT", "ALSO USD 1.00 IN 1 PAYMENT", "3 REFUNDS IN THE TOTAL"),
            hero.notes,
        )
    }

    @Test fun noRefundSaysNothingOfRefunds() {
        val totals = listOf(SearchTotal("MYR", rowCount = 4, countedCount = 3, paymentCount = 3, netSen = 5_030))
        val hero = heroLines(totals, YearMonth.of(2026, 10), shown = 4)

        assertEquals(listOf("RM16.77 A PAYMENT", "1 MORE NOT IN THE TOTAL"), hero.notes)
    }
}
