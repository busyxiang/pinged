package my.pinged.ledger

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.MerchantChoice
import my.pinged.ledger.home.EDIT_MERCHANT
import my.pinged.ledger.home.FILTER_FIELD_TAG
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.MERCHANT_LIST_TAG
import my.pinged.ledger.home.MerchantOpening
import my.pinged.ledger.home.MerchantSheetState
import my.pinged.ledger.home.NAME_FIELD_TAG
import my.pinged.ledger.home.OpenMerchantSheet
import my.pinged.ledger.home.SAVE_NAME
import my.pinged.ledger.home.SUGGESTED
import my.pinged.ledger.home.readMerchantSheet
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING_DELAY_MILLIS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * Spec 6.4's merchant sheet: how a row opens it, what it offers, and that its
 * writes reach the database through the holder.
 */
@RunWith(AndroidJUnit4::class)
class MerchantSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        scope.cancel()
        context.discardTheDatabase()
    }

    private val scanAndPay = "YUENKEEHOMETOWNCAFE"
    private val duitNow = "RESTORAN YUEN KEE HOME TOWN CAFE"

    private fun row(key: String?, display: String, id: Long, name: String? = display) = LedgerItem.Row(
        ledgerTxn(amountSen = 1_250L, id = id).copy(merchantKey = key, merchantRaw = key, merchantDisplay = display),
        identityKey = key,
        displayName = name,
    )

    /** The screen over [items], with [sheet] open over it; what the sheet was asked to do is on the fake returned. */
    private fun content(
        items: List<LedgerItem>,
        sheet: MerchantSheetState? = null,
    ): FakeMerchantActions {
        val merchants = FakeMerchantActions(sheet?.let { OpenMerchantSheet(MerchantOpening(it.ownKey, it.name, DAY), it) })
        val flow = flowOf(
            PagingData.from(
                items,
                LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true)),
            ),
        )
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = flow.collectAsLazyPagingItems(),
                    read = LedgerRead(),
                    storageUnavailable = false,
                    categories = FakeCategoryActions(),
                    merchants = merchants,
                    onOpenSettings = {},
                )
            }
        }
        return merchants
    }

    // ---- the row ------------------------------------------------------------

    @Test fun theRowShowsTheResolvedNameAndNotItsOwn() {
        content(listOf(row(scanAndPay, "Yuenkeehometowncafe", id = 1, name = "Yuen Kee")))
        compose.boundedWaitUntil(5_000) { compose.onAllNodesWithText("Yuen Kee").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("Yuenkeehometowncafe").fetchSemanticsNodes().size)
    }

    @Test fun aLongPressOpensTheSheetForTheRowsOwnKey() {
        val pressed = row(scanAndPay, "Yuenkeehometowncafe", id = 1)
        val merchants = content(listOf(pressed))
        compose.boundedWaitUntil(5_000) { compose.onAllNodesWithText("Yuenkeehometowncafe").fetchSemanticsNodes().isNotEmpty() }
        // `LedgerRead()` has no total, so the hero's dash and status line
        // come 200ms after the first frame and push the row down. Pressed
        // before them, the row moved under a long press still held and the
        // press was cancelled: let them draw first.
        compose.mainClock.advanceTimeBy(READING_DELAY_MILLIS + 40)
        compose.waitForIdle()

        compose.onNodeWithText("Yuenkeehometowncafe").performTouchInput { longClick() }
        compose.waitForIdle()

        assertEquals("The row's own key, and its day for the header's month", listOf(scanAndPay to pressed.txn.localDate), merchants.opened.map { it.ownKey to it.on })
    }

    /**
     * Beside a row that does offer it, so the assertion cannot pass because no
     * row offers anything.
     */
    @Test fun onlyARowWithAMerchantOffersTheSheet() {
        content(
            listOf(row(null, "Unknown", id = 1, name = null), row(scanAndPay, "Yuenkeehometowncafe", id = 2)),
        )
        compose.boundedWaitUntil(5_000) { compose.onAllNodesWithText("Unknown merchant").fetchSemanticsNodes().isNotEmpty() }

        val offering = compose.onAllNodes(
            SemanticsMatcher("offers $EDIT_MERCHANT") {
                it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == EDIT_MERCHANT
            },
        ).fetchSemanticsNodes()
        assertEquals("Exactly the row with a merchant offers the sheet", 1, offering.size)
    }

    // ---- the sheet ----------------------------------------------------------

    private val COUNT_IT_AS_YUEN_KEE = "Count it as Restoran Yuen Kee Home Town Cafe"

    private fun sheet(mergedInto: String? = null) = MerchantSheetState(
        ownKey = scanAndPay,
        identityKey = if (mergedInto != null) duitNow else scanAndPay,
        name = "Yuenkeehometowncafe",
        derivedName = "Yuenkeehometowncafe",
        txnCount = 2,
        mergedInto = mergedInto,
        members = emptyList(),
        others = listOf(
            MerchantChoice(duitNow, "Restoran Yuen Kee Home Town Cafe", "Restoran Yuen Kee Home Town Cafe", 1),
            MerchantChoice("SPADES BAKERY", "Spades Bakery", "Spades Bakery", 8),
        ),
        suggested = setOf(duitNow),
    )

    @Test fun saveIsOfferedOnlyOnceTheNameChangesAndSendsWhatWasTyped() {
        val merchants = content(listOf(row(scanAndPay, "Yuenkeehometowncafe", id = 1)), sheet())
        compose.boundedWaitUntil(5_000) { compose.onAllNodesWithText(SAVE_NAME).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(SAVE_NAME).assertIsNotEnabled()
        compose.onNodeWithTag(NAME_FIELD_TAG, useUnmergedTree = true).performTextReplacementInField("Yuen Kee")
        // The click action rather than a tap at the node's centre: a tap missed
        // the button 1 run in 5 on emulator-5554, with the keyboard the field
        // opened still up, and the cause was not pinned down. The enabled
        // state asserted here is what the tap would have proved.
        compose.onNodeWithText(SAVE_NAME).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("Yuen Kee"), merchants.renamed)
    }

    @Test fun theSheetStatesHowFarARenameReaches() {
        content(listOf(row(scanAndPay, "Yuenkeehometowncafe", id = 1)), sheet())
        compose.boundedWaitUntil(5_000) {
            compose.onAllNodesWithText("2 TRANSACTIONS WILL SHOW A NEW NAME").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun choosingAMerchantThenCountingItMergesIntoItAndTheSuggestionIsMarked() {
        val merchants = content(listOf(row(scanAndPay, "Yuenkeehometowncafe", id = 1)), sheet())
        compose.boundedWaitUntil(5_000) {
            compose.onAllNodesWithText("Restoran Yuen Kee Home Town Cafe").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            "Exactly the one suggestion is marked",
            1,
            compose.onAllNodesWithText(SUGGESTED, substring = true).fetchSemanticsNodes().size,
        )

        compose.onNodeWithText("Restoran Yuen Kee Home Town Cafe").performClick()
        assertEquals("A tap only chooses", emptyList<String>(), merchants.merged)
        compose.onNodeWithTag(MERCHANT_LIST_TAG).performScrollToNode(hasText(COUNT_IT_AS_YUEN_KEE))
        compose.onNodeWithText(COUNT_IT_AS_YUEN_KEE).performClick()

        assertEquals(listOf(duitNow), merchants.merged)
    }

    /**
     * Displayed, not only composed: at CI's 320x640 the keyboard leaves the
     * list 229px with the field at its bottom, so a result is only in view if
     * typing scrolls the field up.
     */
    @Test fun theSearchNarrowsTheList() {
        content(listOf(row(scanAndPay, "Yuenkeehometowncafe", id = 1)), sheet())
        compose.boundedWaitUntil(5_000) {
            compose.onAllNodesWithText("Restoran Yuen Kee Home Town Cafe").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("A blank search lists the suggestions only", 0, compose.onAllNodesWithText("Spades Bakery").fetchSemanticsNodes().size)

        compose.onNodeWithTag(FILTER_FIELD_TAG, useUnmergedTree = true).performTextReplacementInField("spades")
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithText("Restoran Yuen Kee Home Town Cafe").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Spades Bakery").fetchSemanticsNodes().size)
        compose.onNodeWithText("Spades Bakery").assertIsDisplayed()
    }

    @Test fun aMergedMerchantOffersToSeparateItsOwnKey() {
        val merchants = content(
            listOf(row(scanAndPay, "Yuenkeehometowncafe", id = 1)),
            sheet(mergedInto = "Restoran Yuen Kee Home Town Cafe"),
        )
        val separate = "Separate from Restoran Yuen Kee Home Town Cafe"
        compose.boundedWaitUntil(5_000) { compose.onAllNodesWithText(separate).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(separate).performClick()

        assertEquals(listOf(scanAndPay), merchants.separated)
    }

    // ---- through the holder, against the real database ----------------------

    private fun insert(key: String, display: String, amountSen: Long, at: Long): Long =
        Databases.txnDao(context).insert(
            ledgerTxn(amountSen = amountSen, occurredAt = at, categoryId = uncategorized())
                .copy(merchantKey = key, merchantRaw = key, merchantDisplay = display),
        )

    /** The day the inserted rows fall on; the sheet's header counts its month. */
    private val DAY = LocalDates.of(1_000L)

    private fun uncategorized(): Long = Databases.categoryDao(context).requireUncategorizedId()


    @Test fun theHolderMergesWhatTheSheetWasOpenedOn() {
        context.freshLedger()
        insert(scanAndPay, "Yuenkeehometowncafe", 1_250L, at = 1_000L)
        insert(duitNow, "Restoran Yuen Kee Home Town Cafe", 1_500L, at = 2_000L)
        val rows = context.rowActions(scope)

        runBlocking { withTimeout(10_000) { rows.openMerchant(MerchantOpening(scanAndPay, "Yuenkeehometowncafe", DAY)).join() } }
        val sheet = rows.merchantSheet.value!!.read!!
        assertEquals(listOf(duitNow), sheet.others.map { it.identityKey })
        assertEquals(setOf(duitNow), sheet.suggested)

        runBlocking { withTimeout(10_000) { rows.mergeMerchant(duitNow).join() } }

        assertNull("The sheet stays open after its write", rows.merchantSheet.value)
        val dao = Databases.merchantIdentityDao(context)
        assertEquals(duitNow, dao.canonicalOf(scanAndPay))
        assertEquals("Restoran Yuen Kee Home Town Cafe", dao.nameOf(duitNow))
    }

    @Test fun theHolderRenamesTheMergedMerchantFromEitherKey() {
        context.freshLedger()
        insert(scanAndPay, "Yuenkeehometowncafe", 1_250L, at = 1_000L)
        insert(duitNow, "Restoran Yuen Kee Home Town Cafe", 1_500L, at = 2_000L)
        val dao = Databases.merchantIdentityDao(context)
        dao.merge(scanAndPay, duitNow, "Restoran Yuen Kee Home Town Cafe")

        val sheet = readMerchantSheet(dao, Databases.txnDao(context), scanAndPay, DAY)!!
        assertEquals(duitNow, sheet.identityKey)
        assertEquals("Restoran Yuen Kee Home Town Cafe", sheet.mergedInto)
        assertEquals(2, sheet.txnCount)

        val rows = context.rowActions(scope)
        runBlocking { withTimeout(10_000) { rows.openMerchant(MerchantOpening(scanAndPay, "Yuenkeehometowncafe", DAY)).join() } }
        runBlocking { withTimeout(10_000) { rows.renameMerchant("Yuen Kee").join() } }

        assertEquals("Yuen Kee", dao.nameOf(duitNow))
    }

    @Test fun theCanonicalSheetListsWhatWasMergedIntoIt() {
        context.freshLedger()
        insert(scanAndPay, "Yuenkeehometowncafe", 1_250L, at = 1_000L)
        insert(duitNow, "Restoran Yuen Kee Home Town Cafe", 1_500L, at = 2_000L)
        val dao = Databases.merchantIdentityDao(context)
        dao.merge(scanAndPay, duitNow, "Restoran Yuen Kee Home Town Cafe")

        val sheet = readMerchantSheet(dao, Databases.txnDao(context), duitNow, DAY)!!
        assertNull(sheet.mergedInto)
        assertEquals(listOf(scanAndPay to 1), sheet.members.map { it.merchantKey to it.txnCount })
        assertEquals(YearMonth.from(LocalDates.calendarDay(DAY)), sheet.month)
        assertEquals(
            "Both keys' payments, as that month's WHO GOT IT row counts them",
            listOf(2 to 2_750L),
            sheet.monthTotals.map { it.txnCount to it.netSen },
        )
    }

    private fun SemanticsNodeInteraction.performTextReplacementInField(text: String) {
        // The tag is on the wrapper drawing the underline; the editable node is
        // its child.
        onChildren()
            .filterToOne(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performTextReplacement(text)
    }
}
