package my.pinged.ledger

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.ledger.home.CATEGORY_CHIP
import my.pinged.ledger.home.CategoryActions
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * #158: a save from a row's chooser leaves the ledger where it was.
 *
 * The save's write invalidates the feed, and while the new page loads its
 * `refresh` is `Loading` again. Driven through the real view model and the real
 * database, because that second refresh is what moved the list and a fixed
 * `PagingData` never produces one.
 */
@RunWith(AndroidJUnit4::class)
class LedgerScrollTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test fun aRowCategorizedBelowTheFirstScreenStaysInView() {
        val dao = context.freshLedger()
        val petrol = categoryId("Petrol & tolls")
        val uncategorized = Databases.categoryDao(context).requireUncategorizedId()
        // Newest first, so the one uncategorized row is the last of the list and
        // the only `+ CATEGORY` chip in it.
        val start = System.currentTimeMillis() - ROWS * HOUR
        val ids = (0 until ROWS).map { i ->
            val categoryId = if (i == 0) uncategorized else petrol
            dao.insert(
                ledgerTxn(amountSen = 100L + i, occurredAt = start + i * HOUR, categoryId = categoryId)
                    .copy(merchantDisplay = shop(i), merchantKey = "SHOP $i", merchantRaw = "SHOP $i"),
            )
        }

        val saved = AtomicReference<Job?>(null)
        screenFor(saved)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(CATEGORY_CHIP))
        compose.onNodeWithText(shop(ROWS - 1)).assertDoesNotExist()

        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        val opened = runCatching {
            compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely(PICKER_HEADING) > 0 }
        }.isSuccess
        assertTrue("The chooser did not open", opened)
        compose.onNodeWithText("Groceries").performClick()
        compose.onNodeWithText(SAVE_LABEL).performClick()
        val sent = runCatching { compose.boundedWaitUntil(TIMEOUT) { saved.get() != null } }.isSuccess
        assertTrue("Save reported no assignment", sent)
        runBlocking { withTimeout(TIMEOUT) { saved.get()!!.join() } }
        assertEquals(categoryId("Groceries"), dao.byId(ids[0])?.categoryId)

        val shown = runCatching {
            compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("Groceries · $SOURCE_LABEL") > 0 }
        }.isSuccess
        assertTrue("The recategorized row is no longer composed: the list moved off it", shown)
        compose.onNodeWithText(shop(0)).assertIsDisplayed()
        compose.onNodeWithText(shop(ROWS - 1)).assertDoesNotExist()
    }

    /**
     * The state a save leaves the ledger in for as long as its reload takes:
     * the rows still loaded, `refresh` at `Loading`. The test above reaches it
     * only when a frame lands inside the reload, three runs in four on an idle
     * emulator, so the state is held here rather than raced for.
     */
    @Test fun aReloadOverLoadedRowsLeavesTheListWhereItWas() {
        val rows: List<LedgerItem> = (0 until ROWS).map { i ->
            LedgerItem.Row(
                ledgerTxn(amountSen = 100L + i, localDate = DAY, id = ROWS - i.toLong())
                    .copy(merchantDisplay = shop(ROWS - 1 - i)),
                identityKey = null,
                displayName = shop(ROWS - 1 - i),
            )
        }
        val pages = MutableStateFlow(PagingData.from(rows, states(LoadState.NotLoading(true))))
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = pages.collectAsLazyPagingItems(),
                    read = LedgerRead(),
                    storageUnavailable = false,
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(shop(0)))
        compose.onNodeWithText(shop(ROWS - 1)).assertDoesNotExist()

        pages.value = PagingData.from(rows, states(LoadState.Loading))
        compose.waitForIdle()

        compose.onNodeWithText(shop(0)).assertIsDisplayed()
        compose.onNodeWithText(shop(ROWS - 1)).assertDoesNotExist()
    }

    private fun states(refresh: LoadState) =
        LoadStates(refresh = refresh, prepend = LoadState.NotLoading(true), append = LoadState.NotLoading(true))

    private fun categoryId(name: String): Long =
        Databases.categoryDao(context).all().single { it.name == name }.id

    private fun shop(i: Int) = "Shop %02d".format(i)

    private fun screenFor(saved: AtomicReference<Job?>) {
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
                    // The rows' own actions, keeping the save's `Job` for the test to join.
                    categories = remember {
                        object : CategoryActions by viewModel.rows {
                            override fun assignCategory(txnId: Long, categoryId: Long, teach: Boolean): Job =
                                viewModel.rows.assignCategory(txnId, categoryId, teach).also(saved::set)
                        }
                    },
                    merchants = viewModel.rows,
                    onOpenSettings = {},
                )
            }
        }
        compose.boundedWaitUntil(TIMEOUT) { loaded.get() > 0 }
        runBlocking { withTimeout(TIMEOUT) { viewModel.readMonth(YearMonth.now()) } }
    }

    private companion object {
        /** Several screens at CI's 320x640, and inside the first page of 120. */
        const val ROWS = 60
        const val HOUR = 3_600_000L
        val DAY = my.pinged.data.LocalDate(20_260_907)
    }
}
