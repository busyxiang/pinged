package my.pinged

import android.app.NotificationManager
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.capture.SourcePrompt
import my.pinged.ledger.settings.CAPTURE_ALERTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * #217: "Tell me if capture stops" opens Android's notification settings for
 * this app, and on a device where Pinged has never posted that page lists no
 * channel at all, so there is nothing there to turn on or off.
 *
 * The channel is deleted before the tap rather than assumed absent: any earlier
 * test that posted a prompt or an alert has created it, and a check that only
 * holds on a clean device passes either way on this one. `PingedTestRunner`
 * grants `POST_NOTIFICATIONS`, so the tap takes the row's direct route to the
 * settings page rather than the permission dialog.
 *
 * The settings page is caught by a blocking monitor and never opens. A global
 * BACK to leave it once landed after this test ended and destroyed the next
 * test's activity on CI.
 */
class AlertSettingsChannelTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = instrumentation.targetContext.getSystemService(NotificationManager::class.java)

    @Test fun theRowDoesNotOpenSettingsBeforeTheChannelExists() {
        val settingsPage = instrumentation.addMonitor(
            IntentFilter(Settings.ACTION_APP_NOTIFICATION_SETTINGS),
            null,
            true,
        )
        try {
            manager.deleteNotificationChannel(SourcePrompt.CHANNEL_ID)

            compose.onNodeWithText("SETTINGS").performClick()
            compose.onNodeWithText(CAPTURE_ALERTS).performScrollTo().performClick()
            compose.waitForIdle()

            // The tap reached the intent: without this a click that went
            // nowhere would pass on a channel some other path created.
            assertEquals(1, settingsPage.hits)
            assertNotNull(
                "The settings page was opened with no channel on it",
                manager.getNotificationChannel(SourcePrompt.CHANNEL_ID),
            )
        } finally {
            instrumentation.removeMonitor(settingsPage)
        }
    }
}
