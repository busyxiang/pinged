package my.pinged.ledger.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The spreadsheet card's counted copy says the live count, never the board's scenario. */
class SpreadsheetCopyTest {
    @Test fun theAwaitingReviewRowSaysItsOwnCount() {
        assertEquals("The one still awaiting review", awaitingReview(1))
        assertEquals("The 3 still awaiting review", awaitingReview(3))
        assertEquals("The 1,204 still awaiting review", awaitingReview(1204))
    }

    @Test fun theButtonSaysTheRowsCountedOrNoNumber() {
        assertEquals("Export", exportRowsLabel(null))
        assertEquals("Export 1 row", exportRowsLabel(1))
        assertEquals("Export 57 rows", exportRowsLabel(57))
    }
}
