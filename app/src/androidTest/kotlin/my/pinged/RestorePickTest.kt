package my.pinged

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **A restore picked from a document the provider will not open is
 * reported, and kills nothing.** `openInputStream` throws
 * `FileNotFoundException` for it; called in the picker's callback, on the
 * main thread, that took the app down with it.
 *
 * The picker is answered by an `Instrumentation.ActivityMonitor`, as
 * `RescueFromSettingsTest` answers its own, with a `file:` URI naming
 * nothing -- the same `ContentResolver.openInputStream` a provider's
 * document goes through, failing the way a document deleted since it was
 * picked does.
 */
@RunWith(AndroidJUnit4::class)
class RestorePickTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val gone = File(context.cacheDir, "picked-and-gone.json")

    private val picker = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
            return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(gone)))
        }
    }

    @Before fun anEmptyLedger() {
        gone.delete()
        instrumentation.addMonitor(picker)
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @After fun removeThePicker() {
        instrumentation.removeMonitor(picker)
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun aPickedDocumentThatWillNotOpenIsReportedAndChangesNothing() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("SETTINGS").performClick()
            compose.onNodeWithText("Replace everything from a backup").performScrollTo().performClick()
            compose.onNodeWithText("Continue").performClick()

            assertTrue(
                "a document that would not open was not reported as a restore that did not finish",
                compose.waitFor { compose.countOf("RESTORE DID NOT FINISH") == 1 },
            )
            assertTrue(
                "the report does not say nothing was changed",
                compose.countOf("Nothing on this phone was changed.", substring = true) == 1,
            )
        }
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String, substring: Boolean = false): Int {
        waitForIdle()
        return onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L
    }
}
