package my.pinged.ledger.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.MerchantChoice
import my.pinged.data.dao.MerchantTotal
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.WAITING_FOR_AN_EXPORT
import my.pinged.ui.theme.WAITING_FOR_A_CHECK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import kotlin.coroutines.CoroutineContext

/**
 * The merchant sheet's first open (#284), `design/LoadingSheet.dc.html`: it
 * opens on the tap with the name the row already shows, and every figure the
 * read has not reached is a dash, through `ReadingLayout`.
 *
 * The read is held back in a [MutableStateFlow] and the main clock does not
 * advance on its own, as `SpendingNotReadYetTest` does it.
 */
@RunWith(AndroidJUnit4::class)
class MerchantSheetNotReadYetTest {
    @get:Rule val compose = createComposeRule()

    private val opening = MerchantOpening(OWN_KEY, NAME, LocalDates.of(java.time.LocalDate.of(2026, 9, 14)))
    private val shown = MutableStateFlow(OpenMerchantSheet(opening))

    private val read = MerchantSheetState(
        ownKey = OWN_KEY,
        identityKey = OWN_KEY,
        name = NAME,
        derivedName = NAME,
        txnCount = 11,
        mergedInto = null,
        members = emptyList(),
        others = listOf(MerchantChoice("TNG*GROCERKL", "Village Grocer KL", "Village Grocer KL", 2)),
        suggested = setOf("TNG*GROCERKL"),
        month = YearMonth.of(2026, 9),
        monthTotals = listOf(MerchantTotal(OWN_KEY, NAME, "MYR", 26_815L, 11)),
    )

    private fun draw(heldBy: HeldBy? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                CompositionLocalProvider(LocalHeldBy provides heldBy) {
                    MerchantSheet(shown.collectAsState().value, FakeMerchantActions())
                }
            }
        }
    }

    private val clock = HeldClock(compose)

    private fun at(millis: Long) {
        clock.at(millis)
    }

    private fun texts(): List<String> = clock.texts()

    private fun count(text: String): Int = clock.count(text)

    @Test fun theSheetOpensAtOnceWithTheRowsNameAndNothingElseBeforeTwoHundredMilliseconds() {
        draw()

        at(HeldClock.FRAME)
        assertTrue("The sheet did not open on the tap with the row's name: ${texts()}", count(NAME) >= 1)

        at(READING_DELAY_MILLIS - 40)
        assertEquals("The NAME half was drawn before 200ms", 0, count(NAME_HEADING))
        assertEquals("The SAME SHOP half was drawn before 200ms", 0, count(SAME_SHOP_HEADING))
        assertEquals("A dash was drawn before 200ms", emptyList<String>(), texts().filter { it.contains(NOT_READ_YET) })
    }

    @Test fun afterTwoHundredMillisecondsTheBoardsLayoutWithNoFigure() {
        draw()
        at(READING_DELAY_MILLIS + 40)

        assertEquals("The header's figures were not dashes", 1, count(MONTH_LINE_NOT_READ_YET))
        assertEquals("The NAME heading, known without a read, was not drawn", 1, count(NAME_HEADING))
        assertEquals("The reach was not a dash", 1, count(REACH_NOT_READ_YET))
        assertEquals("The SAME SHOP heading was not drawn", 1, count(SAME_SHOP_HEADING))
        assertEquals("The board's SUGGESTED label was not drawn", 1, count(SUGGESTED))
        compose.onNode(hasText(SAVE_NAME) and hasClickAction()).assertIsNotEnabled()
        assertEquals("The board's sheet draws no status line while nothing holds the database", 0, count(READING))
        assertEquals("A read in flight drew a figure", emptyList<String>(), texts().filter { it.any(Char::isDigit) })
    }

    @Test fun theNameFieldCannotBeTypedIntoBeforeTheRead() {
        draw()
        at(READING_DELAY_MILLIS + 40)
        compose.onNode(hasSetText(), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aReadThatLandsInsideTheDelayIsDrawnAndTheLayoutNever() {
        draw()
        at(READING_DELAY_MILLIS - 100)
        shown.value = OpenMerchantSheet(opening, read)
        at(READING_DELAY_MILLIS + 100)

        assertEquals("The read's own header was not drawn", 1, count("11 PAYMENTS · RM268.15 IN SEPTEMBER"))
        assertEquals("The layout was drawn beside the read", emptyList<String>(), texts().filter { it.contains(NOT_READ_YET) })
    }

    @Test fun aReadThatLandsAfterTheDelayReplacesTheLayout() {
        draw()
        at(READING_DELAY_MILLIS + 40)
        shown.value = OpenMerchantSheet(opening, read)
        at(READING_DELAY_MILLIS + 120)

        assertEquals("The read's own header was not drawn", 1, count("11 PAYMENTS · RM268.15 IN SEPTEMBER"))
        assertEquals("The read's reach was not drawn", 1, count(reach(11)))
        assertEquals("A dash outlived the read", emptyList<String>(), texts().filter { it.contains(NOT_READ_YET) })
    }

    @Test fun anExportHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.Export)
        at(READING_DELAY_MILLIS + 40)

        assertEquals("An export holding the database was not named", 1, count(WAITING_FOR_AN_EXPORT))
        assertEquals("An export was blamed on the integrity check", 0, count(WAITING_FOR_A_CHECK))
    }

    @Test fun anIntegrityCheckHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.IntegrityCheck)
        at(READING_DELAY_MILLIS + 40)

        assertEquals("An integrity check holding the database was not named", 1, count(WAITING_FOR_A_CHECK))
        assertEquals("The check was blamed on an export", 0, count(WAITING_FOR_AN_EXPORT))
    }

    /**
     * The tap publishes the sheet before its read is dispatched: the scope
     * here never runs what is launched on it, so anything [RowActions.openMerchant]
     * left to its coroutine is not drawn.
     */
    @Test fun theTapOpensTheSheetBeforeTheReadRuns() {
        val ran = { error("The read ran on a scope that runs nothing") }
        val rows = RowActions(CoroutineScope(Held), runner = object : ReadRunner {
            override suspend fun <T> read(what: String, body: (PingedDatabase) -> T): Outcome<T> = ran()
            override suspend fun <T> write(what: String, oneTransaction: Boolean, body: suspend (PingedDatabase) -> T): Outcome<T> = ran()
        }, screen = object : RowScreen {
            override fun <R> write(what: String, oneTransaction: Boolean, body: suspend (PingedDatabase) -> R): Deferred<Outcome<R>> = ran()
            override fun refused() = ran()
        })
        rows.openMerchant(opening)

        assertEquals(OpenMerchantSheet(opening, read = null), rows.merchantSheet.value)
        rows.closeMerchant()
        assertNull(rows.merchantSheet.value)
    }

    /** A dispatcher that keeps what it is given and never runs it. */
    private object Held : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private fun hasSetText() = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetText)

    private companion object {
        const val OWN_KEY = "TNG*VILLAGEGROCER"
        const val NAME = "Village Grocer"
        const val MONTH_LINE_NOT_READ_YET = "— PAYMENTS · RM\u2009— IN SEPTEMBER"
        const val REACH_NOT_READ_YET = "— TRANSACTIONS WILL SHOW A NEW NAME"
    }
}
