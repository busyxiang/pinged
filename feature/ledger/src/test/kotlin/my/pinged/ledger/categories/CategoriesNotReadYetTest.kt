package my.pinged.ledger.categories

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.data.dao.CategoryUse
import my.pinged.data.entity.Category
import my.pinged.ledger.home.NEW_CATEGORY
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.WAITING_FOR_AN_EXPORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Categories while its read is in flight (#283): `design/LoadingList.dc.html`. */
@RunWith(AndroidJUnit4::class)
class CategoriesNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val held = HeldClock(compose)
    private val state = MutableStateFlow(CategoriesState())

    private fun draw(heldBy: HeldBy? = null) = held.draw(heldBy) {
        CategoriesContent(
            state = state.collectAsState().value,
            onBack = {}, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onDelete = {},
        )
    }

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheListsLayoutWithNoFigure() {
        draw()

        held.at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, held.count(READING))
        assertEquals("A placeholder row was drawn before 200ms", 0, held.count(ROW_CAPTION))
        assertEquals("The title, known without a read, was not drawn at once", 1, held.count(CATEGORIES))

        held.at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, held.count(READING))
        assertEquals("The header's count was not a dash", 1, held.count("$NOT_READ_YET · MOST USED FIRST"))
        assertTrue("No placeholder rows: ${held.texts()}", held.count(ROW_CAPTION) >= 3)
        assertEquals("A read in flight drew a figure", emptyList<String>(), held.figures())
        assertEquals("New category was offered before the list was read", 0, held.count(NEW_CATEGORY))
    }

    @Test fun anExportHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.Export)
        held.at(READING_DELAY_MILLIS + 40)

        assertEquals("An export holding the database was not named", 1, held.count(WAITING_FOR_AN_EXPORT))
        assertEquals("READING was drawn beside the export's wording", 0, held.count(READING))
    }

    @Test fun anUnreadableListLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        held.at(48)

        state.value = CategoriesState(loaded = true, storageUnavailable = true)
        held.at(READING_DELAY_MILLIS - 40)

        assertEquals("An unreadable list found at 48ms waited for the delay", 1, held.count(CANNOT_READ_YOUR_DATA))
        assertEquals("The placeholder rows outlived the read", 0, held.count(ROW_CAPTION))
    }

    @Test fun aListLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        held.at(48)

        state.value = CategoriesState(loaded = true, items = listOf(CategoryUse(Category(1, "Food & Drinks", "utensils", 0, seedName = null), 3, 0)))
        held.at(READING_DELAY_MILLIS - 40)

        assertEquals("A list read at 48ms waited for the delay", 1, held.count("Food & Drinks"))
    }

    private companion object {
        const val ROW_CAPTION = "$NOT_READ_YET TRANSACTIONS"
    }
}
