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
}
