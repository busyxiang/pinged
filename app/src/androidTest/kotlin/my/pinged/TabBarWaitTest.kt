package my.pinged

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import my.pinged.capture.ListenerStatus
import my.pinged.capture.PingedComponents
import my.pinged.ui.test.boundedWaitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [awaitTheTabBar] outlasts a first sample that is slow to end, which is what
 * CI's emulator gives every whole-Activity test. The sample is held [HELD_MS]
 * here so the bar is certainly missing at launch: a tap straight after it
 * fails every time with "could not find any node", as
 * `AlertSettingsChannelTest` did on CI at 0.49s.
 *
 * The grant is taken away for the test and given back after it, as
 * `OnboardingTest` does: with it the app is drawn from the first frame, and
 * `BackupReminderTest`, which runs earlier, leaves it on.
 */
@RunWith(AndroidJUnit4::class)
class TabBarWaitTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val component = PingedComponents.listener(context).flattenToString()
    private var grantedBefore = false

    /** The runner's `holdSampleMs`, if a run set one, for the next class. */
    private val before = AppStatus.beforeSample

    @Before fun holdTheSample() {
        grantedBefore = ListenerStatus.isGranted(context)
        grant(false)
        AppStatus.beforeSample = { delay(HELD_MS) }
    }

    @After fun releaseTheSample() {
        AppStatus.beforeSample = before
        grant(grantedBefore)
    }

    @Test fun theSettingsTabIsTappedOnlyOnceTheBarIsDrawn() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            assertEquals(
                "precondition: the bar was drawn while the sample was held, so nothing here waits for it",
                0,
                compose.onAllNodesWithText("SETTINGS").fetchSemanticsNodes().size,
            )

            compose.awaitTheTabBar()
            compose.onNodeWithText("SETTINGS").performClick()

            // A row only Settings draws.
            compose.boundedWaitUntil(TIMEOUT, "Settings") {
                compose.onAllNodesWithText("Delete everything").fetchSemanticsNodes().size == 1
            }
        }
    }

    private fun grant(on: Boolean) {
        shell("cmd notification ${if (on) "allow_listener" else "disallow_listener"} $component")
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (ListenerStatus.isGranted(context) != on && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(
            "The grant did not become $on; grants on this device are: " +
                shell("settings get secure enabled_notification_listeners"),
            ListenerStatus.isGranted(context) == on,
        )
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private companion object {
        /** Six times the 0.49s at which CI found no tab bar. */
        const val HELD_MS = 3_000L

        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L
    }
}
