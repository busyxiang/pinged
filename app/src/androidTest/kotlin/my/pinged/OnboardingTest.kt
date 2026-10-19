package my.pinged

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.runBlocking
import my.pinged.capture.ListenerStatus
import my.pinged.capture.PingedComponents
import my.pinged.ledger.onboarding.ALLOW_ACCESS
import my.pinged.ledger.onboarding.ONBOARDING_HEADLINE
import my.pinged.ui.test.boundedWaitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First run (#156) as the user meets it: onboarding in place of the app until
 * the grant, then capture sources, then the ledger beneath them.
 *
 * `PingedTestRunner` ends first run for every other test, so each test here
 * starts it again with [FirstRun.forget] and sets the grant itself, and
 * [restore] puts both back: the grant is device state that outlives the run.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val component = PingedComponents.listener(context).flattenToString()
    private var grantedBefore = false
    private val updatedBefore = installWasUpdated

    @Before fun firstRunAgain() {
        grantedBefore = ListenerStatus.isGranted(context)
        runBlocking { FirstRun.forget(context) }
        // A fresh install, which a connected run over an earlier build is not.
        installWasUpdated = { false }
    }

    @After fun restore() {
        installWasUpdated = updatedBefore
        grant(grantedBefore)
        runBlocking { FirstRun.recordDone(context) }
    }

    @Test fun firstRunAsksForAccessAndTheGrantLeadsToSourcesThenTheLedger() {
        grant(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "A fresh install without notification access did not open on onboarding",
                compose.waitFor { compose.countOf(ONBOARDING_HEADLINE) == 1 },
            )
            assertEquals(
                "The tab bar was drawn under onboarding, a way past the one thing first run needs",
                0,
                compose.countOf(SPENDING),
            )

            // No scroll first: the button is in a fixed band below the
            // scrolling claims, so it is on screen at any height.
            compose.onNodeWithText(ALLOW_ACCESS).performClick()
            // Android's own screen is up now; the shell is the user's thumb.
            grant(true)
            // Back to this same instance, as the launcher icon would: its
            // saved state is what remembers the user asked. Not the system
            // Back: on emulator-5554 it returned here alone and failed to,
            // twice, inside the full suite -- presumably an earlier test's
            // Settings task taking the detail screen, which was not confirmed.
            // A launch without REORDER_TO_FRONT built a second instance.
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )

            assertTrue(
                "Coming back with the grant did not lead on to capture sources",
                compose.waitFor { compose.countOf(SOURCES_SECTION) == 1 },
            )
            assertTrue("The grant did not end first run", runBlocking { FirstRun.done(context) })

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertTrue(
                "Back from first run's sources did not land on the ledger",
                compose.waitFor { compose.countOf(SOURCES_SECTION) == 0 && compose.countOf(SPENDING) == 1 },
            )
        }
    }

    @Test fun anInstallThatAlreadyHasTheGrantNeverSeesOnboarding() {
        grant(true)
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "A granted install, as every upgrade is, did not open on the ledger",
                compose.waitFor { compose.countOf(SPENDING) == 1 },
            )
            assertEquals("Onboarding was drawn over a granted install", 0, compose.countOf(ONBOARDING_HEADLINE))
            assertTrue(
                "Seeing the grant did not record first run as over, so revoking it would bring onboarding back",
                waitFor { runBlocking { FirstRun.done(context) } },
            )
        }
    }

    @Test fun aGrantRevokedAfterFirstRunIsTheBannersNotOnboardings() {
        runBlocking { FirstRun.recordDone(context) }
        grant(false)
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "With first run over and no grant, the ledger did not say capture is off",
                compose.waitFor { compose.countOf(CAPTURE_OFF, substring = true) == 1 },
            )
            assertEquals("Onboarding came back once first run was over", 0, compose.countOf(ONBOARDING_HEADLINE))
        }
    }

    @Test fun anUpdatedInstallWithoutTheGrantIsPastFirstRun() = runBlocking {
        var recorded = false
        val shows = onboards(context, granted = false, done = { false }, recordDone = { recorded = true }, updated = { true })
        assertFalse("An install updated without the grant was held on onboarding, hiding its ledger", shows)
        assertTrue("An updated install was let past first run without recording it", recorded)
    }

    @Test fun aFirstRunStoreThatWillNotReadShowsTheAppInsteadOfThrowing() = runBlocking {
        val outcome = runCatching {
            onboards(context, granted = false, done = { throw IOException("simulated unreadable preferences file") })
        }
        assertTrue(
            "An unreadable first-run store threw out of `onboards`: ${outcome.exceptionOrNull()}. Its caller " +
                "is a bare lifecycleScope.launch, so that kills the process on every foreground",
            outcome.isSuccess,
        )
        assertFalse(
            "An unreadable first-run store held the user on onboarding, where nothing else can be reached",
            outcome.getOrThrow(),
        )
    }

    private fun grant(on: Boolean) {
        shell("cmd notification ${if (on) "allow_listener" else "disallow_listener"} $component")
        assertTrue(
            "The grant did not become $on; grants on this device are: " +
                shell("settings get secure enabled_notification_listeners"),
            waitFor { ListenerStatus.isGranted(context) == on },
        )
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    /**
     * [boundedWaitUntil], counting a throw as not yet: while the
     * Activity is behind Android's settings there is no hierarchy to query,
     * and that throws rather than answering false.
     */
    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { boundedWaitUntil(TIMEOUT) { runCatching(condition).getOrDefault(false) } }.isSuccess

    private fun ComposeTestRule.countOf(text: String, substring: Boolean = false): Int {
        waitForIdle()
        return onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /** The tab bar's first label, drawn only by the app proper. */
        const val SPENDING = "SPENDING"

        /** A section only the Sources screen draws; see `SourcePromptRouteTest`. */
        const val SOURCES_SECTION = "ON YOUR PHONE"

        /** The not-granted banner's label. */
        const val CAPTURE_OFF = "CAPTURE OFF"
    }
}
