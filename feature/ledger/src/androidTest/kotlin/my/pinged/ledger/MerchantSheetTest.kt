package my.pinged.ledger

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
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
import my.pinged.ledger.home.FILTER_FIELD_TAG
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.MerchantOpening
import my.pinged.ledger.home.MerchantSheetState
import my.pinged.ledger.home.OpenMerchantSheet
import my.pinged.ledger.home.readMerchantSheet
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * Spec 6.4's merchant sheet on a device: its search with the keyboard up, and
 * that its writes reach the database through the holder. How a row opens it
 * and what it offers are `RowHostTest`'s, on the JVM.
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

    // ---- the sheet ----------------------------------------------------------

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
