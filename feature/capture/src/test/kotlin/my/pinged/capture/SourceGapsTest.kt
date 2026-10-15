package my.pinged.capture

import my.pinged.data.entity.CaptureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec 10.3's check, "no Touch 'n Go notifications in 14 days, and there were
 * 40 last month", as a rule over one `capture_source` row.
 */
class SourceGapsTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    private fun source(
        enabled: Boolean = true,
        lastAt: Long? = now - 15 * day,
        expected: Int? = 40,
    ) = CaptureSource(
        pkg = "my.com.tngdigital.ewallet",
        label = "TNG eWallet",
        enabled = enabled,
        firstSeenAt = now - 400 * day,
        lastNotificationAt = lastAt,
        expectedMonthlyCount = expected,
    )

    @Test fun aBusySourceSilentForFifteenDaysIsQuiet() {
        assertEquals(
            SourceGaps.Quiet(pkg = "my.com.tngdigital.ewallet", label = "TNG eWallet", days = 15, expected = 40),
            SourceGaps.quiet(source(), now),
        )
    }

    @Test fun thirteenDaysIsNotYetQuiet() {
        assertNull(SourceGaps.quiet(source(lastAt = now - 13 * day), now))
    }

    @Test fun exactlyFourteenDaysIsQuiet() {
        assertEquals(14, SourceGaps.quiet(source(lastAt = now - 14 * day), now)?.days)
    }

    @Test fun aSourceThatRarelySpeaksIsNotQuietForBeingSilent() {
        // Seven a month is about three expected in fourteen days, and a Poisson
        // source at that rate is silent that long about one time in twenty.
        assertNull(SourceGaps.quiet(source(expected = 7), now))
        assertEquals(8, SourceGaps.quiet(source(expected = 8), now)?.expected)
    }

    @Test fun nothingIsClaimedWithoutAnExpectation() {
        assertNull(SourceGaps.quiet(source(expected = null), now))
    }

    @Test fun aSourceNeverHeardFromIsNotQuiet() {
        assertNull(SourceGaps.quiet(source(lastAt = null), now))
    }

    @Test fun aDisabledSourceIsNotWatched() {
        assertNull(SourceGaps.quiet(source(enabled = false), now))
    }

    @Test fun aClockBehindTheLastNotificationIsNotQuiet() {
        assertNull(SourceGaps.quiet(source(lastAt = now + day), now))
    }
}
