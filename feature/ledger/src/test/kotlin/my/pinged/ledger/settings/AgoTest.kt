package my.pinged.ledger.settings

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ago`'s branches, pinned against a fixed `now` so a day boundary crossing
 * during a run cannot flip a result.
 *
 * A plain JVM test: `ago` reads nothing but two `Long`s and a `TimeUnit`
 * conversion, so it needs no device.
 */
class AgoTest {
    private val now = 1_600_000_000_000L

    @Test fun neverIsTheCallersOwnWordForAbsent() {
        assertEquals("NEVER", ago(at = 0L, never = "NEVER", now = now))
        assertEquals("NEVER CHECKED", ago(at = -1L, never = "NEVER CHECKED", now = now))
    }

    @Test fun underOneDayReadsToday() {
        assertEquals("TODAY", ago(at = now, never = "NEVER", now = now))
        assertEquals(
            "A moment inside the same calendar day as `now` still reads TODAY",
            "TODAY",
            ago(at = now - TimeUnit.HOURS.toMillis(23), never = "NEVER", now = now),
        )
    }

    @Test fun exactlyOneDayReadsYesterday() {
        assertEquals("YESTERDAY", ago(at = now - TimeUnit.DAYS.toMillis(1), never = "NEVER", now = now))
    }

    @Test fun twoToTwentyNineDaysCountThemselves() {
        assertEquals("2 DAYS AGO", ago(at = now - TimeUnit.DAYS.toMillis(2), never = "NEVER", now = now))
        assertEquals("29 DAYS AGO", ago(at = now - TimeUnit.DAYS.toMillis(29), never = "NEVER", now = now))
    }

    @Test fun theThirtyDayBoundaryTipsOverToTheCoarseAnswer() {
        assertEquals(
            "29 days is still counted precisely; the boundary is at 30, not before it",
            "29 DAYS AGO",
            ago(at = now - TimeUnit.DAYS.toMillis(29), never = "NEVER", now = now),
        )
        assertEquals(
            "30 days loses the count -- the reader is told it is stale, not how stale",
            "OVER A MONTH AGO",
            ago(at = now - TimeUnit.DAYS.toMillis(30), never = "NEVER", now = now),
        )
    }

    @Test fun wellPastThirtyDaysStaysCoarse() {
        assertEquals("OVER A MONTH AGO", ago(at = now - TimeUnit.DAYS.toMillis(400), never = "NEVER", now = now))
    }
}
