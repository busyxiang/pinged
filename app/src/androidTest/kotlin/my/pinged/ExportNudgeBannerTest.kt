package my.pinged

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The nudge as a user meets it: its own wording, its own button, and the button
 * settling the thing the wording complains about.
 *
 * `BannerPriorityTest` pins which kind is chosen and nothing pins the drawing,
 * so a `NUDGE_EXPORT` arm carrying the "CAPTURE STOPPED" body, or wired to
 * `openListenerSettings`, would ship green. Both halves of that pairing are
 * asserted here.
 *
 * **The device has to be talked into the one state where the nudge is visible
 * at all**, because it is last in the strip: notification access granted and a
 * heartbeat fresh enough that capture does not look dead. The grant is
 * `RebindTest`'s shell call; the heartbeat comes from a notification posted by
 * the shell, because `CaptureHealth` is `internal` to `:feature:capture` and
 * `CaptureIngest` skips this app's own package (spec 10.2), so a notification
 * this APK posts would leave the heartbeat untouched.
 *
 * `createEmptyComposeRule`, not `createAndroidComposeRule`: that rule launches
 * the Activity before `@Before` runs, and every one of those inputs has to be
 * in place before `onResume` reads them.
 */
@RunWith(AndroidJUnit4::class)
class ExportNudgeBannerTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test fun theNudgeSaysWhatIsAtRiskAndItsButtonReachesTheScreenThatExports() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp()

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "The backup nudge never appeared over a ledger with rows and " +
                    "an export a month old. Capture was made healthy first, so " +
                    "no other banner outranks it: " + report(),
                compose.waitFor { compose.countOf(NUDGE, substring = true) == 1 },
            )
            assertTrue(
                "The nudge drew a body belonging to another banner. This is " +
                    "the arm that says why an export matters on a phone with " +
                    "no internet permission, and the wording is the whole of " +
                    "what it does",
                compose.countOf(NUDGE_BODY, substring = true) == 1,
            )

            compose.onNodeWithText(NUDGE_ACTION).performClick()

            assertTrue(
                "\"$NUDGE_ACTION\" did not reach settings, which is where the " +
                    "export sheet is hosted. A nudge whose only button goes " +
                    "nowhere -- or to the notification-access screen, which is " +
                    "what every other banner's button does -- leaves the user " +
                    "no way to do the thing it asks for",
                compose.waitFor { compose.countOf(SETTINGS_ONLY) == 1 },
            )
        }
    }

    /**
     * The banner's own button leads to settings, the export happens there, and
     * nothing about that is a resume -- so this is the assertion that the
     * nudge is not still up afterwards, telling a user who has just saved
     * everything that nothing is saved anywhere.
     *
     * `recordExport` directly rather than driving the export sheet: the sheet
     * hands off to the system's save-file picker, which no test can answer.
     * What is written is what `Transfers.export` writes, at the
     * moment it writes it, and it is the only thing the banner reads.
     */
    @Test fun theNudgeGoesWhenAnExportIsRecordedWhileItIsOnScreen() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp()

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "The backup nudge never appeared, so nothing below this line " +
                    "is about it going away: " + report(),
                compose.waitFor { compose.countOf(NUDGE, substring = true) == 1 },
            )

            runBlocking { TransferStore.recordExport(context, System.currentTimeMillis()) }

            assertTrue(
                "The export was recorded and the nudge stayed up. Nothing " +
                    "resumes this Activity between tapping the banner and the " +
                    "export finishing, so a flag read only in `onResume` " +
                    "leaves \"NO BACKUP\" on screen for the rest of the session",
                compose.waitFor { compose.countOf(NUDGE, substring = true) == 0 },
            )
        }
    }

    /**
     * The ledger goes out from under the nudge with nothing written to the
     * transfer store, which is what a delete leaves when its Keystore step
     * throws after the files are unlinked, or a restore whose import does not
     * finish: neither reaches `TransferStore.forget`. The nudge weighs the
     * ledger's row count as well as the timestamp, so it has to hear about
     * the database being replaced as well as about an export.
     */
    @Test fun theNudgeGoesWhenTheLedgerIsReplacedWhileItIsOnScreen() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp()

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "The backup nudge never appeared, so nothing below this line " +
                    "is about it going away: " + report(),
                compose.waitFor { compose.countOf(NUDGE, substring = true) == 1 },
            )

            Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }

            assertTrue(
                "The ledger was replaced by an empty one and the nudge stayed " +
                    "up, telling the user there is something to lose on a " +
                    "phone that holds nothing. Nothing resumed this Activity " +
                    "and nothing wrote the transfer store",
                compose.waitFor { compose.countOf(NUDGE, substring = true) == 0 },
            )
        }
    }

    /**
     * **On a damaged ledger the button names the rescue**, which is what
     * settings offers there: design 6 withdraws Export everything, and a
     * button promising it would lead to a screen without it. The damage is the
     * recorded verdict alone, which is what settings reads to say DAMAGED,
     * recorded as a check made now: with no check on record, the weekly one
     * that stage two runs on resume is due, reads the healthy file and
     * clears it.
     */
    @Test fun onADamagedLedgerTheNudgeOffersTheRescueAndItsButtonReachesIt() {
        captureLooksHealthy()
        aLedgerWithNothingBackedUp()
        runBlocking { IntegrityStore.record(context, at = System.currentTimeMillis(), ok = false) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(
                    "The backup nudge never appeared over a damaged ledger with rows and no recent copy: " + report(),
                    compose.waitFor { compose.countOf(NUDGE, substring = true) == 1 },
                )
                assertTrue(
                    "On a damaged ledger the nudge's button did not name the rescue",
                    compose.waitFor { compose.countOf(RESCUE) == 1 },
                )
                assertTrue(
                    "On a damaged ledger the nudge still offered \"$NUDGE_ACTION\", which settings withdraws there",
                    compose.countOf(NUDGE_ACTION) == 0,
                )

                compose.onNodeWithText(RESCUE).performClick()

                assertTrue(
                    "\"$RESCUE\" did not reach a settings screen offering the rescue",
                    compose.waitFor { compose.countOf(SETTINGS_ONLY) == 1 && compose.countOf(RESCUE) == 1 },
                )
            }
        } finally {
            runBlocking { IntegrityStore.forget(context) }
        }
    }

    /** Granted, and a heartbeat fresh enough that capture does not look dead. */
    private fun captureLooksHealthy() {
        val component = PingedComponents.listener(context).flattenToString()
        shell("cmd notification allow_listener $component")
        assertTrue(
            "The notification-access grant did not take, so the strip shows " +
                "\"CAPTURE OFF\" and the nudge can never be reached. The " +
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
                "rather than the nudge: " + report(),
            waitFor {
                val report = runBlocking { ListenerStatus.report(context) }
                report.granted && report.lastNotificationAt != 0L && !report.looksDead
            },
        )
    }

    /**
     * Something to lose, and no copy of it anywhere.
     *
     * The row is inserted into the app's own database rather than a fresh one:
     * `MainActivity` opens the shared handle, and a count taken against any
     * other file would be a count of a database the banner never reads.
     */
    private fun aLedgerWithNothingBackedUp() {
        val now = System.currentTimeMillis()
        Databases.txnDao(context).insert(
            Txn(
                rawCaptureId = null,
                amountSen = 1_250L,
                direction = Direction.EXPENSE,
                occurredAt = now,
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
     * [ComposeTestRule.waitUntil] wrapped so a failure is the assertion above
     * it and its message, rather than a bare timeout from inside the rule.
     */
    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String, substring: Boolean = false): Int {
        waitForIdle()
        return onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /** The label's first half; the separator between the two is drawn. */
        const val NUDGE = "NO BACKUP"

        /** A phrase no other banner draws. */
        const val NUDGE_BODY = "this phone holds the only copy"

        const val NUDGE_ACTION = "Export everything"

        /** The nudge's button on a damaged ledger, and settings' recovery notice's. */
        const val RESCUE = "Rescue what can still be read"

        /**
         * Settings, distinguished by a row only it draws -- the ledger behind
         * the banner draws a "SETTINGS" control of its own, so arriving cannot
         * be asserted on the destination's name.
         */
        const val SETTINGS_ONLY = "Delete everything"

        const val HEARTBEAT_TAG = "pinged-nudge-heartbeat"
    }
}
