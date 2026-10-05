package my.pinged.ledger.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.ledger.theme.PingedTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `SettingsScreen` reports a restore's result by the [Outcome]'s own
 * operation, because one shared `TransferJob` field cannot say which action
 * a failure belongs to. The case that tells: a restore confirmed, then
 * abandoned at the picker before a file is chosen, and then an unrelated
 * failure on the same holder -- an `exportTo` here -- which must not be
 * drawn as that restore's result.
 */
@RunWith(AndroidJUnit4::class)
class RestoreCancellationTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun startClean() {
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun cancellingThePickerDoesNotMislabelALaterUnrelatedFailure() {
        val viewModel = SettingsViewModel(app)
        compose.setContent {
            PingedTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onOpenSources = {}, onOpenCorrections = {},
                    onExport = {},
                    // Stands in for `MainActivity`'s launcher receiving a null
                    // `Uri` -- the user backed out of the system picker before
                    // choosing anything -- which never calls `settings.restoreFrom`.
                    onRestore = {},
                    onRescue = {},
                )
            }
        }
        compose.waitUntil(TIMEOUT) { viewModel.state.value.loaded }

        compose.onNodeWithText("Replace everything from a backup").performClick()
        compose.onNodeWithText("Continue").performClick()

        // A failure on the same holder, from an entirely different action,
        // landing on the one `TransferJob` field the two share.
        runBlocking { viewModel.exportTo(FailingSink()).join() }
        compose.waitUntil(TIMEOUT) { viewModel.state.value.job is TransferJob.Failed }

        compose.onNodeWithText("RESTORE DID NOT FINISH").assertDoesNotExist()
        compose.onNodeWithText("YOUR LEDGER IS GONE").assertDoesNotExist()
    }

    /** Fails every write; only [ExportSink.open] matters here. */
    private class FailingSink : ExportSink {
        override fun open(): OutputStream = throw IOException("simulated failure, for the test")
        override fun delete(): Boolean = true
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
