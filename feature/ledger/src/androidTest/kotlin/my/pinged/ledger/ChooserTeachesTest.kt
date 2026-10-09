package my.pinged.ledger

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.dao.CategoryCount
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.RuleOrigin
import my.pinged.ledger.home.CATEGORY_CHIP
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.home.NO_MERCHANT_NOTE
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ledger.home.alwaysLabel
import my.pinged.ledger.home.alwaysNote
import my.pinged.ledger.home.conflictLine
import my.pinged.ledger.home.fixLine
import my.pinged.ledger.home.mergedRuleNote
import my.pinged.ledger.theme.PingedTheme
import my.pinged.parse.ExclusionReason
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
 * #48: the chooser's "Always call this" switch and what a tap does with it.
 *
 * The switch's starting position is driven through the stateless content,
 * because each of the five row states is a fact about a row that a fixture can
 * state directly; the saves are driven through the real view model against the
 * real encrypted database, because what they assert is the rule and the row's
 * `user_edited` that land there.
 */
@RunWith(AndroidJUnit4::class)
class ChooserTeachesTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    // ---- the switch's starting position, per row state -------------------

    @Test fun anUncategorizedRowStartsOn() {
        content(row(UNCATEGORIZED_ID))
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test fun aOneOffRowStartsOn() {
        content(row(GROCERIES_ID, userEdited = true), dictionary = { GROCERIES_ID })
        openCategorised()

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test fun aRowFiledByTheUsersRuleStartsOn() {
        content(row(GROCERIES_ID), learned = mapOf(KEY to GROCERIES_ID), dictionary = { GROCERIES_ID })
        openCategorised()

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test fun aRowFiledByTheDictionaryStartsOff() {
        content(row(GROCERIES_ID), dictionary = { key -> if (key == KEY) GROCERIES_ID else null })
        openCategorised()

        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test fun anOldRuleRowStartsOn() {
        // The dictionary files this key under Petrol now; the row says Groceries.
        content(row(GROCERIES_ID), dictionary = { PETROL_ID })
        openCategorised()

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test fun aRowWithNoMerchantKeyHasNoSwitchAndItsSaveIsAOneOff() {
        val saves = mutableListOf<Triple<Long, Long, Boolean>>()
        content(row(UNCATEGORIZED_ID, key = null), onAssign = { t, c, teach -> saves += Triple(t, c, teach) })
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()

        assertEquals("A switch was drawn for a row there is nothing to learn from", 0, compose.countOf(isToggleable()))
        compose.onNodeWithText(NO_MERCHANT_NOTE).assertExists()
        compose.onNodeWithText("Groceries").performClick()
        save()

        assertEquals(listOf(Triple(ROW_ID, GROCERIES_ID, false)), saves)
    }

    // ---- what a tap reports ----------------------------------------------

    @Test fun aTapReportsTheSwitchAsItStands() {
        val saves = mutableListOf<Triple<Long, Long, Boolean>>()
        content(row(UNCATEGORIZED_ID), onAssign = { t, c, teach -> saves += Triple(t, c, teach) })
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()
        compose.onNodeWithText("Groceries").performClick()
        save()
        assertEquals("The switch started on and was never touched", listOf(Triple(ROW_ID, GROCERIES_ID, true)), saves)
    }

    @Test fun aTapAfterTurningTheSwitchOffReportsAOneOff() {
        val saves = mutableListOf<Triple<Long, Long, Boolean>>()
        content(row(UNCATEGORIZED_ID), onAssign = { t, c, teach -> saves += Triple(t, c, teach) })
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Groceries").performClick()
        save()
        assertEquals(listOf(Triple(ROW_ID, GROCERIES_ID, false)), saves)
    }

    @Test fun theSwitchNamesTheCategoryPickedNotTheMerchant() {
        content(row(UNCATEGORIZED_ID))
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()
        compose.onNodeWithText(alwaysLabel(null)).assertExists()

        compose.onNodeWithText("Groceries").performClick()

        compose.onNodeWithText("Always call this Groceries").assertExists()
        compose.onNodeWithText(alwaysNote(MERCHANT)).assertExists()
        compose.onNodeWithText("Petrol & tolls").performClick()
        compose.onNodeWithText("Always call this Petrol & tolls").assertExists()
    }

    // ---- pick, then save -------------------------------------------------

    @Test fun aTapOnACategoryOnlySelectsItAndSaveIsOffUntilOneIsPicked() {
        val saves = mutableListOf<Triple<Long, Long, Boolean>>()
        content(row(UNCATEGORIZED_ID), onAssign = { t, c, teach -> saves += Triple(t, c, teach) })
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()
        compose.onNodeWithText(SAVE_LABEL).assertIsNotEnabled()

        compose.onNodeWithText("Groceries").performClick()

        assertEquals("A tap on a category wrote", emptyList<Triple<Long, Long, Boolean>>(), saves)
        compose.onNodeWithText(SAVE_LABEL).assertIsEnabled()
        compose.onNodeWithText(SAVE_LABEL).performClick()
        assertEquals(listOf(Triple(ROW_ID, GROCERIES_ID, true)), saves)
    }

    @Test fun aCategorisedRowOpensWithItsOwnCategorySelected() {
        content(row(GROCERIES_ID), learned = mapOf(KEY to GROCERIES_ID))
        openCategorised()

        compose.onNodeWithText("Always call this Groceries").assertExists()
        compose.onNodeWithText(SAVE_LABEL).assertIsEnabled()
    }

    // ---- a merged shop's rule, said before the tap (#35) -----------------

    @Test fun aKeyMergedIntoAShopWithARuleNamesTheCategoryTheMergedShopIsFiledUnder() {
        content(
            row(UNCATEGORIZED_ID, identity = CANONICAL),
            learned = mapOf(CANONICAL to GROCERIES_ID),
        )
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()

        compose.onNodeWithText(mergedRuleNote("Groceries")).assertExists()
    }

    @Test fun theCanonicalKeyOfAMergedShopNamesItToo() {
        content(
            row(UNCATEGORIZED_ID),
            learned = mapOf(KEY to GROCERIES_ID),
            merged = setOf(KEY),
        )
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()

        compose.onNodeWithText(mergedRuleNote("Groceries")).assertExists()
    }

    @Test fun aShopThatIsNotMergedSaysNothingAboutAMergedRule() {
        content(row(UNCATEGORIZED_ID), learned = mapOf(KEY to GROCERIES_ID))
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()

        assertEquals(0, compose.countOf(hasText(mergedRuleNote("Groceries"))))
    }

    @Test fun aMergedShopWithNoRuleSaysNothingAboutOne() {
        content(row(UNCATEGORIZED_ID, identity = CANONICAL), merged = setOf(CANONICAL))
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()

        assertEquals(0, compose.countOf(hasText(mergedRuleNote("Groceries"))))
    }

    // ---- the retroactive count (#49) --------------------------------------

    @Test fun theCountShowsOnlyWhileTheSwitchIsOnAndACategoryIsPicked() {
        content(row(UNCATEGORIZED_ID), retro = { _, _ -> RetroPreview(5, emptyList()) })
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()
        assertEquals("A count with no category picked", 0, compose.countOf(hasText(fixLine(5))))

        compose.onNodeWithText("Groceries").performClick()
        compose.waitForText(fixLine(5))

        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
        compose.waitUntil(TIMEOUT) { compose.countOf(hasText(fixLine(5))) == 0 }
        compose.onNode(isToggleable()).performClick()
        compose.waitForText(fixLine(5))
    }

    @Test fun theCountRecomputesOnEverySelection() {
        val asked = mutableListOf<Long>()
        content(
            row(UNCATEGORIZED_ID),
            retro = { _, category ->
                asked += category
                if (category == GROCERIES_ID) RetroPreview(3, emptyList()) else RetroPreview(1, emptyList())
            },
        )
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()
        compose.onNodeWithText("Groceries").performClick()
        compose.waitForText(fixLine(3))

        compose.onNodeWithText("Petrol & tolls").performClick()

        compose.waitForText(fixLine(1))
        assertEquals("The old category's count stayed beside the new pick", 0, compose.countOf(hasText(fixLine(3))))
        assertEquals(listOf(GROCERIES_ID, PETROL_ID), asked)
    }

    /** A count is the answer for the pick it was asked about, never the previous pick's while the next is pending. */
    @Test fun aCountForTheLastPickIsNotShownBesideTheNextWhileItIsPending() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        content(
            row(UNCATEGORIZED_ID),
            retro = { _, category ->
                if (category == GROCERIES_ID) {
                    RetroPreview(3, emptyList())
                } else {
                    gate.await()
                    RetroPreview(1, emptyList())
                }
            },
        )
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()
        compose.onNodeWithText("Groceries").performClick()
        compose.waitForText(fixLine(3))

        compose.onNodeWithText("Petrol & tolls").performClick()
        compose.waitForIdle()

        assertEquals("Groceries' count is drawn beside Petrol", 0, compose.countOf(hasText(fixLine(3))))
        gate.complete(Unit)
        compose.waitForText(fixLine(1))
    }

    @Test fun theCountIsPhrasedForOne() {
        assertEquals("AND FIX THE 1 PAST ONE FROM THIS MERCHANT", fixLine(1))
        assertEquals("AND FIX THE 6 PAST ONES FROM THIS MERCHANT", fixLine(6))
    }

    @Test fun aConflictIsNamedAndReplacesTheCount() {
        content(
            row(UNCATEGORIZED_ID),
            retro = { _, _ -> RetroPreview(2, listOf(CategoryCount(PETROL_ID, 6))) },
        )
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForSheet()

        compose.onNodeWithText("Groceries").performClick()

        compose.waitForText("You've set 6 of these to Petrol & tolls yourself. The rule applies to new payments only.")
        assertEquals("The count stayed beside a conflict", 0, compose.countOf(hasText(fixLine(2))))
    }

    @Test fun aConflictInSeveralCategoriesSaysSo() {
        assertEquals(
            "You've set 7 of these to other categories yourself. The rule applies to new payments only.",
            conflictLine(7, listOf("Petrol & tolls", "Groceries")),
        )
    }

    // ---- who may open it -------------------------------------------------

    /**
     * Four rows -- open and excluded, uncategorised and categorised -- so that
     * each gate is asked of the row it governs and a screen that offered the
     * chooser nowhere cannot pass: exactly one chip and exactly one clickable
     * category line, and the excluded categorised row's line (its category is
     * dropped, §7.3) is not a control.
     */
    @Test fun anExcludedRowCannotOpenTheChooserWhateverItsCategory() {
        fun txn(id: Long, category: Long, excluded: Boolean) = LedgerItem.Row(
            ledgerTxn(
                amountSen = 100L * id, categoryId = category, id = id, isExcluded = excluded,
                exclusionReason = if (excluded) ExclusionReason.TRANSFER else null,
            ),
        )
        contentOf(
            listOf(
                txn(1L, UNCATEGORIZED_ID, excluded = false),
                txn(2L, UNCATEGORIZED_ID, excluded = true),
                txn(3L, GROCERIES_ID, excluded = true),
                txn(4L, GROCERIES_ID, excluded = false),
            ),
        )

        assertEquals(
            "The chip is on both uncategorised rows, or on neither",
            1,
            compose.countOf(androidx.compose.ui.test.hasText(CATEGORY_CHIP)),
        )
        assertEquals(
            "A category line other than the open row's opens the chooser",
            1,
            compose.countOf(androidx.compose.ui.test.hasText("Groceries · $SOURCE_LABEL").and(androidx.compose.ui.test.hasClickAction())),
        )
        // The excluded rows' lines read as the source alone.
        compose.onAllNodes(androidx.compose.ui.test.hasText(SOURCE_LABEL)).fetchSemanticsNodes().forEach {
            assertFalse(
                "An excluded row's line is a control",
                it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick),
            )
        }
    }

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
        compose.waitUntil(TIMEOUT) { Databases.merchantRuleDao(context).countAll() == 1 }

        compose.onNodeWithText("Transport · $SOURCE_LABEL").performClick()
        compose.waitForSheet()

        compose.onNode(isToggleable()).assertIsOn()
    }

    /** No merchant key: the view model saves the one-off the sheet promised, even if asked to teach. */
    @Test fun aKeylessRowIsSavedAsAOneOffEvenWhenAskedToTeach() {
        val dao = context.freshLedger()
        val id = dao.insert(
            ledgerTxn(amountSen = 5_000L, occurredAt = now(), categoryId = categoryId(UNCATEGORIZED))
                .copy(merchantRaw = null, merchantDisplay = null, merchantKey = null),
        )
        val viewModel = LedgerViewModel(context.applicationContext as Application)

        runBlocking { withTimeout(TIMEOUT) { viewModel.assignCategory(id, categoryId(FOOD), teach = true).join() } }

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
        compose.waitUntil(TIMEOUT) {
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
        compose.waitUntil(TIMEOUT) { Databases.merchantRuleDao(context).countAll() == 1 }

        val rows = Databases.txnDao(context).recent(10)
        assertEquals("Only the tapped row should have left Uncategorized", 1, rows.count { it.categoryId == categoryId(FOOD) })
        assertEquals(1, rows.count { it.categoryId == categoryId(UNCATEGORIZED) })
        assertEquals(1, rows.count { it.categoryId == categoryId("Transport") })
    }

    // ---- fixtures --------------------------------------------------------

    private fun categoryId(name: String): Long =
        Databases.categoryDao(context).all().single { it.name == name }.id

    private fun now() = System.currentTimeMillis()

    private fun row(
        categoryId: Long,
        userEdited: Boolean = false,
        key: String? = KEY,
        identity: String? = key,
    ) = LedgerItem.Row(
        ledgerTxn(amountSen = 1_234L, localDate = DAY, categoryId = categoryId, id = ROW_ID)
            .copy(merchantKey = key, userEdited = userEdited),
        identityKey = identity,
        displayName = MERCHANT,
    )

    /** Opens the chooser from the category line of the single categorised row. */
    private fun openCategorised() {
        compose.onNodeWithText("Groceries · $SOURCE_LABEL").performClick()
        compose.waitForSheet()
    }

    private fun content(
        row: LedgerItem.Row,
        learned: Map<String, Long> = emptyMap(),
        merged: Set<String> = emptySet(),
        dictionary: (String?) -> Long? = { null },
        onAssign: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
        retro: suspend (Long, Long) -> RetroPreview = { _, _ -> RetroPreview(0, emptyList()) },
    ) = contentOf(listOf(row), learned, merged, dictionary, onAssign, retro)

    private fun contentOf(
        rows: List<LedgerItem>,
        learned: Map<String, Long> = emptyMap(),
        merged: Set<String> = emptySet(),
        dictionary: (String?) -> Long? = { null },
        onAssign: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
        retro: suspend (Long, Long) -> RetroPreview = { _, _ -> RetroPreview(0, emptyList()) },
    ) {
        val read = LedgerRead(
            categories = listOf(
                Category(id = PETROL_ID, name = "Petrol & tolls", iconKey = "fuel", sortOrder = 0),
                Category(id = GROCERIES_ID, name = "Groceries", iconKey = "shopping-basket", sortOrder = 1),
                Category(
                    id = UNCATEGORIZED_ID, name = UNCATEGORIZED, iconKey = "circle-dashed",
                    sortOrder = 13, isProtected = true,
                ),
            ),
            uncategorizedId = UNCATEGORIZED_ID,
            learnedRules = learned,
            mergedIdentities = merged,
            dictionaryFiling = dictionary,
        )
        val flow = flowOf(
            PagingData.from(
                rows,
                LoadStates(
                    refresh = LoadState.NotLoading(true),
                    prepend = LoadState.NotLoading(true),
                    append = LoadState.NotLoading(true),
                ),
            ),
        )
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = flow.collectAsLazyPagingItems(),
                    read = read,
                    storageUnavailable = false,
                    onAssign = onAssign,
                    onOpenSettings = {},
                    retroPreview = retro,
                )
            }
        }
    }

    /** The screen over the real database, refreshed for this month. */
    private fun screenFor(): LedgerViewModel {
        val viewModel = LedgerViewModel(context.applicationContext as Application)
        val loaded = AtomicInteger(0)
        compose.setContent {
            PingedTheme {
                val items = viewModel.items.collectAsLazyPagingItems()
                val read by viewModel.read.collectAsState()
                loaded.set(items.itemCount)
                LedgerScreenContent(
                    items = items,
                    read = read,
                    storageUnavailable = false,
                    onAssign = { txnId, categoryId, teach -> viewModel.assignCategory(txnId, categoryId, teach) },
                    onOpenSettings = {},
                    retroPreview = viewModel::retroPreview,
                )
            }
        }
        compose.waitUntil(TIMEOUT) { loaded.get() > 0 }
        runBlocking { withTimeout(TIMEOUT) { viewModel.refresh(YearMonth.now()).join() } }
        return viewModel
    }

    private fun save() = compose.onNodeWithText(SAVE_LABEL).performClick()

    private fun ComposeContentTestRule.waitForSheet() = waitForText(PICKER_HEADING)

    private fun ComposeContentTestRule.waitForText(text: String) {
        val appeared = runCatching { waitUntil(TIMEOUT) { onAllNodesWithTextSafely(text) > 0 } }.isSuccess
        assertTrue("Waited ${TIMEOUT}ms for '$text'", appeared)
    }

    private fun ComposeContentTestRule.countOf(matcher: androidx.compose.ui.test.SemanticsMatcher): Int =
        onAllNodes(matcher).fetchSemanticsNodes().size

    private companion object {
        const val FOOD = "Food & Drinks"
        const val UNCATEGORIZED = "Uncategorized"
        const val UNCATEGORIZED_ID = 900L
        const val PETROL_ID = 41L
        const val GROCERIES_ID = 42L
        const val ROW_ID = 77L

        /** 2020-09-13, far from any month the summary reads. */
        const val LONG_AGO = 1_600_000_000_000L
        const val KEY = "WARUNG PAK ALI"
        const val CANONICAL = "WARUNG PAK ALI SDN BHD"
        val DAY = my.pinged.data.LocalDate(20_260_907)
    }
}
