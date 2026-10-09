package my.pinged.ledger.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.entity.CaptureSource
import my.pinged.ledger.theme.PingedTheme
import my.pinged.ledger.transfer.useDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The wiring between `SettingsScreen`'s `Delete everything` row and the
 * holder that does the deleting**, which `WipeSheetTest` cannot see: it drives
 * `WipeSheetBody` directly, so `onDelete = { }` on the row, or an `onConfirm`
 * that never reached `deleteEverything`, would ship a dead control with that
 * whole suite green.
 *
 * Driven against a real [SettingsViewModel] over a real database rather than a
 * spy, because what the delete is supposed to do is destroy the file -- the
 * one enabled capture source seeded below is the thing whose disappearance
 * says it happened, and a recorded call would not.
 *
 * **It is also the only case in this repository that composes a sheet inside
 * its real chrome, and the first run of it found what nothing else could.**
 * With the IME up for the confirmation field, `ReceiptSheet`'s `Column` had no
 * height left for its last child, and Compose measured `Delete` at exactly 0px
 * tall: the control the sheet exists to arm, gone at the moment the user armed
 * it, silently. The `verticalScroll` in `ReceiptSheet` is that fix, and
 * [performScrollTo] below is what a user does on the phone -- the button is
 * real, and below the fold while the keyboard is up. Without the scroll in the
 * chrome there is no scrollable ancestor to find and this case fails.
 */
@RunWith(AndroidJUnit4::class)
class WipeHostTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun startClean() {
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
    }

    @After fun leaveAnOpenableDatabase() {
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
    }

    private fun screen(viewModel: SettingsViewModel, onExport: () -> Unit = {}, onRescue: () -> Unit = {}) {
        compose.setContent {
            PingedTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {},
                    onExport = onExport,
                    onRestore = {},
                    onRescue = onRescue,
                )
            }
        }
        compose.waitUntil(TIMEOUT) { viewModel.state.value.loaded }
    }

    private fun seedOneEnabledSource() {
        DatabaseFactory.build(app).useDb { db ->
            db.captureSourceDao().insertForImport(
                CaptureSource(
                    pkg = "my.com.tngdigital.ewallet",
                    label = "TNG",
                    enabled = true,
                    firstSeenAt = 1L,
                ),
            )
        }
        Databases.reset()
    }

    @Test fun theDeleteRowOpensTheSheetAndTheTypedWordDestroysTheLedger() {
        seedOneEnabledSource()
        val viewModel = SettingsViewModel(app)
        screen(viewModel)
        assertEquals(
            "The fixture did not reach the screen, so the wipe below would " +
                "have nothing to destroy",
            1,
            viewModel.state.value.sourcesOn,
        )

        compose.onNodeWithText("Delete everything").performScrollTo().performClick()
        compose.onNodeWithText("THIS CANNOT BE UNDONE").assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performTextInput("DELETE")
        compose.onNodeWithText("Delete").assertIsEnabled().performScrollTo().performClick()

        // `Wipe.everything` rotates the key rather than leaving none behind,
        // so what opens afterwards is a fresh, healthy, empty database and 0
        // is the true count rather than a read that failed.
        //
        // `Throwable`, not `Exception`: `ComposeTimeoutException` extends
        // Throwable directly, and a timeout that says only "condition not
        // satisfied" cannot tell a dead row from a wipe that failed.
        try {
            compose.waitUntil(TIMEOUT) { viewModel.state.value.sourcesOn == 0 }
        } catch (thrown: Throwable) {
            throw AssertionError(
                "The confirmed delete never reached the holder. State is " +
                    "still ${viewModel.state.value}",
                thrown,
            )
        }
    }

    /**
     * **A delete that destroyed the ledger and then failed says so on the
     * screen.**
     *
     * `SettingsStateTest.aDeleteThatThrowsAfterTheWipeReportsInsteadOfCrashing`
     * establishes that the holder files that failure instead of dying; a
     * `TransferJob.Failed` nobody draws is not much better than a crash, and
     * the screen chooses what to draw by the outcome's own operation -- see
     * `SettingsState.reported`.
     *
     * [HostileApp] is armed only after the screen has loaded, because Room
     * resolves the database file through the call it refuses.
     */
    @Test fun aDeleteThatCouldNotFinishSaysSoOnTheScreen() {
        val hostile = HostileApp(app)
        val viewModel = SettingsViewModel(hostile)
        screen(viewModel)
        hostile.refusing = true

        compose.onNodeWithText("Delete everything").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("DELETE")
        compose.onNodeWithText("Delete").performScrollTo().performClick()

        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText("YOUR LEDGER IS GONE").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The other half of ruling R23: the offer has to reach the export the
     * screen already owns, and close this sheet on the way so the two are not
     * fighting for the screen.
     */
    @Test fun exportingFirstClosesTheSheetAndHandsOffToTheExport() {
        val exports = AtomicInteger(0)
        val viewModel = SettingsViewModel(app)
        screen(viewModel, onExport = { exports.incrementAndGet() })

        compose.onNodeWithText("Delete everything").performScrollTo().performClick()
        compose.onNodeWithText("Export it first").performScrollTo().performClick()

        compose.waitUntil(TIMEOUT) { exports.get() == 1 }
        compose.onNodeWithText("THIS CANNOT BE UNDONE").assertDoesNotExist()
    }

    /**
     * **Where the database will not open, the delete sheet offers no copy
     * first** (design 6's table). An export there opens the picker, throws at
     * its first read and deletes the file it was handed: a guaranteed
     * failure two taps from the notice saying nothing here can be read. The
     * rescue reads through the same key and file, so it is not offered
     * either.
     */
    @Test fun aLedgerWhoseKeyIsGoneIsOfferedNoCopyBeforeTheDelete() {
        DatabaseFactory.build(app).close()
        Databases.reset()
        check(File(app.noBackupFilesDir, "db.key").delete()) { "the key file would not delete" }
        assertNoCopyOffered(Storage.KEY_GONE)
    }

    /** As [aLedgerWhoseKeyIsGoneIsOfferedNoCopyBeforeTheDelete], over a file that will not open through its key. */
    @Test fun anUnreadableLedgerIsOfferedNoCopyBeforeTheDelete() {
        DatabaseFactory.build(app).close()
        Databases.reset()
        app.getDatabasePath(DatabaseFactory.NAME).writeBytes(ByteArray(8_192) { 0x5A })
        assertNoCopyOffered(Storage.UNREADABLE)
    }

    private fun assertNoCopyOffered(storage: Storage) {
        val offers = AtomicInteger(0)
        val viewModel = SettingsViewModel(app)
        screen(viewModel, onExport = { offers.incrementAndGet() }, onRescue = { offers.incrementAndGet() })
        compose.waitUntil(TIMEOUT) { viewModel.state.value.storage == storage }

        compose.onNodeWithText("Delete everything").performScrollTo().performClick()
        compose.onNodeWithText("THIS CANNOT BE UNDONE").assertIsDisplayed()
        compose.onNodeWithTag(EXPORT_FIRST_TAG).assertDoesNotExist()
        compose.onNodeWithText(EXPORT_FIRST).assertDoesNotExist()
        compose.onNodeWithText(RESCUE_FIRST).assertDoesNotExist()
        assertEquals("a copy was offered where none can be made", 0, offers.get())
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
