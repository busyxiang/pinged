package my.pinged

import my.pinged.capture.CaptureReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec 10.2's always-visible signal: the ledger's top bar says when Pinged
 * last heard a notification.
 */
class HeardLineTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun heardAgo(millis: Long) =
        CaptureReport(granted = true, lastNotificationAt = now - millis, staleForMillis = millis)

    @Test fun underTheHeartbeatsThrottleItClaimsNoFinerAnswer() {
        assertEquals("HEARD IN THE LAST 5 MIN", heardLine(heardAgo(0L), now))
        assertEquals("HEARD IN THE LAST 5 MIN", heardLine(heardAgo(4 * minute), now))
    }

    @Test fun minutesThenHoursThenDays() {
        assertEquals("HEARD 5 MIN AGO", heardLine(heardAgo(5 * minute), now))
        assertEquals("HEARD 59 MIN AGO", heardLine(heardAgo(59 * minute), now))
        assertEquals("HEARD 1 HR AGO", heardLine(heardAgo(60 * minute), now))
        assertEquals("HEARD 23 HR AGO", heardLine(heardAgo(23 * 60 * minute + 59 * minute), now))
        assertEquals("HEARD 1 DAY AGO", heardLine(heardAgo(24 * 60 * minute), now))
        assertEquals("HEARD 3 DAYS AGO", heardLine(heardAgo(3 * 24 * 60 * minute), now))
    }

    @Test fun measuredAgainstTheTimeOfDrawingNotOfTheReport() {
        val report = heardAgo(0L)
        assertEquals("HEARD 10 MIN AGO", heardLine(report, now + 10 * minute))
    }

    @Test fun nothingHeardIsTheBannersToSay() {
        assertNull(heardLine(CaptureReport(granted = true, lastNotificationAt = 0L, staleForMillis = Long.MAX_VALUE), now))
    }

    @Test fun anUnreadableRecordOrAMissingGrantDrawsNoLine() {
        assertNull(heardLine(heardAgo(minute).copy(healthUnreadable = true), now))
        assertNull(heardLine(heardAgo(minute).copy(granted = false), now))
        assertNull(heardLine(heardAgo(minute).copy(storageUnavailable = true), now))
        assertNull(heardLine(null, now))
    }

    @Test fun aClockBehindTheHeartbeatReadsAsJustHeard() {
        assertEquals("HEARD IN THE LAST 5 MIN", heardLine(heardAgo(-10 * minute), now))
    }
}
