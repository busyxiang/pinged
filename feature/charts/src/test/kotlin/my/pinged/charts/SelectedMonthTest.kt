package my.pinged.charts

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

/**
 * The selected month is what the screen's [SavedStateHandle] holds, which is
 * what a process death restores (#81, User stories 2-3).
 */
class SelectedMonthTest {
    @Test fun aFreshScreenOpensOnTheCurrentMonthAndKeepsIt() {
        val handle = SavedStateHandle()

        assertEquals(YearMonth.of(2026, 10), selectedMonth(handle, now = YearMonth.of(2026, 10)))
        assertEquals(
            "The default was not written, so a restore after midnight on the 1st " +
                "would open on a different month from the one on screen",
            "2026-10", handle.get<String>(SELECTED_MONTH),
        )
    }

    @Test fun aRestoredScreenOpensOnTheMonthItHeldNotTheCurrentOne() {
        // What a process death hands back: the handle's own saved values.
        val restored = SavedStateHandle(mapOf(SELECTED_MONTH to "2026-08"))

        assertEquals(YearMonth.of(2026, 8), selectedMonth(restored, now = YearMonth.of(2026, 10)))
    }
}
