package my.pinged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole defence against a user who never presses Export -- and it must
 * never fire on an app with nothing to lose.
 */
class ExportNudgeTest {
    private val now = 1_700_000_000_000L

    @Test fun anEmptyLedgerIsNeverNudged() {
        assertFalse(
            "A fresh install with no transactions was told it has no backup, " +
                "which is the app inventing a problem the user cannot act on",
            exportIsOverdue(txns = 0, lastExportAt = 0L, now = now),
        )
    }

    @Test fun aLedgerWithRowsAndNoExportIsOverdue() {
        assertTrue(
            "A ledger with rows and no export at all is the exact state the " +
                "nudge exists for, and it stayed silent",
            exportIsOverdue(txns = 12, lastExportAt = 0L, now = now),
        )
    }

    @Test fun aRecentExportSilencesIt() {
        assertFalse(
            "The banner appeared a second after an export finished",
            exportIsOverdue(txns = 12, lastExportAt = now - 1000, now = now),
        )
    }

    @Test fun anExportOlderThanTheWindowDoesNot() {
        assertTrue(
            "An export older than the window left the nudge silent, so the " +
                "window is never crossed and the banner can never appear",
            exportIsOverdue(txns = 12, lastExportAt = now - EXPORT_STALE_AFTER_MILLIS - 1, now = now),
        )
    }

    /** The boundary itself is not overdue; a strict `>` is what the code says. */
    @Test fun exactlyTheWindowIsNotYetOverdue() {
        assertFalse(
            "An export exactly at the window counted as overdue, so the " +
                "comparison is `>=` while this test and the constant's KDoc " +
                "both say `>`",
            exportIsOverdue(txns = 12, lastExportAt = now - EXPORT_STALE_AFTER_MILLIS, now = now),
        )
    }

    @Test fun aDismissalSilencesItForAWeek() {
        assertFalse(
            "The nudge came back a minute after the user dismissed it",
            exportIsOverdue(txns = 12, lastExportAt = 0L, now = now, nudgeDismissedAt = now - 60_000),
        )
        assertFalse(
            "The nudge came back a millisecond before the week was up",
            exportIsOverdue(txns = 12, lastExportAt = 0L, now = now, nudgeDismissedAt = now - NUDGE_DISMISSED_FOR_MILLIS + 1),
        )
    }

    @Test fun aDismissalAWeekOldNoLongerDoes() {
        assertTrue(
            "A dismissal a week old still silenced the nudge, so one tap " +
                "turns the only backup reminder off for good",
            exportIsOverdue(txns = 12, lastExportAt = 0L, now = now, nudgeDismissedAt = now - NUDGE_DISMISSED_FOR_MILLIS),
        )
    }

    /**
     * A clock set back after a dismissal puts the dismissal in the future. Read
     * as silencing, that lasts until the clock catches up -- years, for a phone
     * that booted at the epoch -- so it is read as no dismissal at all.
     */
    @Test fun aDismissalInTheFutureDoesNotSilenceIt() {
        assertTrue(
            "A dismissal stamped after now silenced the nudge, so a clock " +
                "moved backwards hides it for as long as the clock is behind",
            exportIsOverdue(txns = 12, lastExportAt = 0L, now = now, nudgeDismissedAt = now + 60_000),
        )
    }
}
