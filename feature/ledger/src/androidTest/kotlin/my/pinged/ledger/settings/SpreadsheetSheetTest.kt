package my.pinged.ledger.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.OutputStream
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.ExclusionReason
import my.pinged.ui.CALENDAR
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The export sheet's spreadsheet card (#201, `design/Export.dc.html`): it is
 * not what the sheet opens on, its HOW MUCH and WHAT TO LEAVE OUT reach the
 * choice, and the number on its button is the number of rows the file holds.
 */
@RunWith(AndroidJUnit4::class)
class SpreadsheetSheetTest {
    @get:Rule val compose = createComposeRule()

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Before fun startClean() = Transfers.forgetOutcome()

    @After fun leaveNothingBehind() {
        Transfers.forgetOutcome()
        app.discardTheDatabase()
    }

    @Test fun theSheetOpensOnTheBackupAndOffersTheSpreadsheetsChoicesOnItsCard() {
        val chosen = mutableListOf<SpreadsheetChoice>()
        var picked = 0
        val choice = SpreadsheetChoice()
        draw(
            SpreadsheetPanel(
                choice = choice,
                counts = SpreadsheetCounts(choice, rows = 57, excluded = 2, pending = 3),
                onChoose = { chosen += it },
                onPick = { picked++ },
            ),
        )

        // The backup card is chosen, so none of the spreadsheet's controls.
        compose.onNodeWithText("HOW MUCH").assertDoesNotExist()
        compose.onNodeWithText("Export").assertExists()

        compose.onNodeWithText(SPREADSHEET).performScrollTo().performClick()
        compose.onNodeWithText("HOW MUCH").performScrollTo().assertExists()
        compose.onNodeWithText(YearMonth.now().month.getDisplayName(TextStyle.FULL, CALENDAR)).assertExists()
        compose.onNodeWithText("The 3 still awaiting review").performScrollTo().assertExists()
        compose.onNodeWithText("2 ROWS · TRANSFERS, RELOADS AND NOT SPENDING").performScrollTo().assertExists()

        compose.onNodeWithText(LEFT_OUT_OF_TOTALS).performScrollTo().performClick()
        compose.onNodeWithText(EVERYTHING_SO_FAR).performScrollTo().performClick()
        compose.onNodeWithText("Export 57 rows").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(
            listOf(choice.copy(leaveOutExcluded = true), choice.copy(month = null)),
            chosen,
        )
        assertEquals("the button did not reach the spreadsheet's picker", 1, picked)
    }

    /**
     * A sheet left open past a month's end still names the month it will
     * write, chosen, rather than this month's name over last month's rows.
     */
    @Test fun theMonthChipNamesTheMonthChosen() {
        val last = YearMonth.now().minusMonths(1)
        draw(SpreadsheetPanel(choice = SpreadsheetChoice(month = last), counts = null, onChoose = {}, onPick = {}))
        compose.onNodeWithText(SPREADSHEET).performScrollTo().performClick()

        compose.onNodeWithText(last.month.getDisplayName(TextStyle.FULL, CALENDAR)).performScrollTo().assertIsSelected()
    }

    /** A count made for another choice is not this one's, and draws no number. */
    @Test fun aCountForAnotherChoiceIsNotDrawnOnTheButton() {
        val choice = SpreadsheetChoice()
        draw(
            SpreadsheetPanel(
                choice = choice.copy(leaveOutPending = true),
                counts = SpreadsheetCounts(choice, rows = 112, excluded = 3, pending = 4),
                onChoose = {},
                onPick = {},
            ),
        )
        compose.onNodeWithText(SPREADSHEET).performScrollTo().performClick()

        compose.onNodeWithText("Export 112 rows").assertDoesNotExist()
        compose.onNodeWithText("Export").performScrollTo().assertExists()
    }

    /**
     * Through the holder, against the shipped database: the counts for a
     * month, worked out by hand, and then the file written for the same
     * choice holding exactly the rows the button promised.
     */
    @Test fun theButtonsCountIsTheRowsTheFileHolds() {
        val dao = app.freshLedger()
        val category = Databases.categoryDao(app).requireUncategorizedId()
        // This month: two plain rows, a transfer left out of the totals, and
        // one awaiting review. Last month: one more, outside the range.
        dao.insert(ledgerTxn(100, occurredAt = THIS, categoryId = category, rawCaptureId = null))
        dao.insert(ledgerTxn(200, occurredAt = THIS + 1, categoryId = category, rawCaptureId = null))
        dao.insert(
            ledgerTxn(
                300, occurredAt = THIS + 2, categoryId = category, rawCaptureId = null,
                isExcluded = true, exclusionReason = ExclusionReason.TRANSFER,
            ),
        )
        dao.insert(
            ledgerTxn(
                400, occurredAt = THIS + 3, categoryId = category, rawCaptureId = null,
                state = TxnState.PENDING, pendingReason = PendingReason.RULE_REVIEW,
            ),
        )
        dao.insert(ledgerTxn(500, occurredAt = LAST, categoryId = category, rawCaptureId = null))
        val viewModel = SettingsViewModel(app)
        val choice = SpreadsheetChoice(month = YearMonth.now(), leaveOutExcluded = true)

        runBlocking { viewModel.chooseSpreadsheet(choice).join() }

        // Four this month; leaving out the transfer leaves three.
        assertEquals(SpreadsheetCounts(choice, rows = 3, excluded = 1, pending = 1), viewModel.state.value.spreadsheetCounts)

        val target = File(app.cacheDir, "sheet-count.csv").apply { delete() }
        try {
            val sink = object : ExportSink {
                override fun open(): OutputStream = target.outputStream()
                override fun delete(): Boolean = target.delete()
            }
            runBlocking { viewModel.spreadsheetTo(sink).join() }
            val amounts = target.readText().split("\r\n").filter { it.isNotEmpty() }.drop(1).map { it.split(',')[1] }
            assertEquals(listOf("4.00", "2.00", "1.00"), amounts)
        } finally {
            target.delete()
        }
    }

    private fun draw(panel: SpreadsheetPanel) {
        compose.setContent {
            PingedTheme {
                // Scrollable, as `ReceiptSheet` is, for `performScrollTo`.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ExportSheet(SettingsState(loaded = true), onPick = {}, onCancel = {}, onDismiss = {}, spreadsheet = panel)
                }
            }
        }
    }

    private companion object {
        val THIS: Long = YearMonth.now().atDay(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val LAST: Long = YearMonth.now().minusMonths(1).atDay(10).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
