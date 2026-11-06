package my.pinged.ledger

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.RuleOrigin
import my.pinged.ledger.home.CATEGORY_CHIP
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ledger.home.fixLine
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger

/**
 * #48: the chooser's "Always call this" switch and what a save does with it,
 * through the real view model against the real encrypted database, because
 * what they assert is the rule and the row's `user_edited` that land there.
 * The switch's starting position per row state, and what a save reports, are
 * facts a fixture can state directly: `RowHostTest` draws them on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class ChooserTeachesTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        scope.cancel()
        context.discardTheDatabase()
    }

    // ---- the switch, through the real read ----------------------------

    /**
     * A row the dictionary filed and the user then taught its own category
     * reads as the user's rule: the switch is on, which holds only if the read
     * after the save carried the new rule into the chooser (the dictionary
     * alone would say off).
     */
    @Test fun teachingTheDictionarysOwnCategoryMakesTheRowTheUsersRule() {
        val dao = context.freshLedger()
        // Dated years before the month the summary reads, so "Transport" is
        // drawn once -- by the sheet -- and not also as the summary's top line.
        dao.insert(
            ledgerTxn(amountSen = 5_000L, occurredAt = LONG_AGO, categoryId = categoryId("Transport"))
                .copy(merchantRaw = "MY50 Pass", merchantDisplay = "My50 Pass", merchantKey = "MY50 PASS"),
        )
        screenFor()
        compose.waitForText("Transport · $SOURCE_LABEL")
        compose.onNodeWithText("Transport · $SOURCE_LABEL").performClick()
        compose.waitForSheet()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNode(isToggleable()).performClick()
        save()
        compose.waitForText("Transport · $SOURCE_LABEL")
        compose.boundedWaitUntil(TIMEOUT) { Databases.merchantRuleDao(context).countAll() == 1 }

        compose.onNodeWithText("Transport · $SOURCE_LABEL").performClick()
        compose.waitForSheet()

        compose.onNode(isToggleable()).assertIsOn()
    }

    /** No merchant key: the holder saves the one-off the sheet promised, even if asked to teach. */
    @Test fun aKeylessRowIsSavedAsAOneOffEvenWhenAskedToTeach() {
        val dao = context.freshLedger()
        val id = dao.insert(
            ledgerTxn(amountSen = 5_000L, occurredAt = now(), categoryId = categoryId(UNCATEGORIZED))
                .copy(merchantRaw = null, merchantDisplay = null, merchantKey = null),
        )
        val rows = context.rowActions(scope)

        runBlocking { withTimeout(TIMEOUT) { rows.assignCategory(id, categoryId(FOOD), teach = true).join() } }

        val row = requireNotNull(Databases.txnDao(context).byId(id))
        assertEquals(categoryId(FOOD), row.categoryId)
        assertTrue("A keyless save was not a one-off", row.userEdited)
        assertEquals(0, Databases.merchantRuleDao(context).countAll())
    }

    // ---- the saves, through the real view model --------------------------

    @Test fun aTeachingSaveWritesTheRuleAndLeavesTheRowUnedited() {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = now(), categoryId = categoryId(UNCATEGORIZED)))

        screenFor()
        compose.waitForText(CATEGORY_CHIP)
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText(FOOD).performClick()
        save()
        compose.waitForText("$FOOD · $SOURCE_LABEL")

        val rule = Databases.merchantRuleDao(context).pageFrom(0L, 10).single()
        assertEquals("WARUNG PAK ALI", rule.pattern)
        assertEquals(MatchType.EXACT, rule.matchType)
        assertEquals(RuleOrigin.LEARNED, rule.origin)
        assertEquals(categoryId(FOOD), rule.categoryId)
        assertFalse(
            "A teaching save left the row a one-off",
            Databases.txnDao(context).recent(1).single().userEdited,
        )
    }

    @Test fun aSaveWithTheSwitchOffIsAOneOffAndWritesNoRule() {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = now(), categoryId = categoryId(UNCATEGORIZED)))

        screenFor()
        compose.waitForText(CATEGORY_CHIP)
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(FOOD).performClick()
        save()
        compose.waitForText("$FOOD · $SOURCE_LABEL")

        assertEquals("A one-off wrote a rule", 0, Databases.merchantRuleDao(context).countAll())
        assertTrue(
            "A one-off left the row unedited, so a re-parse could undo it",
            Databases.txnDao(context).recent(1).single().userEdited,
        )
    }

    /**
     * The re-tap: the row the rule filed is opened by its category line, the
     * switch starts on because the learned rule filed it (which only holds if
     * the refresh after the first save carried the rule into the chooser), and
     * a different category updates the one rule.
     */
    @Test fun aRetapUpdatesTheRuleAndTheRowStaysFiledByIt() {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = now(), categoryId = categoryId(UNCATEGORIZED)))
        screenFor()
        compose.waitForText(CATEGORY_CHIP)
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()
        compose.onNodeWithText(FOOD).performClick()
        save()
        compose.waitForText("$FOOD · $SOURCE_LABEL")

        compose.onNodeWithText("$FOOD · $SOURCE_LABEL").performClick()
        compose.waitForSheet()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Groceries").performClick()
        save()
        compose.waitForText("Groceries · $SOURCE_LABEL")

        val rules = Databases.merchantRuleDao(context).pageFrom(0L, 10)
        assertEquals("A re-tap wrote a second rule", listOf(categoryId("Groceries")), rules.map { it.categoryId })
        assertFalse(Databases.txnDao(context).recent(1).single().userEdited)
    }

    /** `MY50 PASS` is a shipped dictionary entry (Transport); the row is filed by it, so the switch starts off. */
    @Test fun aRowTheShippedDictionaryFiledStartsOffThroughTheRealRead() {
        val dao = context.freshLedger()
        dao.insert(
            ledgerTxn(amountSen = 5_000L, occurredAt = now(), categoryId = categoryId("Transport"))
                .copy(merchantRaw = "MY50 Pass", merchantDisplay = "My50 Pass", merchantKey = "MY50 PASS"),
        )

        screenFor()
        compose.waitForText("Transport · $SOURCE_LABEL")
        compose.onNodeWithText("Transport · $SOURCE_LABEL").performClick()
        compose.waitForSheet()

        compose.onNode(isToggleable()).assertIsOff()
    }

    // ---- the retroactive fix, through the real view model (#49) ------------

    @Test fun teachingFixesThePastPaymentsTheSheetCountedAndLeavesThemUnedited() {
        val dao = context.freshLedger()
        repeat(3) { dao.insert(ledgerTxn(amountSen = 5_000L + it, occurredAt = now() - it * 1_000L, categoryId = categoryId(UNCATEGORIZED))) }
        screenFor()
        compose.waitForText(CATEGORY_CHIP)
        compose.onAllNodesWithText(CATEGORY_CHIP)[0].performClick()
        compose.waitForSheet()
        compose.onNodeWithText(FOOD).performClick()

        compose.waitForText(fixLine(2))
        save()
        compose.boundedWaitUntil(TIMEOUT) {
            Databases.txnDao(context).recent(10).all { it.categoryId == categoryId(FOOD) }
        }

        val rows = Databases.txnDao(context).recent(10)
        assertEquals(3, rows.size)
        assertTrue("A fixed row became a one-off", rows.none { it.userEdited })
        assertEquals(1, Databases.merchantRuleDao(context).countAll())
    }

    @Test fun aHandSetPastPaymentIsNamedAndNothingMoves() {
        val dao = context.freshLedger()
        repeat(2) { dao.insert(ledgerTxn(amountSen = 5_000L + it, occurredAt = now() - it * 1_000L, categoryId = categoryId(UNCATEGORIZED))) }
        dao.insert(
            ledgerTxn(amountSen = 9_000L, occurredAt = now() - 5_000L, categoryId = categoryId("Transport"))
                .copy(userEdited = true),
        )
        screenFor()
        compose.waitForText(CATEGORY_CHIP)
        compose.onAllNodesWithText(CATEGORY_CHIP)[0].performClick()
        compose.waitForSheet()
        compose.onNodeWithText(FOOD).performClick()

        compose.waitForText("You've set 1 of these to Transport yourself. The rule applies to new payments only.")
        save()
        compose.boundedWaitUntil(TIMEOUT) { Databases.merchantRuleDao(context).countAll() == 1 }

        val rows = Databases.txnDao(context).recent(10)
        assertEquals("Only the tapped row should have left Uncategorized", 1, rows.count { it.categoryId == categoryId(FOOD) })
        assertEquals(1, rows.count { it.categoryId == categoryId(UNCATEGORIZED) })
        assertEquals(1, rows.count { it.categoryId == categoryId("Transport") })
    }

    // ---- fixtures --------------------------------------------------------

    private fun categoryId(name: String): Long =
        Databases.categoryDao(context).all().single { it.name == name }.id

    private fun now() = System.currentTimeMillis()

    /** The screen over the real database, refreshed for this month. */
    private fun screenFor(): LedgerViewModel {
        val viewModel = LedgerViewModel(context.applicationContext as Application, SavedStateHandle())
        val loaded = AtomicInteger(0)
        compose.setContent {
            PingedTheme {
                val items = viewModel.items.collectAsLazyPagingItems()
                val read by viewModel.read.state.collectAsState()
                loaded.set(items.itemCount)
                LedgerScreenContent(
                    items = items,
                    read = read.answer ?: LedgerRead(),
                    storageUnavailable = false,
                    categories = viewModel.rows,
                    merchants = viewModel.rows,
                    onOpenSettings = {},
                )
            }
        }
        compose.boundedWaitUntil(TIMEOUT) { loaded.get() > 0 }
        runBlocking { withTimeout(TIMEOUT) { viewModel.readMonth(YearMonth.now()) } }
        return viewModel
    }

    private fun save() = compose.onNodeWithText(SAVE_LABEL).performClick()

    private fun ComposeContentTestRule.waitForSheet() = waitForText(PICKER_HEADING)

    private fun ComposeContentTestRule.waitForText(text: String) {
        val appeared = runCatching { boundedWaitUntil(TIMEOUT) { onAllNodesWithTextSafely(text) > 0 } }.isSuccess
        assertTrue("Waited ${TIMEOUT}ms for '$text'", appeared)
    }

    private companion object {
        const val FOOD = "Food & Drinks"
        const val UNCATEGORIZED = "Uncategorized"

        /** 2020-09-13, far from any month the summary reads. */
        const val LONG_AGO = 1_600_000_000_000L
    }
}
