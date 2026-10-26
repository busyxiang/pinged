package my.pinged.ledger.search

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.data.SearchTotal
import my.pinged.data.dao.FeedRow
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.HERO_NOT_READ_YET
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.monthName
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.WAITING_FOR_AN_EXPORT
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * Search's first read in flight (#238): `design/LoadingSearch`'s layout
 * through `ReadingLayout`, every figure a dash. The read is held back as a
 * null [SearchRead]; the main clock moves only when the test moves it.
 */
@RunWith(AndroidJUnit4::class)
class SearchNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val clock = HeldClock(compose)

    private val month = YearMonth.of(2026, 9)
    private val read = MutableStateFlow<SearchRead?>(null)

    private fun draw(heldBy: HeldBy? = null) = clock.draw(heldBy) {
        SearchScreenContent(
            query = "grab",
            onQuery = {},
            choice = SearchChoice(month = month),
            read = read.collectAsState().value,
            storageUnavailable = false,
            onBack = {},
            onOpenSheet = {},
            onExport = {},
        )
    }

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheLayoutWithNoFigure() {
        draw()

        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, clock.count(READING))
        assertEquals("A dash was drawn before 200ms", 0, clock.texts().count { NOT_READ_YET in it })

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed: ${clock.texts()}", 1, clock.count(READING))
        assertEquals(
            "The hero's label was not the count dashed over the month picked: ${clock.texts()}",
            1,
            clock.count("$NOT_READ_YET PAYMENTS · ${monthName(month)}"),
        )
        assertEquals("The total was not the hero's dash", 1, clock.count(HERO_NOT_READ_YET))
        assertEquals("No row drew its amount as a dash", SEARCH_PLACES, clock.count(NOT_READ_YET))
        assertEquals("A read in flight drew a figure", emptyList<String>(), clock.figures(monthName(month), "$NOT_READ_YET PAYMENTS · ${monthName(month)}"))
    }

    @Test fun anExportHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.Export)
        clock.at(READING_DELAY_MILLIS + 40)

        assertEquals("An export holding the database was not named", 1, clock.count(WAITING_FOR_AN_EXPORT))
        assertEquals("READING was drawn beside the export's wording", 0, clock.count(READING))
    }

    @Test fun nothingFoundBeforeTwoHundredMillisecondsSaysSoAtOnce() {
        draw()
        clock.at(48)

        read.value = SearchRead(
            text = "grab",
            choice = SearchChoice(month = month),
            rows = emptyList(),
            totals = emptyList(),
            chooser = ChooserRead(emptyList(), null),
        )
        clock.at(READING_DELAY_MILLIS - 40)

        assertEquals("Nothing found at 48ms waited for the delay", 1, clock.count(nothingFound(SearchChoice(month = month))))
    }

    @Test fun rowsLandingAfterTheLayoutReplaceIt() {
        draw()
        clock.at(READING_DELAY_MILLIS + 40)

        read.value = SearchRead(
            text = "grab",
            choice = SearchChoice(month = month),
            rows = listOf(ledgerTxn(1_840L, id = 1L)).map { FeedRow(it, it.merchantKey, "Grab") },
            totals = listOf(SearchTotal("MYR", rowCount = 1, countedCount = 1, paymentCount = 1, netSen = 1_840L)),
            chooser = ChooserRead(emptyList(), null),
        )
        clock.at(READING_DELAY_MILLIS + 80)

        assertEquals("The status line outlived the read", 0, clock.count(READING))
        assertEquals("The landed hero was not drawn", 1, clock.count("1 PAYMENT · ${monthName(month)}"))
    }
}
