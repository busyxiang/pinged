package my.pinged.ledger.day

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.SOURCE_LABEL
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.home.CATEGORY_CHIP
import my.pinged.ledger.home.EDIT_MERCHANT
import my.pinged.ledger.home.NAME_FIELD_TAG
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ledger.home.SAVE_NAME
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.ExclusionReason
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * The `Day` screen over the real database, through its own holder (#69): the
 * rows the Ledger would draw for the day, the header's lines, and the two
 * offers a row makes.
 *
 * Dated relative to the device's today, because the screen reads its own
 * clock: [DAY] is three days ago, and the history starts ten days ago, so
 * whether [DAY] is "not captured" is decided by whether it is bound.
 */
@RunWith(AndroidJUnit4::class)
class DayScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test fun excludedRowsAreStruckThroughPendingRowsBadgedAndTheKeptOutLineSaysHowMuch() {
        val dao = seed(bindDay = true)
        dao.insert(txn(5_000L))
        dao.insert(txn(10_000L, isExcluded = true, exclusionReason = ExclusionReason.TRANSFER))
        dao.insert(txn(700L, state = TxnState.PENDING, pendingReason = PendingReason.RULE_REVIEW))
        // The day after: not this day's row.
        dao.insert(txn(999L, day = DAY.plusDays(1)))

        screen()

        waitFor("RM107.00 kept out of the total: 1 excluded, 1 pending", "The kept-out line is not drawn")
        assertEquals("The day's counted total is not the one counted row", 1, count("RM50.00"))
        assertEquals("The excluded row is not badged", 1, count("NOT SPENDING · TRANSFER"))
        assertEquals("The pending row is not badged with its reason", 1, count("PENDING · RULE REVIEW"))
        assertEquals("Another day's row is on this day", 0, count("9.99"))
        assertEquals("A captured day said Pinged was not watching", 0, count(NOT_WATCHING))
        assertEquals(
            "The excluded amount is not struck through",
            TextDecoration.LineThrough,
            decorationOf("100.00"),
        )
        assertTrue("A counted amount is struck through", decorationOf("50.00") != TextDecoration.LineThrough)
    }

    @Test fun theKeptOutLineIsAbsentWhenNothingIsKeptOut() {
        val dao = seed(bindDay = true)
        dao.insert(txn(5_000L))

        screen()

        waitFor("RM50.00", "The day's total is not drawn")
        assertEquals(
            "A day with nothing kept out drew a kept-out line",
            0,
            compose.onAllNodes(hasText("kept out of the total", substring = true)).fetchSemanticsNodes().size,
        )
    }

    @Test fun aDayPingedWasNotWatchingWithNoRowsSaysOnlyThat() {
        seed(bindDay = false)

        screen()

        waitFor(NOT_WATCHING, "A not-captured day does not say Pinged was not watching")
        assertEquals(
            "A not-captured day with no rows drew an amount, which reads as nothing spent",
            0,
            compose.onAllNodes(hasText("RM", substring = true)).fetchSemanticsNodes().size,
        )
    }

    @Test fun aDayPingedWasNotWatchingStillListsWhatItHas() {
        val dao = seed(bindDay = false)
        // A row with no capture behind it, as an import or a hand entry is:
        // it does not make the day captured.
        dao.insert(txn(1_200L))

        screen()

        waitFor(NOT_WATCHING, "A not-captured day with a row does not say Pinged was not watching")
        assertEquals("The day's row is not listed", 1, count("12.00"))
        assertEquals("The day's total is not drawn", 1, count("RM12.00"))
    }

    @Test fun theChipAssignsACategoryFromDay() {
        val dao = seed(bindDay = true)
        val uncategorized = Databases.categoryDao(context).uncategorizedIdOrNull()!!
        val id = dao.insert(txn(5_000L, categoryId = uncategorized))
        val food = Databases.categoryDao(context).all().single { it.name == FOOD }.id

        screen()

        waitFor(CATEGORY_CHIP, "The uncategorized row offers no chip")
        compose.onNodeWithText(CATEGORY_CHIP).performScrollTo().performClick()
        waitFor(PICKER_HEADING, "The chip did not open the chooser")
        // The categories are the sheet's `LazyColumn`, which a short screen
        // can push a row of below. Save sits under it, in no scroll, so
        // `performScrollTo` fails on it ("no parent layout with a Scroll").
        compose.onNodeWithText(FOOD).performScrollTo().performClick()
        compose.onNodeWithText(SAVE_LABEL).performClick()

        waitFor("$FOOD · $SOURCE_LABEL", "The row did not redraw in the category it was saved to")
        assertEquals(food, Databases.txnDao(context).byId(id)!!.categoryId)
    }

    @Test fun theMerchantSheetRenamesFromDay() {
        val dao = seed(bindDay = true)
        dao.insert(txn(5_000L))

        screen()

        waitFor(MERCHANT, "The row is not drawn")
        compose.onNode(
            SemanticsMatcher("offers $EDIT_MERCHANT") {
                it.config.getOrElseNullable(SemanticsActions.OnLongClick) { null }?.label == EDIT_MERCHANT
            },
        ).performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor(SAVE_NAME, "The long press did not open the merchant sheet")
        compose.onNodeWithTag(NAME_FIELD_TAG, useUnmergedTree = true)
            .onChildren()
            .filterToOne(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performTextReplacement(RENAMED)
        compose.onNodeWithText(SAVE_NAME).performSemanticsAction(SemanticsActions.OnClick)

        waitFor(RENAMED, "The row did not redraw under the merchant's new name")
        assertEquals("The old name is still drawn", 0, count(MERCHANT))
    }

    // ---- fixtures --------------------------------------------------------

    /** A fresh ledger whose history starts ten days ago, with [DAY] bound or not. */
    private fun seed(bindDay: Boolean): TxnDao {
        val dao = context.freshLedger()
        val days = Databases.captureDayDao(context)
        days.recordListenerBound(LocalDates.of(TODAY.minusDays(10)), true)
        if (bindDay) days.recordListenerBound(LocalDates.of(DAY), true)
        return dao
    }

    private fun txn(
        amountSen: Long,
        day: LocalDate = DAY,
        categoryId: Long = 1L,
        state: TxnState = TxnState.COMMITTED,
        pendingReason: PendingReason? = null,
        isExcluded: Boolean = false,
        exclusionReason: ExclusionReason? = null,
    ) = ledgerTxn(
        amountSen = amountSen,
        occurredAt = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        categoryId = categoryId,
        state = state,
        pendingReason = pendingReason,
        isExcluded = isExcluded,
        exclusionReason = exclusionReason,
    )

    private fun screen() {
        val viewModel = DayViewModel(context.applicationContext as Application, DAY)
        compose.setContent { PingedTheme { DayScreen(viewModel, onBack = {}) } }
    }

    private fun count(text: String): Int = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun waitFor(text: String, why: String) {
        val appeared = runCatching { compose.waitUntil(TIMEOUT) { count(text) > 0 } }.isSuccess
        assertTrue("$why -- waited ${TIMEOUT}ms for '$text'", appeared)
    }

    /** The decoration the amount [text] is laid out with. */
    private fun decorationOf(text: String): TextDecoration? {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.textDecoration
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.now()
        val DAY: LocalDate = TODAY.minusDays(3)
        const val FOOD = "Food & Drinks"
        const val RENAMED = "Pak Ali Corner"
    }
}
