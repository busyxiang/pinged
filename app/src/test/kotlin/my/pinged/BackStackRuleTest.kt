package my.pinged

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rules `MainActivity` gives its back stack, asserted off the real
 * stack rather than through a composition.
 *
 * `rememberNavBackStack` returns a `MutableList<NavKey>`, and both rules are
 * decisions about that list and nothing else. Driven through the Activity they
 * would need two pointer events inside one frame and a paused clock to see the
 * first of them at all -- a test whose subject is the test harness.
 */
class BackStackRuleTest {

    @Test fun twoTapsInOneFramePushOneEntry() {
        val stack = mutableListOf<NavKey>(Ledger)

        stack.pushOnce(Sources)
        stack.pushOnce(Sources)

        assertEquals(
            "A second tap on the ledger's SOURCES control pushed a second " +
                "entry. Back then has to be pressed twice to leave a screen " +
                "the user reached once, and nothing on screen changed between " +
                "the two taps to explain it",
            listOf(Ledger, Sources),
            stack.toList(),
        )
    }

    @Test fun aDifferentDestinationOnTopStillPushes() {
        val stack = mutableListOf<NavKey>(Sources)

        stack.pushOnce(Ledger)

        assertEquals(
            "The guard refused a push onto a different destination, so it is " +
                "suppressing navigation rather than the immediate repeat",
            listOf(Sources, Ledger),
            stack.toList(),
        )
    }

    /**
     * The banner strip is drawn over the allow-list too, and its buttons lead
     * to the settings under it. A push there puts a second settings on the
     * stack, and back then goes through the allow-list to the first.
     */
    @Test fun showOnceReturnsToTheEntryUnderTheTop() {
        val stack = mutableListOf<NavKey>(Ledger, Settings, Sources)

        stack.showOnce(Settings)

        assertEquals(
            "The banner's button over the allow-list did not return to the settings " +
                "under it",
            listOf(Ledger, Settings),
            stack.toList(),
        )
    }

    @Test fun showOncePushesADestinationNotOnTheStack() {
        val stack = mutableListOf<NavKey>(Ledger)

        stack.showOnce(Settings)

        assertEquals("The banner's button over the ledger did not open settings", listOf(Ledger, Settings), stack.toList())
    }

    @Test fun popTakesTheTopEntryOff() {
        val stack = mutableListOf<NavKey>(Ledger, Sources)

        stack.pop()

        assertEquals(
            "The allow-list did not come off the stack, so the chevron and the " +
                "system gesture leave the user where they were",
            listOf(Ledger),
            stack.toList(),
        )
    }

    @Test fun popWillNotEmptyTheStack() {
        val stack = mutableListOf<NavKey>(Ledger)

        stack.pop()

        assertEquals(
            "The last entry was popped. NavDisplay then has no entry to render " +
                "and the app has no screen for that state",
            listOf(Ledger),
            stack.toList(),
        )
    }
}
