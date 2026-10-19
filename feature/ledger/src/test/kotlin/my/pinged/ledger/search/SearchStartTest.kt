package my.pinged.ledger.search

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

/**
 * A search opened from `N TO SORT` or Charts' Uncategorized bar (#273) starts
 * on that month and Uncategorized; once the user has chosen filters of their
 * own, those come back after a process death instead.
 */
class SearchStartTest {
    private val now = YearMonth.of(2026, 10)
    private val start = SearchStart(YearMonth.of(2026, 9), categoryId = 16L)

    @Test fun aStartSetsTheMonthAndCategory() {
        assertEquals(
            "The search did not open on the month and category it was asked for",
            SearchChoice(month = start.month, categoryId = start.categoryId),
            restoredChoice(SavedStateHandle(), start, now),
        )
    }

    @Test fun filtersTheUserChoseOutliveTheStart() {
        val handle = SavedStateHandle(
            mapOf(SearchViewModel.MONTH to SearchViewModel.ANY, SearchViewModel.CATEGORY to null),
        )

        assertEquals(
            "A restored search went back to its start over the filters the user chose",
            SearchChoice(month = null, categoryId = null),
            restoredChoice(handle, start, now),
        )
    }

    @Test fun noStartIsThisMonthAndAnyCategory() {
        assertEquals(
            "A search opened from the magnifier did not open on this month and every category",
            SearchChoice(month = now),
            restoredChoice(SavedStateHandle(), start = null, now = now),
        )
    }
}
