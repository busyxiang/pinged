package my.pinged.ledger

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.LearnedMerchant
import my.pinged.data.entity.Category
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.home.CATEGORY_CHIP
import my.pinged.ledger.home.LedgerRow
import my.pinged.ledger.home.awaitingReviewBadge
import my.pinged.ledger.learned.DELETE
import my.pinged.ledger.learned.LearnedContent
import my.pinged.ledger.learned.LearnedState
import my.pinged.ledger.settings.ExportSheet
import my.pinged.ledger.settings.RecoveryNotice
import my.pinged.ledger.settings.SettingsScreen
import my.pinged.ledger.settings.SettingsState
import my.pinged.ledger.settings.SettingsViewModel
import my.pinged.ledger.settings.Storage
import my.pinged.ledger.settings.Transfers
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.RESCUE
import my.pinged.ui.theme.Separator
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
        assertTall("Back", DELETE)
    }

    // ---- settings ------------------------------------------------------------

    @Test fun exportSheetsAction() {
        scrolled { ExportSheet(SettingsState(loaded = true), onPick = {}, onCancel = {}, onDismiss = {}) }
        assertTall("Export")
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenLicences = {},
                onExport = {}, onRestore = {}, onRescue = {},
                buildVersion = "0.0.0-test", packVersion = 1,
            )
        }
    }

    // ---- a ledger row --------------------------------------------------------

    private val food = Category(id = 1L, name = "Food", iconKey = "food", sortOrder = 1)
    private val uncategorized = Category(id = 2L, name = "Uncategorized", iconKey = "uncategorized", sortOrder = 2)

    /**
     * 600dp wide whatever the screen: a badge or line that wraps to two lines
     * is taller than 44dp for that reason alone, and would pass unfixed. The
     * longest `DUPLICATE_SUSPECT` badge wraps at a 1080px phone's width.
     */
    private fun row(categoryId: Long, state: TxnState = TxnState.COMMITTED, pendingReason: PendingReason? = null) =
        scrolled {
            Box(Modifier.requiredWidth(600.dp)) {
                LedgerRow(
                    row = FeedRow(ledgerTxn(1_250L, categoryId = categoryId, state = state, pendingReason = pendingReason, id = 7L), "WARUNG PAK ALI", MERCHANT),
                    categoriesById = mapOf(food.id to food, uncategorized.id to uncategorized),
                    uncategorizedId = uncategorized.id,
                    canCategorize = true,
                    onCategorize = {}, onOpenMerchant = {}, onOpenReview = {},
                )
            }
        }

    @Test fun categoryChip() {
        row(categoryId = uncategorized.id)
        assertTall(CATEGORY_CHIP)
    }

    @Test fun categoryLine() {
        row(categoryId = food.id)
        assertTall("Food" + Separator + SOURCE_LABEL)
    }

    @Test fun reviewBadge() {
        row(categoryId = food.id, state = TxnState.PENDING, pendingReason = PendingReason.RULE_REVIEW)
        assertTall(awaitingReviewBadge(PendingReason.RULE_REVIEW.name))
    }
}
