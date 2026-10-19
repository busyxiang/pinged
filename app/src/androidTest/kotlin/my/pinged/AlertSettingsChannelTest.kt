package my.pinged

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.capture.SourcePrompt
import my.pinged.ledger.settings.CAPTURE_ALERTS
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * #217: "Tell me if capture stops" opens Android's notification settings for
 * this app, and on a device where Pinged has never posted that page lists no
 * channel at all, so there is nothing there to turn on or off.
 *
 * The channel is deleted before the activity starts rather than assumed absent:
 * any earlier test that posted a prompt or an alert has created it, and a check
 * that only holds on a clean device passes either way on this one.
 * `PingedTestRunner` grants `POST_NOTIFICATIONS`, so the tap takes the row's
 * direct route to the settings page rather than the permission dialog.
 */
class AlertSettingsChannelTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = instrumentation.targetContext.getSystemService(NotificationManager::class.java)

    @Test fun theRowDoesNotOpenSettingsBeforeTheChannelExists() {
        manager.deleteNotificationChannel(SourcePrompt.CHANNEL_ID)

        compose.onNodeWithText("SETTINGS").performClick()
        compose.onNodeWithText(CAPTURE_ALERTS).performScrollTo().performClick()

        assertNotNull(
            "The settings page was opened with no channel on it",
            manager.getNotificationChannel(SourcePrompt.CHANNEL_ID),
        )
    }

    /** Back out of the system page, so it does not sit over the next test. */
    @After fun leaveSettings() {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
}
