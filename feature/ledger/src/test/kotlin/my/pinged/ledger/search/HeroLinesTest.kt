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

    /**
     * A found payback counted under its bill (spec 7.4) is in no count of
     * payments, refunds or rows kept out, and is named on its own line, so
     * the lines still add up to every row the export writes. September's
     * search in `PaybackCountingTest`: 3 payments, 2 counted refunds, 2
     * paybacks and 2 rows kept out are its 9 rows.
     */
    @Test fun aFoundPaybackIsNamedAsCountedWithItsBill() {
        val totals = listOf(SearchTotal("MYR", rowCount = 9, countedCount = 5, paymentCount = 3, netSen = 6_200L, paybackCount = 2))
        val hero = heroLines(totals, YearMonth.of(2026, 9), shown = 9)

        assertEquals("3 PAYMENTS · SEPTEMBER 2026", hero.label)
        assertEquals(
            listOf("RM20.67 A PAYMENT", "2 REFUNDS IN THE TOTAL", "2 PAYBACKS COUNTED WITH THEIR BILLS", "2 MORE NOT IN THE TOTAL"),
            hero.notes,
        )
        val said = (listOf(hero.label) + hero.notes).sumOf { line ->
            Regex("^([0-9]+) (PAYMENTS?|REFUNDS?|PAYBACKS?|MORE NOT IN THE TOTAL)").find(line)?.groupValues?.get(1)?.toInt() ?: 0
        }
        assertEquals("the hero said ${hero.label} / ${hero.notes}", 9, said)
    }

    /** A search for the friend: their paybacks' money is their bills', which it did not find. */
    @Test fun oneFoundPaybackIsNamedInTheSingular() {
        val totals = listOf(SearchTotal("MYR", rowCount = 1, countedCount = 0, paymentCount = 0, netSen = 0L, paybackCount = 1))
        val hero = heroLines(totals, null, shown = 1)

        assertEquals("NO PAYMENTS · ALL TIME", hero.label)
        assertEquals(null, hero.amount)
        assertEquals(listOf("1 PAYBACK COUNTED WITH ITS BILL"), hero.notes)
    }
}
