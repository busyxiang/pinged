package my.pinged.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The one money formatter every screen draws with. Each expected string is
 * written out, from the artboard, not built the way the code builds it.
 */
class MoneyTest {

    @Test fun ringgitPrintsTheSymbolAndGroupsThousands() {
        assertEquals("RM12.34", ringgit(1_234L))
        assertEquals("RM2,847.30", ringgit(284_730L))
        assertEquals("RM0.05", ringgit(5L))
        assertEquals("RM0.00", ringgit(0L))
    }

    @Test fun aNegativeTakesTheTrueMinusBeforeTheSymbol() {
        assertEquals("−RM120.00", ringgit(-12_000L))
        assertEquals("−RM0.01", ringgit(-1L))
    }

    @Test fun theDigitsVariantDropsTheSymbolAndKeepsTheMinus() {
        assertEquals("2,847.30", ringgitDigits(284_730L))
        assertEquals("−120.00", ringgitDigits(-12_000L))
    }

    @Test fun anotherCurrencyNamesItsCodeWithOrWithoutTheSymbol() {
        assertEquals("USD 12.00", money(1_200L, "USD", symbol = true))
        assertEquals("USD −1,200.00", money(-120_000L, "USD", symbol = false))
        assertEquals("RM12.00", money(1_200L, RINGGIT, symbol = true))
        assertEquals("12.00", money(1_200L, RINGGIT, symbol = false))
    }

    @Test fun theGroupingDoesNotFollowTheDeviceLocale() {
        val device = Locale.getDefault()
        try {
            // Groups with a full stop and marks decimals with a comma.
            Locale.setDefault(Locale.GERMANY)
            assertEquals("RM1,234,567.89", ringgit(123_456_789L))
        } finally {
            Locale.setDefault(device)
        }
    }

    @Test fun theLargestSenStillFormats() {
        assertEquals("RM92,233,720,368,547,758.07", ringgit(Long.MAX_VALUE))
    }
}
