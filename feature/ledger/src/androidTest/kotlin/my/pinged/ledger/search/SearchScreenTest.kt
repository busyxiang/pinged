package my.pinged.ledger.search

import android.app.Application
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.Databases
import my.pinged.ledger.settings.ExportSink
import my.pinged.ledger.settings.Transfers
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.data.entity.PendingReason
import my.pinged.parse.Direction
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.entry.EditTarget
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.monthName
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import java.time.ZoneId

/**
 * Search (#154) against a real database through its real holder: what the
 * words and each chip narrow the rows to, and what the hero then says. Every
 * expected figure is worked out by hand in the comment beside it.
 */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test fun wordsNarrowTheRowsAndTheHeroCountsWhatIsLeftInTheMonth() {
        seed()
        screen()

        // Opens on this month, no words: three rides and a coffee, not last
        // month's ride. 18.40 + 22.10 + 9.80 + 6.50 = 56.80.
        waitForCount("4 PAYMENTS · $THIS_MONTH")
        compose.onNodeWithTag(SEARCH_TOTAL).assertTextEquals("RM56.80")
        compose.onNodeWithText(KOPI).assertExists()

        compose.onNodeWithTag(SEARCH_FIELD).performTextInput("gra")

        // 18.40 + 22.10 + 9.80 = 50.30; 50.30 / 3 = 16.7666.. -> 16.77.
        waitForCount("3 PAYMENTS · $THIS_MONTH")
        compose.onNodeWithTag(SEARCH_TOTAL).assertTextEquals("RM50.30")
        compose.onNodeWithText("RM16.77 A PAYMENT").assertExists()
        compose.onNodeWithText(KOPI).assertDoesNotExist()
    }

    @Test fun anyMonthWidensTheSearchToAllTimeAndSaysSo() {
        seed()
        screen()
        compose.onNodeWithTag(SEARCH_FIELD).performTextInput("grab")
        waitForCount("3 PAYMENTS · $THIS_MONTH")

        compose.onNodeWithTag(CHIP + THIS_MONTH).performClick()
        compose.onNodeWithText("Any month").performScrollTo().performClick()

        // Last month's 15.00 joins: 50.30 + 15.00 = 65.30.
        waitForCount("4 PAYMENTS · ALL TIME")
        compose.onNodeWithTag(SEARCH_TOTAL).assertTextEquals("RM65.30")
        compose.onNodeWithTag(CHIP + MONTH_CHIP).assertExists()
    }

    @Test fun theCategoryAppAndAmountChipsEachNarrow() {
        seed()
        screen()
        waitForCount("4 PAYMENTS · $THIS_MONTH")

        compose.onNodeWithTag(CHIP + CATEGORY_CHIP).performClick()
        compose.onNodeWithTag(PICK + FOOD).performScrollTo().performClick()
        // The coffee alone.
        waitForCount("1 PAYMENT · $THIS_MONTH")
        compose.onNodeWithTag(CHIP + FOOD.uppercase()).performClick()
        compose.onNodeWithTag(PICK + ANY_CATEGORY).performScrollTo().performClick()
        waitForCount("4 PAYMENTS · $THIS_MONTH")

        compose.onNodeWithTag(CHIP + APP_CHIP).performClick()
        compose.onNodeWithTag(PICK + GRAB_LABEL).performScrollTo().performClick()
        // The three rides; the coffee was paid through Touch 'n Go.
        waitForCount("3 PAYMENTS · $THIS_MONTH")

        compose.onNodeWithTag(CHIP + AMOUNT_CHIP).performClick()
        compose.onNodeWithContentDescription(FROM_RM).performTextInput("10")
        compose.onNodeWithText(APPLY).performScrollTo().performClick()
        // 18.40 and 22.10; the 9.80 ride is under RM10.
        waitForCount("2 PAYMENTS · $THIS_MONTH")
        compose.onNodeWithTag(SEARCH_TOTAL).assertTextEquals("RM40.50")
        compose.onNodeWithTag(CHIP + "FROM RM10.00").assertExists()
    }

    @Test fun aRowTheTotalLeavesOutIsDrawnAndSaidToBeLeftOut() {
        seed()
        dao().insert(ride(5_000, THIS, merchant = GRAB).copy(state = TxnState.PENDING, pendingReason = PendingReason.OVER_THRESHOLD))
        screen()
        compose.onNodeWithTag(SEARCH_FIELD).performTextInput("grab")

        waitForCount("3 PAYMENTS · $THIS_MONTH")
        compose.onNodeWithTag(SEARCH_TOTAL).assertTextEquals("RM50.30")
        compose.onNodeWithText("1 MORE NOT IN THE TOTAL").assertExists()
        compose.onNodeWithText("AWAITING REVIEW").performScrollTo()
    }

    @Test fun aRefundLowersTheTotalAndIsNotAPayment() {
        seed()
        dao().insert(ride(500, THIS.plusHours(4), merchant = GRAB).copy(direction = Direction.REFUND))
        screen(onPickExport = {})
        compose.onNodeWithTag(SEARCH_FIELD).performTextInput("grab")

        // Three rides less a RM5.00 refund: 50.30 - 5.00 = 45.30, over three
        // payments 15.10.
        waitForCount("3 PAYMENTS · $THIS_MONTH")
        compose.onNodeWithTag(SEARCH_TOTAL).assertTextEquals("RM45.30")
        compose.onNodeWithText("RM15.10 A PAYMENT").assertExists()
        // Three payments and the refund are the four rows the button writes (#216).
        compose.onNodeWithText("1 REFUND IN THE TOTAL").assertExists()
        compose.onNodeWithText("EXPORT THESE 4 ROWS").assertExists()
    }

    @Test fun aCapturedRowOpensItsDetailAndAManualEntryItsEditScreen() {
        context.freshLedger()
        val captured = dao().insert(ride(1_840, THIS, merchant = GRAB))
        val manual = dao().insert(ride(650, THIS, merchant = KOPI).copy(rawCaptureId = null))
        var detail: EditTarget? = null
        var entry: EditTarget? = null
        screen(onOpenDetail = { detail = it }, onOpenEntry = { entry = it })
        waitForCount("2 PAYMENTS · $THIS_MONTH")

        compose.onNodeWithText(GRAB).performScrollTo().performClick()
        compose.onNodeWithText(KOPI).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(EditTarget(captured, dao().byId(captured)!!.createdAt), detail)
        assertEquals(EditTarget(manual, dao().byId(manual)!!.createdAt), entry)
    }

    @Test fun wordsThatStartNothingSayNothingWasFoundInTheMonth() {
        seed()
        screen()
        // "rab" is inside "Grab" and starts no word: spec 15.2's prefix match.
        compose.onNodeWithTag(SEARCH_FIELD).performTextInput("rab")

        val said = runCatching {
            compose.boundedWaitUntil(TIMEOUT) {
                compose.onAllNodes(hasTestTag(SEARCH_NOTICE) and hasText("NOTHING FOUND IN $THIS_MONTH"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        assertTrue("Never said nothing was found", said)
    }

    /**
     * Words typed inside the first debounce window, after the screen's own
     * read has landed, are still read. The debounce swallows the opening
     * empty words when others follow within [SearchViewModel.DEBOUNCE_MS],
     * so the first question the holder's collector sees can already be the
     * typed one, and must not be skipped as the opening question. Skips
     * itself when the words land outside that window, where it tests
     * nothing.
     */
    @Test fun wordsTypedWhileTheFirstReadIsDebouncedAreRead() {
        seed()
        val opened = System.nanoTime()
        val viewModel = SearchViewModel(context.applicationContext as Application, SavedStateHandle())
        // The screen's resume read, of no words.
        runBlocking { viewModel.refresh().join() }
        viewModel.setQuery("grab")
        val typedAfterMs = (System.nanoTime() - opened) / 1_000_000
        assumeTrue("typed ${typedAfterMs}ms in, outside the window", typedAfterMs < SearchViewModel.DEBOUNCE_MS)

        val read = runBlocking { withTimeoutOrNull(TIMEOUT) { viewModel.read.first { it?.text == "grab" } } }
        assertTrue("the words typed ${typedAfterMs}ms in were never read", read != null)
    }

    /**
     * "Export these N rows" counts what the totals count -- every row found,
     * not the [SearchViewModel.ROW_LIMIT] drawn -- and the sheet it opens
     * offers that same number before handing to the picker (#201).
     */
    @Test fun theExportCountsEveryRowFoundNotOnlyTheRowsDrawn() {
        val found = SearchViewModel.ROW_LIMIT + 5
        context.freshLedger()
        repeat(found) { dao().insert(ride(100L + it, THIS + it, merchant = GRAB)) }
        var picked = 0
        screen(onPickExport = { picked++ })

        val footer = "EXPORT THESE $found ROWS"
        assertTrue(
            "Waited ${TIMEOUT}ms for '$footer'",
            runCatching { compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodesWithText(footer).fetchSemanticsNodes().isNotEmpty() } }.isSuccess,
        )
        // Pinned below the list, outside any scroll: nothing to scroll to.
        compose.onNodeWithText(footer).performClick()
        compose.onNodeWithText("Export $found rows").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("the sheet's button did not reach the picker", 1, picked)
    }

    /**
     * A spreadsheet the Settings sheet made, settled and not yet dismissed,
     * is not this search's: its sheet still offers its own export rather
     * than another file's "SAVED".
     */
    @Test fun anotherSheetsSpreadsheetIsNotThisSearchsResult() {
        seed()
        val target = File(context.cacheDir, "settings-export.csv").apply { delete() }
        try {
            val sink = object : ExportSink {
                override fun open(): OutputStream = target.outputStream()
                override fun delete(): Boolean = target.delete()
            }
            val answer = runBlocking {
                Transfers.spreadsheet(context, sink, "", my.pinged.data.SearchFilter(), kotlinx.coroutines.Job()).await()
            }
            assertTrue("the other export did not settle: $answer", answer is Transfers.Answer.Settled)
            screen(onPickExport = {})
            val footer = "EXPORT THESE 4 ROWS"
            assertTrue(
                "Waited ${TIMEOUT}ms for '$footer'",
                runCatching { compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodesWithText(footer).fetchSemanticsNodes().isNotEmpty() } }.isSuccess,
            )
            // Pinned below the list, outside any scroll: nothing to scroll to.
            compose.onNodeWithText(footer).performClick()

            compose.onNodeWithText("Export 4 rows").performScrollTo().assertExists()
            compose.onNodeWithText("SAVED", substring = true).assertDoesNotExist()
        } finally {
            target.delete()
            Transfers.forgetOutcome()
        }
    }

    /**
     * The file holds the search on screen: its words and its month, so last
     * month's ride and this month's coffee stay out of it.
     */
    @Test fun theExportWritesTheRowsTheSearchFound() {
        seed()
        val viewModel = SearchViewModel(context.applicationContext as Application, SavedStateHandle())
        viewModel.setQuery("grab")
        runBlocking { viewModel.refresh().join() }
        val target = File(context.cacheDir, "search-export.csv").apply { delete() }
        try {
            val sink = object : ExportSink {
                override fun open(): OutputStream = target.outputStream()
                override fun delete(): Boolean = target.delete()
            }
            runBlocking { viewModel.exportTo(sink).join() }

            val lines = target.readText().split("\r\n").filter { it.isNotEmpty() }
            // The header and this month's three rides, newest first:
            // 9.80, 22.10, 18.40.
            assertEquals(listOf("9.80", "22.10", "18.40"), lines.drop(1).map { it.split(',')[1] })
        } finally {
            target.delete()
            Transfers.forgetOutcome()
        }
    }

    // ---- fixture ---------------------------------------------------------

    private fun dao() = Databases.txnDao(context)

    /**
     * This month: three Grab rides and a coffee filed under Food. Last
     * month: one more ride, which only an any-month search finds.
     */
    private fun seed() {
        context.freshLedger()
        dao().insert(ride(1_840, THIS, merchant = GRAB))
        dao().insert(ride(2_210, THIS.plusHours(1), merchant = GRAB))
        dao().insert(ride(980, THIS.plusHours(2), merchant = "GrabCar"))
        dao().insert(
            ride(650, THIS.plusHours(3), merchant = KOPI).copy(
                categoryId = Databases.categoryDao(context).all().single { it.name == FOOD }.id,
                sourcePackage = TNG,
                sourceLabel = "Touch 'n Go eWallet",
            ),
        )
        dao().insert(ride(1_500, LAST, merchant = GRAB))
    }

    private fun ride(amountSen: Long, at: Long, merchant: String): Txn =
        ledgerTxn(
            amountSen = amountSen,
            occurredAt = at,
            categoryId = Databases.categoryDao(context).requireUncategorizedId(),
        ).copy(
            merchantDisplay = merchant,
            merchantRaw = merchant.uppercase(),
            merchantKey = merchant.uppercase(),
            sourcePackage = GRAB_APP,
            sourceLabel = GRAB_LABEL,
        )

    private fun screen(
        onOpenDetail: (EditTarget) -> Unit = {},
        onOpenEntry: (EditTarget) -> Unit = {},
        onPickExport: (() -> Unit)? = null,
    ) {
        val viewModel = SearchViewModel(context.applicationContext as Application, SavedStateHandle())
        compose.setContent {
            PingedTheme {
                SearchScreen(viewModel, onBack = {}, onOpenEntry = onOpenEntry, onOpenDetail = onOpenDetail, onPickExport = onPickExport)
            }
        }
    }

    private fun waitForCount(text: String) {
        val appeared = runCatching {
            compose.boundedWaitUntil(TIMEOUT) {
                compose.onAllNodesWithTag(SEARCH_COUNT).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasTestTag(SEARCH_COUNT) and hasText(text)).fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        assertTrue("Waited ${TIMEOUT}ms for the hero to say '$text'", appeared)
    }

    private fun Long.plusHours(hours: Int): Long = this + hours * 3_600_000L

    private companion object {
        val MONTH: YearMonth = YearMonth.now()
        val THIS_MONTH: String = monthName(MONTH)

        /** Noon on the 1st, so the hours added stay inside the month. */
        val THIS: Long = MONTH.atDay(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val LAST: Long = MONTH.minusMonths(1).atDay(10).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        const val GRAB = "Grab"
        const val KOPI = "Kopi Pagi"
        const val FOOD = "Food & Drinks"
        const val GRAB_APP = "com.grabtaxi.passenger"
        const val GRAB_LABEL = "Grab Superapp"
        const val TNG = "my.com.tngdigital.ewallet"
    }
}
