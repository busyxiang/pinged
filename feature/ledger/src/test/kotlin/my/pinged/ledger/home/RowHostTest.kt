package my.pinged.ledger.home

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import my.pinged.data.LocalDate
import my.pinged.data.dao.CategoryCount
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.MerchantChoice
import my.pinged.data.dao.MerchantMember
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.SOURCE_LABEL
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.DictionaryEntry
import my.pinged.parse.ExclusionReason
import my.pinged.parse.Filer
import my.pinged.parse.LearnedRules.Snapshot
import my.pinged.parse.MatchMode
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One row screen's chooser and merchant sheet, through [rememberRowHost] (#377):
 * which row is being categorized, what the chooser offers it, the switch and
 * count lines it opens with (#48, #49, #35), spec 6.4's sheet, and the two
 * options a screen may take -- no New category tile, no merchant sheet.
 *
 * Drawn over [LedgerRow]s in a plain list, against the `sharedTest` fakes, so
 * nothing here opens a database or is any one screen's. Each screen's own
 * test checks that it draws [RowHost.Sheets] at all; what its holder does with
 * a save is that screen's device test.
 */
@RunWith(AndroidJUnit4::class)
class RowHostTest {
    @get:Rule val compose = createComposeRule()

    private var categories = FakeCategoryActions()

    // ---- the host --------------------------------------------------------

    @Test fun aChipOpensTheChooserForItsRowAndASaveReportsTheRowTheCategoryAndTheSwitch() {
        draw(listOf(row(UNCATEGORIZED_ID, id = 76L, key = null, name = "Other"), row(UNCATEGORIZED_ID)))

        // The second row's chip: a host that opened on the first, or on no
        // particular row, reports another id.
        compose.onAllNodes(hasText(CATEGORY_CHIP))[1].performClick()
        compose.onNodeWithText(PICKER_HEADING).assertExists()
        compose.onNodeWithText("Groceries").performClick()
        save()

        assertEquals(
            "The chooser saved another row, another category, or not as a teaching save",
            listOf(Triple(ROW_ID, GROCERIES_ID, true)),
            categories.assigned,
        )
        compose.onNodeWithText(PICKER_HEADING).assertDoesNotExist()
    }

    /**
     * `Uncategorized` is not one of the things the chooser offers, though it
     * is among the categories read under the id the read names as the
     * uncategorized one: only the filter can keep it off.
     *
     * The chip is drawn on an uncategorized row, so tapping `Uncategorized` in
     * the sheet it opens is the natural "never mind" -- and it is not a no-op.
     * A one-off writes `user_edited = 1`, and §5.5 excludes an edited
     * transaction from the re-parse comparison for good.
     */
    @Test fun theChooserLeavesOutTheCategoryAnUncategorizedRowIsIn() {
        draw(listOf(row(UNCATEGORIZED_ID)))
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.onNodeWithText("Groceries").assertExists()

        assertEquals("The chooser offers Uncategorized", 0, count(hasText(UNCATEGORIZED.name)))
    }

    /**
     * With `Uncategorized` the only category the chooser has nothing to draw,
     * so a chip would take the tap and open nothing. Gated at the chip, so the
     * tap has nowhere to go rather than nowhere to land. Only an import can
     * leave this table.
     */
    @Test fun noChipIsOfferedWhenTheRowsOwnCategoryIsTheOnlyOne() {
        draw(listOf(row(UNCATEGORIZED_ID)), chooser = ChooserRead(listOf(UNCATEGORIZED), UNCATEGORIZED_ID))

        compose.onNodeWithText(MERCHANT).assertExists()
        assertEquals("A chip is offered with nothing to choose", 0, count(hasText(CATEGORY_CHIP)))
    }

    /**
     * The row being categorized comes back from a process death as its id,
     * and is drawn only once a read has the row again: the restored id is
     * not a row, and the read that would find it lands after the restore.
     */
    @Test fun aRestoredChooserWaitsForItsRowToBeReadAgain() {
        val restoration = StateRestorationTester(compose)
        var rows by mutableStateOf(listOf(row(UNCATEGORIZED_ID)))
        restoration.setContent { PingedTheme { Rows(rows) } }
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.onNodeWithText(PICKER_HEADING).assertExists()

        rows = emptyList()
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(PICKER_HEADING).assertDoesNotExist()
        rows = listOf(row(UNCATEGORIZED_ID))
        compose.onNodeWithText(PICKER_HEADING).assertExists()
    }

    @Test fun aScreenThatOffersNoNewCategoryDrawsNoTile() {
        draw(listOf(row(UNCATEGORIZED_ID)), offerNewCategory = false)
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.onNodeWithText(PICKER_HEADING).assertExists()

        assertEquals("A New category tile on a screen that offers none", 0, count(hasText(NEW_CATEGORY)))
    }

    @Test fun aScreenThatOffersANewCategoryDrawsTheTile() {
        draw(listOf(row(UNCATEGORIZED_ID)))
        compose.onNodeWithText(CATEGORY_CHIP).performClick()

        compose.onNodeWithText(NEW_CATEGORY).performScrollTo()
    }

    @Test fun anOpenMerchantSheetIsDrawn() {
        showSheet(withRules(), merchants = true)

        compose.onNodeWithText(NAME_HEADING).assertExists()
    }

    @Test fun aScreenThatOffersNoMerchantSheetDrawsNone() {
        showSheet(withRules(), merchants = false)

        compose.onNodeWithText(MERCHANT).assertExists()
        assertEquals("A merchant sheet on a screen that offers none", 0, count(hasText(NAME_HEADING)))
    }

    // ---- who may open the chooser ----------------------------------------

    /**
     * Four rows -- counted and excluded, uncategorized and categorized -- so
     * that each gate is asked of the row it governs and a screen that offered
     * the chooser nowhere cannot pass: exactly one chip and exactly one
     * clickable category line, and the excluded categorized row's line (its
     * category dropped, §7.3) is not a control.
     */
    @Test fun anExcludedRowCannotOpenTheChooserWhateverItsCategory() {
        fun txn(id: Long, category: Long, excluded: Boolean) = FeedRow(
            ledgerTxn(
                amountSen = 100L * id, categoryId = category, id = id, isExcluded = excluded,
                exclusionReason = if (excluded) ExclusionReason.TRANSFER else null,
            ),
            KEY,
            MERCHANT,
        )
        draw(
            listOf(
                txn(1L, UNCATEGORIZED_ID, excluded = false),
                txn(2L, UNCATEGORIZED_ID, excluded = true),
                txn(3L, GROCERIES_ID, excluded = true),
                txn(4L, GROCERIES_ID, excluded = false),
            ),
        )

        assertEquals("The chip is on both uncategorized rows, or on neither", 1, count(hasText(CATEGORY_CHIP)))
        assertEquals(
            "A category line other than the counted row's opens the chooser",
            1,
            count(hasText("Groceries · $SOURCE_LABEL") and hasClickAction()),
        )
        // The excluded rows' lines read as the source alone.
        compose.onAllNodes(hasText(SOURCE_LABEL)).fetchSemanticsNodes().forEach {
            assertFalse("An excluded row's line is a control", it.config.contains(SemanticsActions.OnClick))
        }
    }

    // ---- the switch's starting position, per row state (#48) -------------

    @Test fun anUncategorizedRowStartsOn() {
        teach(row(UNCATEGORIZED_ID))
        openChip()

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test fun aOneOffRowStartsOn() {
        teach(row(GROCERIES_ID, userEdited = true), dictionary = GROCERIES_ID)
        openCategorised()

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test fun aRowFiledByTheUsersRuleStartsOn() {
        teach(row(GROCERIES_ID), learned = mapOf(KEY to GROCERIES_ID), dictionary = GROCERIES_ID)
        openCategorised()

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test fun aRowFiledByTheDictionaryStartsOff() {
        teach(row(GROCERIES_ID), dictionary = GROCERIES_ID)
        openCategorised()

        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test fun aRowTheDictionaryNowFilesElsewhereStartsOn() {
        // The dictionary files this key under Petrol now; the row says Groceries.
        teach(row(GROCERIES_ID), dictionary = PETROL_ID)
        openCategorised()

        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test fun aRowWithNoMerchantKeyHasNoSwitchAndItsSaveIsAOneOff() {
        teach(row(UNCATEGORIZED_ID, key = null))
        openChip()

        assertEquals("A switch was drawn for a row there is nothing to learn from", 0, count(isToggleable()))
        compose.onNodeWithText(NO_MERCHANT_NOTE).assertExists()
        compose.onNodeWithText("Groceries").performClick()
        save()

        assertEquals(listOf(Triple(ROW_ID, GROCERIES_ID, false)), categories.assigned)
    }

    // ---- what a save reports ---------------------------------------------

    @Test fun aSaveReportsTheSwitchAsItStands() {
        teach(row(UNCATEGORIZED_ID))
        openChip()
        compose.onNodeWithText("Groceries").performClick()
        save()

        assertEquals("The switch started on and was never touched", listOf(Triple(ROW_ID, GROCERIES_ID, true)), categories.assigned)
    }

    @Test fun aSaveAfterTurningTheSwitchOffReportsAOneOff() {
        teach(row(UNCATEGORIZED_ID))
        openChip()
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Groceries").performClick()
        save()

        assertEquals(listOf(Triple(ROW_ID, GROCERIES_ID, false)), categories.assigned)
    }

    @Test fun theSwitchNamesTheCategoryPickedNotTheMerchant() {
        teach(row(UNCATEGORIZED_ID))
        openChip()
        compose.onNodeWithText(alwaysLabel(null)).assertExists()

        compose.onNodeWithText("Groceries").performClick()

        compose.onNodeWithText("Always call this Groceries").assertExists()
        compose.onNodeWithText(alwaysNote(MERCHANT)).assertExists()
        compose.onNodeWithText("Petrol & tolls").performClick()
        compose.onNodeWithText("Always call this Petrol & tolls").assertExists()
    }

    @Test fun aTapOnACategoryOnlySelectsItAndSaveIsOffUntilOneIsPicked() {
        teach(row(UNCATEGORIZED_ID))
        openChip()
        compose.onNodeWithText(SAVE_LABEL).assertIsNotEnabled()

        compose.onNodeWithText("Groceries").performClick()

        assertEquals("A tap on a category wrote", emptyList<Triple<Long, Long, Boolean>>(), categories.assigned)
        compose.onNodeWithText(SAVE_LABEL).assertIsEnabled()
        save()
        assertEquals(listOf(Triple(ROW_ID, GROCERIES_ID, true)), categories.assigned)
    }

    @Test fun aCategorisedRowOpensWithItsOwnCategorySelected() {
        teach(row(GROCERIES_ID), learned = mapOf(KEY to GROCERIES_ID))
        openCategorised()

        compose.onNodeWithText("Always call this Groceries").assertExists()
        compose.onNodeWithText(SAVE_LABEL).assertIsEnabled()
    }

    // ---- a merged shop's rule, said before the tap (#35) -----------------

    @Test fun aKeyMergedIntoAShopWithARuleNamesTheCategoryTheMergedShopIsFiledUnder() {
        teach(
            row(UNCATEGORIZED_ID, identity = MERGED_INTO),
            learned = mapOf(MERGED_INTO to GROCERIES_ID),
            aliases = mapOf(KEY to MERGED_INTO),
        )
        openChip()

        compose.onNodeWithText(mergedRuleNote("Groceries")).assertExists()
    }

    @Test fun theMerchantKeyAShopIsMergedIntoNamesItToo() {
        teach(row(UNCATEGORIZED_ID), learned = mapOf(KEY to GROCERIES_ID), aliases = mapOf("WARUNG ALI" to KEY))
        openChip()

        compose.onNodeWithText(mergedRuleNote("Groceries")).assertExists()
    }

    @Test fun aShopThatIsNotMergedSaysNothingAboutAMergedRule() {
        teach(row(UNCATEGORIZED_ID), learned = mapOf(KEY to GROCERIES_ID))
        openChip()

        assertEquals(0, count(hasText(mergedRuleNote("Groceries"))))
    }

    @Test fun aMergedShopWithNoRuleSaysNothingAboutOne() {
        teach(row(UNCATEGORIZED_ID, identity = MERGED_INTO), aliases = mapOf(KEY to MERGED_INTO))
        openChip()

        assertEquals(0, count(hasText(mergedRuleNote("Groceries"))))
    }

    // ---- the retroactive count (#49) -------------------------------------

    @Test fun theCountShowsOnlyWhileTheSwitchIsOnAndACategoryIsPicked() {
        categories = FakeCategoryActions(preview = { _, _ -> RetroPreview(5, emptyList()) })
        teach(row(UNCATEGORIZED_ID))
        openChip()
        assertEquals("A count with no category picked", 0, count(hasText(fixLine(5))))

        compose.onNodeWithText("Groceries").performClick()
        waitForText(fixLine(5))

        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOff()
        compose.waitUntil(TIMEOUT) { count(hasText(fixLine(5))) == 0 }
        compose.onNode(isToggleable()).performClick()
        waitForText(fixLine(5))
    }

    @Test fun theCountRecomputesOnEverySelection() {
        val asked = mutableListOf<Long>()
        categories = FakeCategoryActions(
            preview = { _, category ->
                asked += category
                if (category == GROCERIES_ID) RetroPreview(3, emptyList()) else RetroPreview(1, emptyList())
            },
        )
        teach(row(UNCATEGORIZED_ID))
        openChip()
        compose.onNodeWithText("Groceries").performClick()
        waitForText(fixLine(3))

        compose.onNodeWithText("Petrol & tolls").performClick()

        waitForText(fixLine(1))
        assertEquals("The old category's count stayed beside the new pick", 0, count(hasText(fixLine(3))))
        assertEquals(listOf(GROCERIES_ID, PETROL_ID), asked)
    }

    /** A count is the answer for the pick it was asked about, never the previous pick's while the next is pending. */
    @Test fun aCountForTheLastPickIsNotShownBesideTheNextWhileItIsPending() {
        val gate = CompletableDeferred<Unit>()
        categories = FakeCategoryActions(
            preview = { _, category ->
                if (category == GROCERIES_ID) {
                    RetroPreview(3, emptyList())
                } else {
                    gate.await()
                    RetroPreview(1, emptyList())
                }
            },
        )
        teach(row(UNCATEGORIZED_ID))
        openChip()
        compose.onNodeWithText("Groceries").performClick()
        waitForText(fixLine(3))

        compose.onNodeWithText("Petrol & tolls").performClick()
        compose.waitForIdle()

        assertEquals("Groceries' count is drawn beside Petrol", 0, count(hasText(fixLine(3))))
        gate.complete(Unit)
        waitForText(fixLine(1))
    }

    @Test fun theCountIsPhrasedForOne() {
        assertEquals("AND FIX THE 1 PAST ONE FROM THIS MERCHANT", fixLine(1))
        assertEquals("AND FIX THE 6 PAST ONES FROM THIS MERCHANT", fixLine(6))
    }

    @Test fun aConflictIsNamedAndReplacesTheCount() {
        categories = FakeCategoryActions(preview = { _, _ -> RetroPreview(2, listOf(CategoryCount(PETROL_ID, 6))) })
        teach(row(UNCATEGORIZED_ID))
        openChip()

        compose.onNodeWithText("Groceries").performClick()

        waitForText("You've set 6 of these to Petrol & tolls yourself. The rule applies to new payments only.")
        assertEquals("The count stayed beside a conflict", 0, count(hasText(fixLine(2))))
    }

    @Test fun aConflictInSeveralCategoriesSaysSo() {
        assertEquals(
            "You've set 7 of these to other categories yourself. The rule applies to new payments only.",
            conflictLine(7, listOf("Petrol & tolls", "Groceries")),
        )
    }

    // ---- the merchant sheet, from the row (spec 6.4) ---------------------

    @Test fun theRowShowsTheResolvedNameAndNotItsOwn() {
        draw(listOf(merchantRow(SCAN_AND_PAY, "Yuenkeehometowncafe", id = 1, name = "Yuen Kee")))

        compose.onNodeWithText("Yuen Kee").assertExists()
        assertEquals(0, count(hasText("Yuenkeehometowncafe")))
    }

    @Test fun aLongPressOpensTheSheetForTheRowsOwnKey() {
        val merchants = FakeMerchantActions()
        val pressed = merchantRow(SCAN_AND_PAY, "Yuenkeehometowncafe", id = 1)
        draw(listOf(pressed), merchants = merchants)

        compose.onNodeWithText("Yuenkeehometowncafe").performTouchInput { longClick() }

        assertEquals(
            "The row's own key, and its day for the header's month",
            listOf(SCAN_AND_PAY to pressed.txn.localDate),
            merchants.opened.map { it.ownKey to it.on },
        )
    }

    /** Beside a row that does offer it, so the assertion cannot pass because no row offers anything. */
    @Test fun onlyARowWithAMerchantOffersTheSheet() {
        draw(listOf(merchantRow(null, "Unknown", id = 1, name = null), merchantRow(SCAN_AND_PAY, "Yuenkeehometowncafe", id = 2)))
        compose.onNodeWithText("Unknown merchant").assertExists()

        val offering = SemanticsMatcher("offers $EDIT_MERCHANT") {
            it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == EDIT_MERCHANT
        }
        assertEquals("Exactly the row with a merchant offers the sheet", 1, count(offering))
    }

    // ---- what the sheet offers -------------------------------------------

    @Test fun saveIsOfferedOnlyOnceTheNameChangesAndSendsWhatWasTyped() {
        val merchants = showSheet(yuenKee())

        compose.onNodeWithText(SAVE_NAME).assertIsNotEnabled()
        compose.onNodeWithTag(NAME_FIELD_TAG, useUnmergedTree = true).performTextReplacementInField("Yuen Kee")
        compose.onNodeWithText(SAVE_NAME).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("Yuen Kee"), merchants.renamed)
    }

    @Test fun theSheetStatesHowFarARenameReaches() {
        showSheet(yuenKee())

        compose.onNodeWithText("2 TRANSACTIONS WILL SHOW A NEW NAME").assertExists()
    }

    @Test fun choosingAMerchantThenCountingItMergesIntoItAndTheSuggestionIsMarked() {
        val merchants = showSheet(yuenKee())
        compose.onNodeWithText(DUIT_NOW_NAME).assertExists()
        assertEquals("Exactly the one suggestion is marked", 1, count(hasText(SUGGESTED, substring = true)))

        compose.onNodeWithText(DUIT_NOW_NAME).performClick()
        assertEquals("A tap only chooses", emptyList<String>(), merchants.merged)
        val countIt = "Count it as $DUIT_NOW_NAME"
        compose.onNodeWithTag(MERCHANT_LIST_TAG).performScrollToNode(hasText(countIt))
        compose.onNodeWithText(countIt).performClick()

        assertEquals(listOf(DUIT_NOW), merchants.merged)
    }

    @Test fun aMergedMerchantOffersToSeparateItsOwnKey() {
        val merchants = showSheet(yuenKee(mergedInto = DUIT_NOW_NAME))

        compose.onNodeWithText("Separate from $DUIT_NOW_NAME").performClick()

        assertEquals(listOf(SCAN_AND_PAY), merchants.separated)
    }

    // ---- what a Separate is about to give back (#51) ---------------------

    @Test fun separateSaysASourceWithNoRuleOfItsOwnGoesBackToUncategorized() {
        showSheet(withRules(mergedInto = "Mr D.I.Y.", ruleCategory = "Food & Drinks", noOwnRule = true))
        assertEquals(1, count(hasText(SEPARATE_NO_RULE)))
    }

    @Test fun separateSaysNothingOfTheKindWhenTheSourceHasItsOwnRule() {
        showSheet(withRules(mergedInto = "Mr D.I.Y.", ruleCategory = "Food & Drinks", noOwnRule = false))
        assertEquals(0, count(hasText(SEPARATE_NO_RULE)))
    }

    @Test fun separateSaysNothingWhenTheMergedShopHasNoRuleToLose() {
        showSheet(withRules(mergedInto = "Mr D.I.Y.", ruleCategory = null, noOwnRule = true))
        assertEquals(0, count(hasText(SEPARATE_NO_RULE)))
    }

    @Test fun eachMemberWithNoRuleOfItsOwnCarriesTheSameWarning() {
        showSheet(
            withRules(
                ruleCategory = "Food & Drinks",
                members = listOf(
                    MerchantMember("A KEY", "A key", noOwnRule = true),
                    MerchantMember("B KEY", "B key", noOwnRule = false),
                ),
            ),
        )
        assertEquals("Only the member without a rule of its own", 1, count(hasText(SEPARATE_NO_RULE)))
    }

    @Test fun aMemberWithNoRuleIsNotWarnedAboutWhenTheMergedShopHasNoRuleToLose() {
        showSheet(withRules(ruleCategory = null, members = listOf(MerchantMember("A KEY", "A key", noOwnRule = true))))
        assertEquals(0, count(hasText(SEPARATE_NO_RULE)))
    }

    // ---- fixtures --------------------------------------------------------

    private fun row(
        categoryId: Long,
        id: Long = ROW_ID,
        key: String? = KEY,
        identity: String? = key,
        name: String = MERCHANT,
        userEdited: Boolean = false,
    ) = FeedRow(
        ledgerTxn(1_234L, localDate = DAY, categoryId = categoryId, id = id).copy(merchantKey = key, userEdited = userEdited),
        identity,
        name,
    )

    private fun merchantRow(key: String?, display: String, id: Long, name: String? = display) = FeedRow(
        ledgerTxn(amountSen = 1_250L, id = id).copy(merchantKey = key, merchantRaw = key, merchantDisplay = display),
        key,
        name,
    )

    /**
     * [rows] under a chooser whose filer knows [learned] rules, [aliases] and
     * a dictionary that files [KEY] under the category [dictionary], if any.
     */
    private fun teach(
        row: FeedRow,
        learned: Map<String, Long> = emptyMap(),
        aliases: Map<String, String> = emptyMap(),
        dictionary: Long? = null,
    ) = draw(
        listOf(row),
        chooser = CHOOSER.copy(
            filer = Filer(
                dictionary = listOf(DictionaryEntry(KEY, "Dictionary", MatchMode.EXACT)),
                categoryIds = listOfNotNull(dictionary?.let { "Dictionary" to it }).toMap(),
                learned = Snapshot(learned, aliases),
            ),
        ),
    )

    /** One row, with [sheet] open over it; what the sheet was asked to do is on the fake returned. */
    private fun showSheet(sheet: MerchantSheetState, merchants: Boolean = true): FakeMerchantActions {
        val fake = FakeMerchantActions(OpenMerchantSheet(MerchantOpening(sheet.ownKey, sheet.name, DAY), sheet))
        draw(listOf(row(GROCERIES_ID)), merchants = fake.takeIf { merchants })
        return fake
    }

    private fun draw(
        rows: List<FeedRow>,
        chooser: ChooserRead? = CHOOSER,
        merchants: MerchantActions? = FakeMerchantActions(),
        offerNewCategory: Boolean = true,
    ) = compose.setContent { PingedTheme { Rows(rows, chooser, merchants, offerNewCategory) } }

    /** A row screen at its smallest: the rows, and the host's sheets beside them. */
    @Composable
    private fun Rows(
        rows: List<FeedRow>,
        chooser: ChooserRead? = CHOOSER,
        merchants: MerchantActions? = FakeMerchantActions(),
        offerNewCategory: Boolean = true,
    ) {
        val host = rememberRowHost(
            chooser = chooser,
            findRow = { id -> rows.firstOrNull { it.txn.id == id } },
            categories = categories,
            merchants = merchants,
            offerNewCategory = offerNewCategory,
        )
        // No keyboard, for the reason `ChooserNewCategoryTest` gives.
        InterceptPlatformTextInput({ _, _ -> awaitCancellation() }) {
            LazyColumn {
                items(rows, key = { it.txn.id }) { row ->
                    LedgerRow(
                        row = row,
                        categoriesById = host.categoriesById,
                        uncategorizedId = chooser?.uncategorizedId,
                        canCategorize = host.canCategorize,
                        onCategorize = host::categorize,
                        onOpenMerchant = { merchants?.openMerchant(it) },
                        openings = RowOpenings.None,
                    )
                }
            }
            host.Sheets()
        }
    }

    private fun openChip() {
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.onNodeWithText(PICKER_HEADING).assertExists()
    }

    /** Opens the chooser from the category line of the single categorised row. */
    private fun openCategorised() {
        compose.onNodeWithText("Groceries · $SOURCE_LABEL").performClick()
        compose.onNodeWithText(PICKER_HEADING).assertExists()
    }

    private fun save() = compose.onNode(hasText(SAVE_LABEL) and hasClickAction()).performClick()

    private fun waitForText(text: String) = compose.waitUntil(TIMEOUT) { count(hasText(text)) > 0 }

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun SemanticsNodeInteraction.performTextReplacementInField(text: String) {
        // The tag is on the wrapper drawing the underline; the editable node is its child.
        onChildren()
            .filterToOne(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performTextReplacement(text)
    }

    private fun yuenKee(mergedInto: String? = null) = MerchantSheetState(
        ownKey = SCAN_AND_PAY,
        identityKey = if (mergedInto != null) DUIT_NOW else SCAN_AND_PAY,
        name = "Yuenkeehometowncafe",
        derivedName = "Yuenkeehometowncafe",
        txnCount = 2,
        mergedInto = mergedInto,
        members = emptyList(),
        others = listOf(
            MerchantChoice(DUIT_NOW, DUIT_NOW_NAME, DUIT_NOW_NAME, 1),
            MerchantChoice("SPADES BAKERY", "Spades Bakery", "Spades Bakery", 8),
        ),
        suggested = setOf(DUIT_NOW),
    )

    private fun withRules(
        ruleCategory: String? = null,
        mergedInto: String? = null,
        noOwnRule: Boolean = false,
        members: List<MerchantMember> = emptyList(),
    ) = MerchantSheetState(
        ownKey = "MR DIY KLCC",
        identityKey = if (mergedInto != null) "MR D.I.Y." else "MR DIY KLCC",
        name = "Mr DIY",
        derivedName = "Mr DIY",
        txnCount = 2,
        mergedInto = mergedInto,
        members = members,
        others = listOf(MerchantChoice("MR D.I.Y.", "Mr D.I.Y.", "Mr D.I.Y.", 3)),
        suggested = emptySet(),
        ruleCategory = ruleCategory,
        noOwnRule = noOwnRule,
    )

    private companion object {
        const val TIMEOUT = 5_000L
        const val UNCATEGORIZED_ID = 900L
        const val PETROL_ID = 41L
        const val GROCERIES_ID = 42L
        const val ROW_ID = 77L
        const val KEY = "WARUNG PAK ALI"
        const val MERGED_INTO = "WARUNG PAK ALI SDN BHD"
        const val SCAN_AND_PAY = "YUENKEEHOMETOWNCAFE"
        const val DUIT_NOW = "RESTORAN YUEN KEE HOME TOWN CAFE"
        const val DUIT_NOW_NAME = "Restoran Yuen Kee Home Town Cafe"
        val DAY = LocalDate(20_260_907)

        val PETROL = Category(id = PETROL_ID, name = "Petrol & tolls", iconKey = "fuel", sortOrder = 0)
        val GROCERIES = Category(id = GROCERIES_ID, name = "Groceries", iconKey = "shopping-basket", sortOrder = 1)
        val UNCATEGORIZED = Category(
            id = UNCATEGORIZED_ID, name = "Uncategorized", iconKey = "circle-dashed",
            sortOrder = 13, isProtected = true,
        )
        val CHOOSER = ChooserRead(categories = listOf(PETROL, GROCERIES, UNCATEGORIZED), uncategorizedId = UNCATEGORIZED_ID)
    }
}
