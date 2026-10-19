package my.pinged

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.ledger.entry.ADD_PAYMENT
import my.pinged.ledger.entry.AMOUNT
import my.pinged.ledger.entry.SAVE
import my.pinged.ledger.home.ENTERED_BY_HAND
import my.pinged.ui.test.boundedWaitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.4's way in (#99): the Spending tab's **Add payment** button opens the
 * entry screen over the list with the tab bar hidden, back returns to the
 * list, and a saved entry is on the list reading `Entered by hand`.
 *
 * `createEmptyComposeRule`, for the reason `BackupReminderTest` gives: the
 * database has to be empty before the Activity reads it.
 */
@RunWith(AndroidJUnit4::class)
class ManualEntryRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startEmpty() = discard()

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun addPaymentOpensTheEntryWithoutTheBarAndBackReturnsToTheList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTheEntryScreen()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            awaitTheList("System back from the entry screen did not return to the list")
            assertEquals("Back saved a payment nobody saved", 0, Databases.txnDao(context).countAll())
        }
    }

    @Test fun aSavedEntryIsOnTheListEnteredByHand() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openTheEntryScreen()

            "1250".forEach { compose.onNodeWithContentDescription(AMOUNT).performTextInput(it.toString()) }
            compose.onNodeWithText(SAVE).performScrollTo().performClick()

            awaitTheList("Save did not return to the list")
            assertTrue(
                "The list does not show the RM12.50 entry reading `Entered by hand`",
                compose.scrollFeedTo(hasText(ENTERED_BY_HAND, substring = true), TIMEOUT) &&
                    compose.countOf("12.50") >= 1 &&
                    compose.onAllNodes(hasText(ENTERED_BY_HAND, substring = true)).fetchSemanticsNodes().size == 1,
            )
        }
    }

    @Test fun theDraftSurvivesAProcessDeath() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTheEntryScreen()
            "1250".forEach { compose.onNodeWithContentDescription(AMOUNT).performTextInput(it.toString()) }
            // The chip before the merchant: a merchant the dictionary files
            // pre-selects its chip, and a tap on a pre-selected chip clears it.
            assertTrue("The category chips never arrived", compose.waitFor { compose.countOf(FOOD) == 1 })
            // The chip row into the screen's column first, then the chip into
            // the row: `performScrollTo` scrolls only the closest scrolling
            // parent, the row. Measured at CI's 320x640 with the number
            // keyboard (283px) and the no-access banner up, the column's
            // viewport is 97px (window y 260 to 357) and the chip sat at
            // y 373, clipped to nothing, so a touch scrolled to by the row
            // alone landed on no chip; scrolled this way it is drawn at
            // y 313 to 357 and the touch selects it.
            compose.onNode(hasScrollAction() and hasAnyChild(hasText(FOOD))).performScrollTo()
            compose.onNodeWithText(FOOD).performScrollTo().performClick()
            compose.onNodeWithText(FOOD).assertIsSelected()
            // The amount, then the merchant, then the note.
            compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("Kopitiam")
            compose.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput("teh tarik")

            // Every holder dropped, then the Activity rebuilt from its saved
            // state: what comes back is what the entry's `SavedStateHandle`
            // carries, and nothing a holder kept in memory.
            scenario.onActivity { it.viewModelStore.clear() }
            scenario.recreate()

            assertTrue(
                "The entry screen did not come back with its draft after the process death",
                compose.waitFor {
                    compose.countOf("RM12.50") == 1 && compose.countOf("Kopitiam") == 1 &&
                        compose.countOf("teh tarik") == 1 && compose.countOf(FOOD) == 1
                },
            )
            compose.onNodeWithText(FOOD).performScrollTo().assertIsSelected()
            assertEquals("The draft was saved by coming back", 0, Databases.txnDao(context).countAll())
        }
    }

    /**
     * The + on the Spending list, then the entry screen with no tab bar.
     *
     * By substring: on an empty ledger the + is day one's cash offer, whose
     * name is its own words and then [ADD_PAYMENT].
     */
    private fun openTheEntryScreen() {
        assertTrue(
            "The Spending list has no Add payment button",
            compose.waitFor { compose.onAllNodesWithContentDescription(ADD_PAYMENT, substring = true).fetchSemanticsNodes().size == 1 },
        )
        // Not scrolled to: the button floats over the list, outside any
        // scrolling container, as the tab bar's items do.
        compose.onNodeWithContentDescription(ADD_PAYMENT, substring = true).performClick()
        assertTrue(
            "The + did not open the entry screen",
            compose.waitFor { compose.onAllNodesWithContentDescription(AMOUNT).fetchSemanticsNodes().size == 1 },
        )
        assertEquals("The tab bar is drawn over the entry screen", 0, compose.countOf(SPENDING))
    }

    /** The Spending list: its tab bar and its button, and no entry screen. */
    private fun awaitTheList(why: String) {
        assertTrue(
            why,
            compose.waitFor {
                compose.countOf(SPENDING) == 1 &&
                    compose.onAllNodesWithContentDescription(ADD_PAYMENT, substring = true).fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithContentDescription(AMOUNT).fetchSemanticsNodes().isEmpty()
            },
        )
    }

    private fun discard() {
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { boundedWaitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /** The tab bar's first tab, which is drawn only on a tab's root. */
        const val SPENDING = "SPENDING"

        /** The seed's first category: a chip on a fresh ledger. */
        const val FOOD = "Food & Drinks"
    }
}
