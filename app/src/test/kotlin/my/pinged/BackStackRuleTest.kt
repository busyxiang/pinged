package my.pinged

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun tabs(
        spending: List<NavKey> = listOf(Ledger),
        settings: List<NavKey> = listOf(Settings),
        selected: Tab = Tab.Spending,
    ) = TabStacks(
        mapOf(Tab.Spending to spending.toMutableList(), Tab.Settings to settings.toMutableList()),
        mutableStateOf(selected),
    )

    @Test fun selectingSettingsShowsItsRootAndKeepsTheLedgerUnderneath() {
        val tabs = tabs()

        tabs.select(Tab.Settings)

        assertEquals(
            "The SETTINGS tab did not put settings on top; the ledger's entries must stay " +
                "in the list so its holder and scroll position survive the visit",
            listOf(Ledger, Settings),
            tabs.entries,
        )
    }

    @Test fun aTabKeepsItsOwnStackWhileAnotherIsShown() {
        val tabs = tabs(settings = listOf(Settings, Sources), selected = Tab.Settings)

        tabs.select(Tab.Spending)
        tabs.select(Tab.Settings)

        assertEquals(
            "Leaving settings over the allow-list and coming back did not return to the allow-list",
            listOf(Ledger, Settings, Sources),
            tabs.entries,
        )
    }

    @Test fun backOnSettingsReturnsToSpendingRatherThanLeavingTheApp() {
        val tabs = tabs(selected = Tab.Settings)

        assertTrue("Back on the settings root was not handled, so it would exit the app", tabs.back())

        assertEquals(Tab.Spending, tabs.selected)
    }

    @Test fun backOverAPushedScreenPopsItAndStaysOnTheTab() {
        val tabs = tabs(settings = listOf(Settings, Sources), selected = Tab.Settings)

        assertTrue(tabs.back())

        assertEquals(Tab.Settings, tabs.selected)
        assertEquals(listOf(Ledger, Settings), tabs.entries)
    }

    @Test fun backOnTheSpendingRootIsLeftToTheSystem() {
        assertFalse("Back on the ledger was swallowed, so the app could never be left", tabs().back())
    }

    @Test fun showSettingsSelectsTheTabAndUnwindsItToItsRoot() {
        val tabs = tabs(settings = listOf(Settings, Sources))

        tabs.showSettings()

        assertEquals(Tab.Settings, tabs.selected)
        assertEquals(
            "The banner's button over the allow-list left the allow-list showing",
            listOf(Ledger, Settings),
            tabs.entries,
        )
    }

    @Test fun theBarShowsOnATabsRootAndNotOverAPushedScreen() {
        assertTrue(tabs().showsBar)
        assertTrue(tabs(selected = Tab.Settings).showsBar)
        assertFalse(tabs(settings = listOf(Settings, Sources), selected = Tab.Settings).showsBar)
    }

    @Test fun showingSettingsOnSettingsHasNothingToOffer() {
        assertTrue(tabs(selected = Tab.Settings).showingSettingsRoot)
        assertFalse(tabs().showingSettingsRoot)
        assertFalse(tabs(settings = listOf(Settings, Sources), selected = Tab.Settings).showingSettingsRoot)
    }
}
