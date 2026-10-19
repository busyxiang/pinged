package my.pinged

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.capture.ListenerStatus
import my.pinged.capture.PingedComponents
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.transfer.TransferStore
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.ui.test.boundedWaitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spending's backup reminder (#252) as a user meets it: where it shows and
 * where it does not, its action reaching what it names, and its X and an
 * export each putting it away. Its wording and its controls' callbacks are
 * `:feature:ledger`'s `BackupReminderTest`, under Robolectric; this is the
 * half that needs the Activity, the stores and the tabs.
 *
 * **The device has to be talked into the one state where the reminder is
 * visible at all**, because it never shows beside a capture banner:
 * notification access granted and a heartbeat fresh enough that capture does
 * not look dead. The grant is `RebindTest`'s shell call; the heartbeat comes
 * from a notification posted by the shell, because `CaptureHealth` is
 * `internal` to `:feature:capture` and `CaptureIngest` skips this app's own
 * package (spec 10.2), so a notification this APK posts would leave the
 * heartbeat untouched.
 *
 * `createEmptyComposeRule`, not `createAndroidComposeRule`: that rule launches
 * the Activity before `@Before` runs, and every one of those inputs has to be
 * in place before `onResume` reads them.
 */
@RunWith(AndroidJUnit4::class)
class BackupReminderTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    /**
     * Export goes to settings with the export sheet open on the backup card:
     * the sheet stays hosted in settings, so Back from it lands there.
     */
    @Test fun exportReachesSettingsWithTheBackupSheetOpen() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp()

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTheReminder()

            compose.onNodeWithText(EXPORT).performClick()

            assertTrue(
                "\"$EXPORT\" did not open settings' export sheet on the backup " +
                    "card. A reminder whose action stops short of the export " +
                    "leaves the user to find it",
                compose.waitFor {
                    compose.countOf(SETTINGS_ONLY) == 1 &&
                        compose.onAllNodes(hasText(EVERYTHING) and isSelected()).fetchSemanticsNodes().size == 1
                },
            )
        }
    }

    /**
     * An item of the list and not a header: it scrolls away with the rows,
     * and back when the list is at its top again.
     */
    @Test fun theReminderScrollsAwayWithTheList() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp(rows = SCROLLING_ROWS)

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTheReminder()

            val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            list.performScrollToIndex(SCROLLING_ROWS)

            assertTrue(
                "The list scrolled ${SCROLLING_ROWS} items down and the reminder " +
                    "stayed, so it is pinned above the list rather than in it",
                compose.waitFor { compose.countOf(NUDGE, substring = true) == 0 },
            )

            list.performScrollToIndex(0)

            assertTrue(
                "The list came back to its top without the reminder",
                compose.waitFor { compose.countOf(NUDGE, substring = true) == 1 },
            )
        }
    }

    /** Spending's alone: not on the other two tabs, which the strip spans. */
    @Test fun theReminderIsAbsentFromChartsAndSettings() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp()

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTheReminder()

            compose.onNodeWithText("CHARTS").performClick()
            assertTrue(
                "The reminder was drawn over Charts",
                compose.waitFor {
                    compose.onAllNodes(hasText("CHARTS") and isSelected()).fetchSemanticsNodes().size == 1 &&
                        compose.countOf(NUDGE, substring = true) == 0
                },
            )

            compose.onNodeWithText("SETTINGS").performClick()
            assertTrue(
                "The reminder was drawn over Settings",
                compose.waitFor {
                    compose.countOf(SETTINGS_ONLY) == 1 && compose.countOf(NUDGE, substring = true) == 0
                },
            )
        }
    }

    /**
     * The export happens in settings and nothing about that is a resume -- so
     * this is the assertion that the reminder is not still up afterwards,
     * telling a user who has just saved everything that nothing is saved.
     *
     * `recordExport` directly rather than driving the export sheet: the sheet
     * hands off to the system's save-file picker, which no test can answer.
     * What is written is what `Transfers.export` writes, at the
     * moment it writes it, and it is the only thing the reminder reads.
     */
    @Test fun theReminderGoesWhenAnExportIsRecordedWhileItIsOnScreen() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp()

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTheReminder()

            runBlocking { TransferStore.recordExport(context, System.currentTimeMillis()) }

            assertTrue(
                "The export was recorded and the reminder stayed up. Nothing " +
                    "resumes this Activity between the tap and the export " +
                    "finishing, so a flag read only in `onResume` leaves " +
                    "\"NO BACKUP\" on screen for the rest of the session",
                compose.waitFor { compose.countOf(NUDGE, substring = true) == 0 },
            )
        }
    }

    /**
     * The X hides the reminder now and on the next launch, which is the
     * difference between a stored snooze and a flag in this Activity.
     * `ExportNudgeTest` holds when it comes back.
     */
    @Test fun theReminderStaysSnoozedAcrossALaunch() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp()

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTheReminder()

            compose.onNodeWithContentDescription(REMIND_ME_IN_A_WEEK).performClick()

            assertTrue(
                "\"$REMIND_ME_IN_A_WEEK\" was tapped and the reminder stayed up",
                compose.waitFor { compose.countOf(NUDGE, substring = true) == 0 },
            )
        }
        // A miss here is the reminder coming back once `onResume` has read
        // the store, so the absence is asserted after that read and not
        // before. The hook runs once `sampleHealth` has set the flag the
        // reminder is drawn from, which ends the wait at the read rather than
        // at TIMEOUT: a negative waitFor ran the full 30s on every pass.
        val sampled = CountDownLatch(1)
        betweenProbeAndReport = { sampled.countDown() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(
                    "The second launch never sampled the backup store, so " +
                        "nothing below is about what it read",
                    sampled.await(TIMEOUT, TimeUnit.MILLISECONDS),
                )
                assertEquals(
                    "The snoozed reminder came back on the next launch, so the " +
                        "snooze was never stored",
                    0,
                    compose.countOf(NUDGE, substring = true),
                )
            }
        } finally {
            betweenProbeAndReport = {}
        }
    }

    /**
     * The ledger goes out from under the reminder with nothing written to the
     * transfer store, which is what a delete leaves when its Keystore step
     * throws after the files are unlinked, or a restore whose import does not
     * finish: neither reaches `TransferStore.forget`. The reminder weighs the
     * ledger's row count as well as the timestamp, so it has to hear about
     * the database being replaced as well as about an export.
     */
    @Test fun theReminderGoesWhenTheLedgerIsReplacedWhileItIsOnScreen() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp()

        ActivityScenario.launch(MainActivity::class.java).use {
            awaitTheReminder()

            Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }

            assertTrue(
                "The ledger was replaced by an empty one and the reminder " +
                    "stayed up, telling the user there is something to lose on " +
                    "a phone that holds nothing. Nothing resumed this Activity " +
                    "and nothing wrote the transfer store",
                compose.waitFor { compose.countOf(NUDGE, substring = true) == 0 },
            )
        }
    }

    /**
     * **On a damaged ledger the action is the rescue**, which is what
     * settings offers there: design 6 withdraws Export everything, so the
     * action leads to the recovery notice and opens no export sheet. The
     * damage is the recorded verdict alone, which is what settings reads to
     * say DAMAGED, recorded as a check made now: with no check on record, the
     * weekly one that stage two runs on resume is due, reads the healthy file
     * and clears it.
     */
    @Test fun onADamagedLedgerTheReminderLeadsToTheRescue() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp()
        runBlocking { IntegrityStore.record(context, at = System.currentTimeMillis(), ok = false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitTheReminder()
                assertTrue(
                    "On a damaged ledger the reminder's action did not read \"Rescue\"",
                    compose.waitFor { compose.countOf(REMINDER_RESCUE) == 1 },
                )
                assertEquals(
                    "On a damaged ledger the reminder still offered \"$EXPORT\", which settings withdraws there",
                    0,
                    compose.countOf(EXPORT),
                )

                compose.onNodeWithText(REMINDER_RESCUE).performClick()

                assertTrue(
                    "\"$REMINDER_RESCUE\" did not reach settings' recovery notice",
                    compose.waitFor { compose.countOf(SETTINGS_ONLY) == 1 && compose.countOf(RESCUE) == 1 },
                )
                assertEquals(
                    "The rescue opened the export sheet, which a damaged ledger cannot finish",
                    0,
                    compose.countOf(EVERYTHING),
                )
            }
        } finally {
            runBlocking { IntegrityStore.forget(context) }
        }
    }

    /**
     * On screen, not only composed: the reminder is handed to Spending once
     * `onResume`'s sample has read the stores, after the list is drawn, and a
     * `LazyColumn` keeps its first visible item across a change -- so an item
     * added above the top row could be composed for prefetch and sit above
     * the viewport, unseen by a user at the top of the list.
     */
    private fun awaitTheReminder() = assertTrue(
        "The backup reminder was never on screen over a ledger with rows and " +
            "an export a month old. Capture was made healthy first, so no " +
            "capture banner stands in its way: " + report(),
        compose.waitFor {
            compose.countOf(NUDGE, substring = true) == 1 &&
                compose.onNodeWithText(NUDGE, substring = true).isDisplayed()
        },
    )

    /** Granted, and a heartbeat fresh enough that capture does not look dead. */
    private fun captureLooksHealthy() {
        val component = PingedComponents.listener(context).flattenToString()
        shell("cmd notification allow_listener $component")
        assertTrue(
            "The notification-access grant did not take, so the strip shows " +
                "\"CAPTURE OFF\" and the reminder can never be reached. The " +
                // Not `cmd notification allowed_listeners`: that is not a
                // subcommand -- `cmd notification` with no arguments lists
                // `allow_listener` and `disallow_listener` and no reader of
                // any kind -- so it prints "Unknown command" where the
                // grant should be. The secure setting is the list
                // the grant is actually stored in.
                "grants on this device are: " +
                shell("settings get secure enabled_notification_listeners"),
            waitFor { ListenerStatus.isGranted(context) },
        )
        // Posted by the shell, so it arrives from a package that is not this
        // one. Whether it lands through `onNotificationPosted` or through the
        // catch-up on `onListenerConnected` does not matter: both record the
        // heartbeat.
        shell("cmd notification post -t Pinged $HEARTBEAT_TAG heartbeat")
        assertTrue(
            "No notification reached the listener, so the heartbeat is " +
                "missing or a day old and the strip shows a capture banner " +
                "in place of the reminder: " + report(),
            waitFor {
                val report = runBlocking { ListenerStatus.report(context) }
                report.granted && report.lastNotificationAt != 0L && !report.looksDead
            },
        )
    }

    /**
     * Something to lose, and no copy of it anywhere.
     *
     * The rows are inserted into the app's own database rather than a fresh
     * one: `MainActivity` opens the shared handle, and a count taken against
     * any other file would be a count of a database the reminder never reads.
     * Today's, a minute apart, so all [rows] are in the month Spending opens on.
     */
    private fun aLedgerWithNothingBackedUp(rows: Int = 1) {
        val now = System.currentTimeMillis()
        for (i in 0 until rows) Databases.txnDao(context).insert(
            Txn(
                // Captured, as its source says: a null id is a manual entry (spec 4).
                // The id names no capture row; the column has no foreign key.
                // The clock, because the file outlives each test here and the
                // column is unique.
                rawCaptureId = now * 1_000 + i,
                amountSen = 1_250L,
                direction = Direction.EXPENSE,
                occurredAt = now - i * 60_000L,
                merchantRaw = "WARUNG PAK ALI",
                merchantDisplay = "Warung Pak Ali",
                merchantKey = "WARUNG PAK ALI",
                categoryId = 1L,
                sourcePackage = "my.com.tngdigital.ewallet",
                sourceLabel = "Touch 'n Go eWallet",
                confidence = Confidence.HIGH,
                state = TxnState.COMMITTED,
                createdAt = now,
                updatedAt = now,
            ),
        )
        runBlocking {
            // Clears a snooze an earlier test left, which would hide the reminder.
            TransferStore.forget(context)
            TransferStore.recordExport(context, now - EXPORT_STALE_AFTER_MILLIS - 1)
        }
    }

    /** What capture says about itself, for a failure message. */
    private fun report() = runBlocking { ListenerStatus.report(context) }.toString()

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
     * [boundedWaitUntil] wrapped so a failure is the assertion above
     * it and its message, rather than a bare timeout from inside the rule.
     */
    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { boundedWaitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String, substring: Boolean = false): Int {
        waitForIdle()
        return onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /** The reminder's line's first half. */
        const val NUDGE = "NO BACKUP"

        const val EXPORT = "Export"

        /** The reminder's action on a damaged ledger. */
        const val REMINDER_RESCUE = "Rescue"

        /** Settings' recovery notice's action. */
        const val RESCUE = "Rescue what can still be read"

        /** The reminder's X, by what a screen reader calls it. */
        const val REMIND_ME_IN_A_WEEK = "Remind me in a week"

        /** The export sheet's backup card, chosen when it opens on JSON. */
        const val EVERYTHING = "Everything"

        /**
         * Settings, distinguished by a row only it draws -- the ledger behind
         * the banner draws a "SETTINGS" control of its own, so arriving cannot
         * be asserted on the destination's name.
         */
        const val SETTINGS_ONLY = "Delete everything"

        /**
         * At `LedgerScreen`'s 57dp a row, about 2,300dp of list: more than three
         * of CI's 640dp screens, so the list has somewhere to scroll.
         */
        const val SCROLLING_ROWS = 40

        const val HEARTBEAT_TAG = "pinged-nudge-heartbeat"
    }
}
