package my.pinged.charts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hero's label, figure and lines (#81, Hero; #68, #73). */
class HeroTest {
    private val whole = MonthTrust(notCaptured = 0)

    @Test fun aPositiveNetIsTotalSpentWithNoSplit() {
        val hero = hero(netSen = 284_730L, spentSen = 300_000L, cameBackSen = 15_270L, trust = whole)

        assertEquals("TOTAL SPENT", hero.label)
        assertEquals("RM2,847.30", hero.figure)
        assertNull("A positive month has nothing to explain: ${hero.split}", hero.split)
    }

    @Test fun aZeroNetIsNetSpentWithTheSplit() {
        val hero = hero(netSen = 0L, spentSen = 4_500L, cameBackSen = 4_500L, trust = whole)

        assertEquals("NET SPENT", hero.label)
        assertEquals("RM0.00", hero.figure)
        assertEquals("SPENT RM45.00 · CAME BACK RM45.00", hero.split)
    }

    @Test fun aNegativeNetIsNetSpentSignedWithTheSplit() {
        val hero = hero(netSen = -12_000L, spentSen = 4_500L, cameBackSen = 16_500L, trust = whole)

        assertEquals("NET SPENT", hero.label)
        assertEquals("The figure lost its sign", "−RM120.00", hero.figure)
        assertEquals("SPENT RM45.00 · CAME BACK RM165.00", hero.split)
    }

    @Test fun anUntrustedMonthGreysAndSaysWhyWithoutClaimingAFloor() {
        val hero = hero(netSen = 284_730L, spentSen = 284_730L, cameBackSen = 0L, trust = MonthTrust(3))

        assertTrue("An untrusted hero was drawn in the normal colour", hero.greyed)
        assertEquals("MAY BE INCOMPLETE · 3 DAYS NOT CAPTURED", hero.incomplete)
    }

    @Test fun oneNotCapturedDayIsEnoughAndReadsInTheSingular() {
        val hero = hero(netSen = 100L, spentSen = 100L, cameBackSen = 0L, trust = MonthTrust(1))

        assertTrue(hero.greyed)
        assertEquals("MAY BE INCOMPLETE · 1 DAY NOT CAPTURED", hero.incomplete)
    }

    @Test fun aMonthWithNoGapsIsDrawnNormally() {
        val hero = hero(netSen = 100L, spentSen = 100L, cameBackSen = 0L, trust = whole)

        assertFalse("A whole month was greyed", hero.greyed)
        assertNull(hero.incomplete)
    }
}
