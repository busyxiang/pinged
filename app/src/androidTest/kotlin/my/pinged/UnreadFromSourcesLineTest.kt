package my.pinged

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.ui.test.boundedWaitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Could not read filtered to one app (#368), through the real Activity:
 * `GRABPAY` in Charts' sources line opens it, its *Show every app* clears it,
 * Settings opens it unfiltered, and teaching a GrabPay message from it takes
 * GrabPay out of the line's warning -- Teach, then `user_template_rule`, then
 * `SourcesLine` on the next read.
 *
 * MAE, which the shipped pack reads, and GrabPay, which it does not, both
 * switched on, each with one unread capture. `createEmptyComposeRule`, for
 * the reason `BackupReminderTest` gives.
 */
@RunWith(AndroidJUnit4::class)
class UnreadFromSourcesLineTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val today = LocalDate.now()

    @Before fun seed() {
        discard()
        val db = Databases.shared(context)
        // A history start, so Charts draws the month and its hero.
        db.captureDayDao().recordListenerBound(LocalDates.of(today), bound = true)
        db.captureSourceDao().insertIfNew(CaptureSource(MAE, "MAE", enabled = true, firstSeenAt = 0L))
        db.captureSourceDao().insertIfNew(CaptureSource(GRAB, "GrabPay", enabled = true, firstSeenAt = 0L))
        db.rawCaptureDao().insert(unmatched(1, MAE, MAE_TEXT))
        db.rawCaptureDao().insert(unmatched(2, GRAB, GRAB_TEXT))
    }

    @After fun leaveAnOpenableDatabase() = discard()

    @Test fun grabPayInTheSourcesLineOpensItsFilterAndShowEveryAppListsBoth() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openCharts()
            tapInTheLine("GRABPAY")

            awaitFilteredToGrabPay()
            compose.onNode(hasText(SHOW_EVERY_APP) and hasClickAction()).performClick()

            assertTrue(
                "Show every app did not list both apps' captures",
                compose.waitFor { compose.countOf(MAE_TEXT) == 1 && compose.countOf(GRAB_TEXT) == 1 && compose.countOf(ONLY_GRABPAY) == 0 },
            )
            // Replaced, not pushed: Back still returns to the line.
            compose.onNodeWithContentDescription("Back").performClick()
            assertTrue("Back from every app's list did not return to Charts", compose.waitFor { compose.countOf(WARNING, substring = true) == 1 })
        }
    }

    @Test fun teachingGrabPayFromItsFilterTakesItOutOfTheWarning() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openCharts()
            tapInTheLine("GRABPAY")
            awaitFilteredToGrabPay()

            compose.onNode(hasText("Teach Pinged this") and hasClickAction()).performScrollTo().performClick()
            assertTrue("Teach did not open", compose.waitFor { compose.countOf("Save this rule") == 1 })
            compose.onNode(hasText("RM12.00") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("This message has no merchant in it").performScrollTo().performClick()
            compose.onNodeWithText("Save this rule").performClick()

            // Saving returns to the filtered list, which GrabPay's one capture has left.
            assertTrue(
                "The filtered list did not say it holds nothing from GrabPay",
                compose.waitFor { compose.countOf("Nothing here from GRABPAY", substring = true) == 1 },
            )
            compose.onNodeWithContentDescription("Back").performClick()
            // The line, read again on Charts' return: GrabPay named, and as read.
            assertTrue(
                "Charts still warns that Pinged cannot read GrabPay after a GrabPay message was taught",
                compose.waitFor { compose.textsWith("GRABPAY").let { it.size == 1 && it.single().startsWith("FROM ") && "CANNOT READ" !in it.single() } },
            )
        }
    }

    @Test fun settingsOpensCouldNotReadUnfiltered() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.awaitTheTabBar()
            compose.onNodeWithText("SETTINGS").performClick()
            compose.onNodeWithText("Notifications Pinged could not read").performScrollTo().performClick()

            assertTrue(
                "Settings' Could not read did not list both apps' captures",
                compose.waitFor { compose.countOf(MAE_TEXT) == 1 && compose.countOf(GRAB_TEXT) == 1 },
            )
            assertEquals("Settings' Could not read is filtered", 0, compose.countOf(SHOW_EVERY_APP))
        }
    }

    private fun openCharts() {
        compose.awaitTheTabBar()
        compose.onNodeWithText("CHARTS").performClick()
        assertTrue("Charts did not warn that Pinged cannot read GrabPay", compose.waitFor { compose.countOf(WARNING, substring = true) == 1 })
    }

    /**
     * A tap on [name] where the line lays it out: the name is a run inside
     * one wrapping text, so it has no node of its own to click.
     */
    private fun tapInTheLine(name: String) {
        val line = compose.onNode(hasText(WARNING, substring = true))
        val layouts = mutableListOf<TextLayoutResult>()
        line.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        val start = layout.layoutInput.text.text.lastIndexOf(name)
        val box = layout.getBoundingBox(start + name.length / 2)
        line.performTouchInput { click(box.center) }
    }

    private fun awaitFilteredToGrabPay() {
        assertTrue(
            "GRABPAY did not open Could not read filtered to GrabPay",
            compose.waitFor { compose.countOf(ONLY_GRABPAY) == 1 && compose.countOf(GRAB_TEXT) == 1 },
        )
        assertEquals("The GrabPay filter lists MAE's capture", 0, compose.countOf(MAE_TEXT))
    }

    private fun unmatched(index: Int, pkg: String, text: String) = RawCapture(
        sourcePackage = pkg,
        postedAt = System.currentTimeMillis() - index,
        whenMillis = null,
        capturedAt = System.currentTimeMillis() - index,
        sbnKey = "0|$pkg|$index|null|0",
        notifId = index,
        notifTag = null,
        userHandle = 0,
        channelId = "txn",
        flags = 0,
        arrival = Arrival.POSTED,
        title = null,
        text = text,
        bigText = null,
        subText = null,
        extrasJson = null,
        contentHash = "unread-from-line-$index",
        parseStatus = ParseStatus.UNMATCHED,
    )

    private fun discard() {
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { boundedWaitUntil(TIMEOUT) { condition() } }.isSuccess

    /** The whole text of every node whose text holds [part]. */
    private fun ComposeTestRule.textsWith(part: String): List<String> =
        onAllNodesWithText(part, substring = true).fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.Text].joinToString("") { it.text }
        }

    private fun ComposeTestRule.countOf(text: String, substring: Boolean = false): Int =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        const val MAE = "com.maybank2u.life"
        const val GRAB = "com.grabtaxi.passenger"
        const val MAE_TEXT = "Your fixed deposit of RM1,000.00 has matured."
        const val GRAB_TEXT = "Paid RM12.00 to Kedai Satu"
        const val WARNING = "GRABPAY IS ON BUT PINGED CANNOT READ IT YET"
        const val ONLY_GRABPAY = "ONLY GRABPAY"
        const val SHOW_EVERY_APP = "Show every app"
    }
}
