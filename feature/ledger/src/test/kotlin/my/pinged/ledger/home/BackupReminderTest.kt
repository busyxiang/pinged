package my.pinged.ledger.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spending's backup reminder (#252) as `design/Backup.dc.html` words it: its
 * line, its one action and its X, each wired to its own callback.
 *
 * Where the list puts it is `BackupReminderListTest`, on a device: the feed
 * is paged, and a page handed to `collectAsLazyPagingItems` here never lands
 * once `ManualEntryScreenTest` or its siblings have run in the same JVM.
 * That it scrolls away and where each callback leads need the Activity, so
 * they are `:app`'s `BackupReminderTest`; the X's 48dp is measured, so it is
 * `TouchTargetTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class BackupReminderTest {
    @get:Rule val compose = createComposeRule()

    private var actions = 0
    private var dismissals = 0

    private fun reminder(damaged: Boolean = false) =
        BackupReminder(damaged = damaged, onAction = { actions++ }, onDismiss = { dismissals++ })

    @Test fun itSaysWhatIsAtRiskInOneLine() {
        compose.setContent { PingedTheme { BackupReminderRow(reminder()) } }

        compose.onNodeWithText("NO BACKUP · ONLY ON THIS PHONE").assertExists()
    }

    @Test fun exportIsItsAction() {
        compose.setContent { PingedTheme { BackupReminderRow(reminder()) } }

        compose.onNodeWithText(REMINDER_EXPORT).performClick()

        assertEquals("\"Export\" did not ask for the export", 1, actions)
        assertEquals("\"Export\" snoozed the reminder", 0, dismissals)
    }

    /**
     * Settings withdraws Export everything on a damaged ledger (design 6), so
     * an action promising an export would lead to a screen without one.
     */
    @Test fun onADamagedLedgerTheActionIsTheRescue() {
        compose.setContent { PingedTheme { BackupReminderRow(reminder(damaged = true)) } }

        assertEquals(
            "On a damaged ledger the reminder still offered an export",
            0,
            compose.onAllNodesWithText(REMINDER_EXPORT).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText(REMINDER_RESCUE).performClick()

        assertEquals("\"Rescue\" did not ask for the rescue", 1, actions)
    }

    @Test fun theXRemindsInAWeek() {
        compose.setContent { PingedTheme { BackupReminderRow(reminder()) } }

        compose.onNodeWithContentDescription(REMIND_ME_IN_A_WEEK).performClick()

        assertEquals("The X did not snooze the reminder", 1, dismissals)
        assertEquals("The X asked for the export", 0, actions)
    }
}
