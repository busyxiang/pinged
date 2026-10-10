package my.pinged.charts.model

import my.pinged.data.dao.MerchantTotal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The top-merchants ranking (#81, Merchants; #68; #92). */
class MerchantsTest {
    private val whole = MonthTrust(notCaptured = 0)

    private fun of(vararg rows: Triple<String, Long, Int>) =
        rows.map { (name, sen, count) -> MerchantTotal(name.uppercase(Locale.ROOT), name, "MYR", sen, count) }

    @Test fun fewerThanFiveAreAllDrawnLargestFirstWithNoRest() {
        val merchants = merchants(
            of(Triple("Shell", 21_000L, 4), Triple("Grab", 31_240L, 18), Triple("99 Speedmart", 26_815L, 11)),
            whole,
        )

        assertEquals(listOf("Grab", "99 Speedmart", "Shell"), merchants.rows.map { it.name })
        assertEquals(listOf("312.40", "268.15", "210.00"), merchants.rows.map { it.amount })
        assertEquals(listOf("18×", "11×", "4×"), merchants.rows.map { it.count })
        assertEquals(null, merchants.rest)
    }

    @Test fun moreThanFiveDrawTheTopFiveThenCountAndSumTheRest() {
        val merchants = merchants(
            of(
                Triple("Grab", 31_240L, 18), Triple("99 Speedmart", 26_815L, 11), Triple("Shell", 21_000L, 4),
                Triple("foodpanda", 18_690L, 9), Triple("Lotus's", 17_430L, 3), Triple("Watsons", 6_420L, 2),
                Triple("Mr DIY", 3_380L, 5), Triple("Tealive", 1_250L, 1),
            ),
            whole,
        )

        assertEquals(listOf("Grab", "99 Speedmart", "Shell", "foodpanda", "Lotus's"), merchants.rows.map { it.name })
        // 64.20 + 33.80 + 12.50
        assertEquals(MerchantRest("3 more merchants", "110.50"), merchants.rest)
    }

    @Test fun aNetNegativeMerchantSortsLastAndKeepsItsSign() {
        val merchants = merchants(
            of(Triple("Shopee", -4_500L, 2), Triple("Grab", 31_240L, 18), Triple("Shell", 0L, 2), Triple("Watsons", 6_420L, 2)),
            whole,
        )

        assertEquals(listOf("Grab", "Watsons", "Shell"), merchants.rows.map { it.name })
        assertEquals(listOf(MerchantRow("Shopee", "2×", "−45.00")), merchants.negatives)
    }

    @Test fun aNetNegativeMerchantPastTheTopFiveKeepsItsOwnRowAfterTheRest() {
        val six = (1..6).map { Triple("Shop $it", 1_000L * (7 - it), 1) } + Triple("Shopee", -4_500L, 2)

        val merchants = merchants(of(*six.toTypedArray()), whole)

        assertEquals("The refund was folded into the rest", MerchantRest("1 more merchant", "10.00"), merchants.rest)
        assertEquals(listOf(MerchantRow("Shopee", "2×", "−45.00")), merchants.negatives)
    }

    @Test fun onlyRinggitIsRanked() {
        val merchants = merchants(
            listOf(MerchantTotal("AGODA", "Agoda", "USD", 90_000L, 1), MerchantTotal("GRAB", "Grab", "MYR", 1_000L, 1)),
            whole,
        )

        assertEquals(listOf("Grab"), merchants.rows.map { it.name })
    }

    @Test fun equalNetsKeepOneOrderWhateverTheReadsOrder() {
        val a = MerchantTotal("A", "Aeon", "MYR", 1_000L, 1)
        val b = MerchantTotal("B", "Boost", "MYR", 1_000L, 1)

        assertEquals(merchants(listOf(a, b), whole), merchants(listOf(b, a), whole))
    }

    @Test fun anUntrustedMonthGreysTheRankingAndATrustedOneDoesNot() {
        val totals = of(Triple("Grab", 31_240L, 18))

        assertEquals(true, merchants(totals, MonthTrust(notCaptured = 1)).greyed)
        assertEquals(false, merchants(totals, whole).greyed)
    }

    @Test fun oneLeftOverIsOneMoreMerchant() {
        val six = (1..6).map { Triple("Shop $it", 1_000L * (7 - it), 1) }.toTypedArray()

        assertEquals(MerchantRest("1 more merchant", "10.00"), merchants(of(*six), whole).rest)
    }
}
