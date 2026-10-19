package my.pinged.ledger

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.dao.LearnedMerchant
import my.pinged.ledger.learned.DELETE
import my.pinged.ledger.learned.LearnedContent
import my.pinged.ledger.learned.LearnedState
import my.pinged.ledger.settings.ExportSheet
import my.pinged.ledger.settings.Footer
import my.pinged.ledger.settings.RecoveryNotice
import my.pinged.ledger.settings.SettingsScreen
import my.pinged.ledger.settings.SettingsState
import my.pinged.ledger.settings.SettingsViewModel
import my.pinged.ledger.settings.Storage
import my.pinged.ledger.settings.Transfers
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.RESCUE
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #165: the design system's "Every touch target is at least 44px tall",
 * asserted on each control that once fell short of it. The height asserted is
 * the clickable node's own bounds, which is what Compose hit-tests: a tap just
 * outside a short control lands on whatever is behind it, and on a ledger row
 * that is the row's own tap.
 *
 * Not a ledger row's controls: those are laid out at their drawn size and
 * take Compose's minimum touch target instead, which no layout height shows;
 * `RowTouchTargetTest` taps around them (#186).
 */
@RunWith(AndroidJUnit4::class)
class TouchTargetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private fun assertTall(vararg texts: String) {
        val short = texts.mapNotNull { text ->
            val node = compose.onNodeWithText(text).performScrollTo()
            val height = with(compose.density) { node.fetchSemanticsNode().size.height.toDp() }
            "$text is $height".takeIf { height < 44.dp }
        }
        assertTrue("under 44dp: $short", short.isEmpty())
        texts.forEach { compose.onNodeWithText(it).assertHeightIsAtLeast(44.dp) }
    }

    /** A scroll parent, which `performScrollTo` needs, for the composables that are not a screen. */
    private fun scrolled(content: @Composable () -> Unit) = compose.setContent {
        PingedTheme { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
    }

    // ---- learned merchants ---------------------------------------------------

    @Test fun learnedBackAndDelete() {
        compose.setContent {
            PingedTheme {
                LearnedContent(
                    LearnedState(loaded = true, items = listOf(LearnedMerchant("MR DIY", "Hardware shop", 4L, "Shopping", payments = 3))),
                    onBack = {},
                    onDelete = {},
                )
            }
        }
        assertTall("Back")
        // A drawn icon, so found by what a screen reader calls it.
        compose.onNodeWithContentDescription(DELETE).assertHeightIsAtLeast(44.dp).assertWidthIsAtLeast(44.dp)
    }

    // ---- settings ------------------------------------------------------------

    @Test fun exportSheetsAction() {
        scrolled { ExportSheet(SettingsState(loaded = true), onPick = {}, onCancel = {}, onDismiss = {}) }
        assertTall("Export")
    }

    /** #230: the licences are a link in the footer, and still a control. */
    @Test fun footersLicences() {
        scrolled { Footer(buildVersion = "1.0", packVersion = 7, onOpenLicences = {}) }
        assertTall("LICENCES")
    }

    @Test fun rescue() {
        scrolled { RecoveryNotice(SettingsState(storage = Storage.DAMAGED, loaded = true), onRescue = {}) }
        assertTall(RESCUE)
    }

    @Test fun restoreConfirmationsTwoAnswers() {
        cleanSettings()
        settings(SettingsViewModel(app))
        compose.onNodeWithText("Replace everything from a backup").performScrollTo().performClick()
        compose.waitForIdle()
        assertTall("Keep what's here", "Continue")
    }

    @Test fun outcomesOk() {
        cleanSettings()
        DatabaseFactory.build(app).close()
        runBlocking {
            SettingsViewModel(app).restoreFrom { ByteArrayInputStream("{\"not\":\"a backup\"}".toByteArray()) }.join()
        }
        try {
            settings(SettingsViewModel(app))
            assertTall("OK")
        } finally {
            cleanSettings()
        }
    }

    private fun cleanSettings() {
        Transfers.forgetOutcome()
        Databases.whileDeleting { app.deleteDatabase(DatabaseFactory.NAME) }
        runBlocking { IntegrityStore.forget(app) }
    }

    private fun settings(viewModel: SettingsViewModel) = compose.setContent {
        PingedTheme {
            SettingsScreen(
                viewModel = viewModel,
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {}, onOpenUnread = {},
                onExport = {}, onRestore = {}, onRescue = {},
                buildVersion = "0.0.0-test", packVersion = 1,
            )
        }
    }
}
