package my.pinged

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.capture.SourcePrompt
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tap on spec 9.6's just-in-time prompt opens the capture sources, with
 * Settings beneath them, so back lands inside the app where the row lives.
 *
 * The intent is built the way `SourcePrompt` builds it, from the launcher's own
 * intent, because a `PendingIntent`'s intent cannot be read back on a device.
 * `SourcePromptTest` holds that the prompt carries this extra.
 */
@RunWith(AndroidJUnit4::class)
class SourcePromptRouteTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startEmpty() = discard()

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun aTapOnThePromptOpensTheSourcesAndBackShowsSettings() {
        ActivityScenario.launch<MainActivity>(promptTap()).use { scenario ->
            assertTrue(
                "A tap on the prompt did not open the capture sources",
                compose.waitFor { compose.countOf(SOURCES_SECTION) == 1 },
            )

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertTrue(
                "Back from the prompt's sources did not land on Settings",
                compose.waitFor { compose.countOf(SOURCES_SECTION) == 0 && compose.countOf(SETTINGS_SECTION) == 1 },
            )
        }
    }

    @Test fun aRebuildAfterLeavingTheSourcesStaysOnSettings() {
        ActivityScenario.launch<MainActivity>(promptTap()).use { scenario ->
            assertTrue(compose.waitFor { compose.countOf(SOURCES_SECTION) == 1 })
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertTrue(compose.waitFor { compose.countOf(SOURCES_SECTION) == 0 })

            scenario.recreate()

            assertTrue(
                "Rebuilding the Activity routed the prompt's tap again",
                compose.waitFor { compose.countOf(SETTINGS_SECTION) == 1 } && compose.countOf(SOURCES_SECTION) == 0,
            )
        }
    }

    private fun promptTap(): Intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        .putExtra(SourcePrompt.EXTRA_OPEN, SourcePrompt.OPEN_SOURCES)

    private fun discard() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /**
         * A section only the Sources screen draws. Not its title: Settings' row
         * that leads there has the same words.
         */
        const val SOURCES_SECTION = "ON YOUR PHONE"

        /** The section label above that row, drawn only on Settings' root. */
        const val SETTINGS_SECTION = "CAPTURE"
    }
}
