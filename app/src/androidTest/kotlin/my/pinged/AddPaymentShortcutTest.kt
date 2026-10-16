package my.pinged

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.ledger.entry.ADD_PAYMENT
import my.pinged.ledger.entry.AMOUNT
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.4's **Add payment** launcher shortcut (#107): the entry screen on
 * the Spending stack above the Ledger, cold and warm, and back to the Ledger.
 *
 * The intent launched is the one the system parsed out of `shortcuts.xml`,
 * read back through [ShortcutManager.getManifestShortcuts], not one built
 * here from [AddPaymentShortcut]'s constants: a launcher fires exactly that
 * intent, so a declared extra, package or class that drifted from what
 * `MainActivity` reads fails these tests rather than agreeing with itself.
 *
 * The bar is fixed below the `NavDisplay`, not in a scroll, so its tabs are
 * tapped without `performScrollTo`, as `ChartsTabTest` taps them.
 */
@RunWith(AndroidJUnit4::class)
class AddPaymentShortcutTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startEmpty() = discard()

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun aColdStartFromTheShortcutOpensTheEntryAndBackShowsTheLedger() {
        ActivityScenario.launch<MainActivity>(declaredIntent()).use { scenario ->
            awaitTheEntryScreen("A cold start from the shortcut did not open the entry screen")

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            awaitTheLedger("Back from the shortcut's entry screen did not land on the Ledger")
        }
    }

    /**
     * The Activity keeps the shortcut's intent for its whole life, so a
     * rebuild must restore the saved stack rather than route it again and put
     * the entry back over a list the user had returned to.
     */
    @Test fun aRebuildAfterLeavingTheEntryStaysOnTheLedger() {
        ActivityScenario.launch<MainActivity>(declaredIntent()).use { scenario ->
            awaitTheEntryScreen("A cold start from the shortcut did not open the entry screen")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitTheLedger("Back from the shortcut's entry screen did not land on the Ledger")

            scenario.recreate()

            awaitTheLedger("Rebuilding the Activity opened the shortcut's entry screen again")
        }
    }

    @Test fun theShortcutFromChartsOpensTheEntryOnSpendingAndBackShowsTheLedger() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("The app never drew its tab bar", compose.waitFor { compose.countOf(CHARTS) == 1 })
            compose.onNodeWithText(CHARTS).performClick()
            assertTrue(
                "Charts never showed",
                compose.waitFor { compose.countOf(TOTAL_SPENT) + compose.countOf(CHARTS_EMPTY) == 1 },
            )

            val intent = declaredIntent()
            assertTrue(
                "The system no longer fires the shortcut with FLAG_ACTIVITY_CLEAR_TASK " +
                    "(flags ${Integer.toHexString(intent.flags)}). MainActivity routes it only " +
                    "in onCreate, so a running app would now ignore it",
                intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0,
            )
            ActivityScenario.launch<MainActivity>(intent).use { shortcut ->
                awaitTheEntryScreen("The shortcut, with the app on Charts, did not open the entry screen")

                shortcut.onActivity { it.onBackPressedDispatcher.onBackPressed() }

                awaitTheLedger("Back from the shortcut's entry screen did not land on the Spending list")
                compose.onNodeWithText(SPENDING).assertIsSelected()
            }
        }
    }

    /** The shortcut's intent as the system holds it for a launcher. */
    private fun declaredIntent(): Intent {
        val shortcut = context.getSystemService(ShortcutManager::class.java)
            .manifestShortcuts
            .singleOrNull { it.id == AddPaymentShortcut.ID }
        assertNotNull("The manifest declares no `${AddPaymentShortcut.ID}` shortcut", shortcut)
        val intent = shortcut!!.intent
        assertNotNull("The `${AddPaymentShortcut.ID}` shortcut carries no intent", intent)
        return intent!!
    }

    /** The entry screen, and no tab bar over it. */
    private fun awaitTheEntryScreen(why: String) {
        assertTrue(
            why,
            compose.waitFor {
                compose.onAllNodesWithContentDescription(AMOUNT).fetchSemanticsNodes().size == 1 &&
                    compose.countOf(SPENDING) == 0
            },
        )
    }

    /** The Spending list: its tab bar and its button, and no entry screen. */
    private fun awaitTheLedger(why: String) {
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
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        const val SPENDING = "SPENDING"
        const val CHARTS = "CHARTS"

        /** Lines only Charts draws, with data and without; see `ChartsTabTest`. */
        const val TOTAL_SPENT = "TOTAL SPENT"
        const val CHARTS_EMPTY = "Charts begin on the first day Pinged sees a notification."
    }
}
