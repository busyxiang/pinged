package my.pinged.ledger.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.data.SearchTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.UPDATING
import my.pinged.ui.theme.WAITING_FOR_AN_EXPORT
import my.pinged.ui.theme.updatingFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * Search re-reading in place while the user types (#285), `design/LoadingSearch`:
 * the last answer stays, `UPDATING FOR "GRAB F"` under its hero once the
 * re-read has run 200ms, and the export's count a dash. The 45% dim is a
 * pixel, so `SearchUpdatingDimTest` asserts it on a device.
 *
 * The re-read is held back by never handing the screen a read for the words
 * typed; the main clock does not advance on its own.
 */
@RunWith(AndroidJUnit4::class)
class SearchUpdatingTest {
    @get:Rule val compose = createComposeRule()

    private val month = YearMonth.of(2026, 9)
    private val groceries = Category(2, "Groceries", "shopping-basket", 1, seedName = "Groceries")

    private fun read(text: String) = SearchRead(
        text = text,
        choice = SearchChoice(month = month),
        rows = listOf(ledgerTxn(1_840L, id = 1L), ledgerTxn(2_210L, id = 2L)).map { FeedRow(it, it.merchantKey, "Grab ${it.id}") },
        totals = listOf(SearchTotal("MYR", rowCount = 18, countedCount = 18, paymentCount = 18, netSen = 31_240L)),
        chooser = ChooserRead(listOf(groceries), null),
    )

    private val query = MutableStateFlow("grab")
    private val shown = MutableStateFlow<SearchRead?>(read("grab"))

    private fun draw(heldBy: HeldBy? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                CompositionLocalProvider(LocalHeldBy provides heldBy) {
                    val words = query.collectAsState().value
                    val last = shown.collectAsState().value
                    SearchScreenContent(
                        query = words,
                        onQuery = {},
                        choice = SearchChoice(month = month),
                        read = last,
                        // What `SearchScreen` is told by its read: words not yet answered.
                        rereading = last != null && last.text != words,
                        storageUnavailable = false,
                        onBack = {},
                        onOpenSheet = {},
                        categories = FakeCategoryActions(),
                        onExport = {},
                    )
                }
            }
        }
        at(HeldClock.FRAME)
    }

    private val clock = HeldClock(compose) { now -> if (updating() > 0) updatingAt += now }

    /** The frames, since the words changed, at which a status line was drawn. */
    private val updatingAt = mutableListOf<Long>()

    private fun at(millis: Long) {
        clock.at(millis)
    }

    private fun type(words: String) {
        query.value = words
        clock.restart()
    }

    private fun texts(): List<String> = clock.texts()

    private fun count(text: String): Int = clock.count(text)

    private fun updating() = texts().count { it.startsWith(UPDATING) }

    @Test fun typedWordsKeepTheLastAnswerAndSayUpdatingOnceTheReReadHasRun200ms() {
        draw()
        assertEquals("The settled answer drew a status line", 0, updating())
        assertEquals("The settled answer's export did not count its rows", 1, count("EXPORT THESE 18 ROWS"))

        type("grab f")
        at(READING_DELAY_MILLIS - 40)
        assertEquals("UPDATING was drawn before 200ms", 0, updating())
        assertEquals("The export's count was dashed before 200ms", 1, count("EXPORT THESE 18 ROWS"))

        at(READING_DELAY_MILLIS + 40)
        assertEquals("No UPDATING naming the words typed: ${texts()}", 1, count(updatingFor("grab f")))
        assertEquals("The re-read went back to READING", 0, count(READING))
        assertEquals("The last answer's rows were not kept", 1, count("Grab 1"))
        assertEquals("The last answer's hero was not kept", 1, count("18 PAYMENTS · SEPTEMBER 2026"))
        assertEquals("The export's count, about to change, is not a dash: ${texts()}", 1, count("EXPORT THESE $NOT_READ_YET ROWS"))
    }

    @Test fun aReReadThatLandsInside200msNeverSaysUpdating() {
        draw()

        type("grab f")
        at(READING_DELAY_MILLIS - 60)
        shown.value = read("grab f")
        at(READING_DELAY_MILLIS * 3)

        assertEquals("A re-read that landed inside 200ms drew UPDATING, at these ms", emptyList<Long>(), updatingAt)
        assertEquals("The landed answer's export did not count its rows", 1, count("EXPORT THESE 18 ROWS"))
    }

    @Test fun theStatusLineGoesOnceTheReReadLands() {
        draw()

        type("grab f")
        at(READING_DELAY_MILLIS + 40)
        shown.value = read("grab f")
        at(READING_DELAY_MILLIS + 80)

        assertEquals("UPDATING outlived the read it waited for", 0, updating())
    }

    @Test fun aReReadBehindAnExportNamesTheExport() {
        draw(heldBy = HeldBy.Export)

        type("grab f")
        at(READING_DELAY_MILLIS + 40)

        assertEquals("A re-read behind an export did not name it: ${texts()}", 1, count(WAITING_FOR_AN_EXPORT))
        assertEquals("The last answer's rows were not kept behind an export", 1, count("Grab 1"))
    }

    @Test fun clearingTheWordsSaysUpdatingWithNoWords() {
        draw()

        type("")
        at(READING_DELAY_MILLIS + 40)

        assertEquals("Updating for no words did not draw the bare status: ${texts()}", 1, count(UPDATING))
    }
}
