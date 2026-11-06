package my.pinged.ledger.home

import my.pinged.data.dao.FeedRow
import my.pinged.ledger.entry.EditTarget
import my.pinged.ledger.ledgerTxn
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a row's tap opens (#377): a manual entry its edit screen (#105), a
 * captured row its detail (#153), decided here and nowhere else.
 */
class RowOpeningsTest {
    private val entries = mutableListOf<EditTarget>()
    private val details = mutableListOf<EditTarget>()
    private val openings = RowOpenings(openEntry = { entries += it }, openDetail = { details += it }, openReview = {})

    @Test fun aManualEntryOpensItsEditScreen() {
        openings.open(FeedRow(ledgerTxn(1_250L, occurredAt = 5_000L, id = 7L, rawCaptureId = null), null, "Ali"))

        assertEquals("A manual entry opened the wrong screen, or the wrong row", listOf(EditTarget(7L, 5_000L)), entries)
        assertEquals(emptyList<EditTarget>(), details)
    }

    @Test fun aCapturedRowOpensItsDetail() {
        openings.open(FeedRow(ledgerTxn(1_250L, occurredAt = 6_000L, id = 8L, rawCaptureId = 31L), null, "Ali"))

        assertEquals("A captured row opened the wrong screen, or the wrong row", listOf(EditTarget(8L, 6_000L)), details)
        assertEquals(emptyList<EditTarget>(), entries)
    }

    @Test fun eachRowIsOfferedToAScreenReaderAsWhatItOpens() {
        assertEquals(EDIT_ENTRY, openings.label(FeedRow(ledgerTxn(1L, rawCaptureId = null), null, null)))
        assertEquals(OPEN_DETAIL, openings.label(FeedRow(ledgerTxn(1L, rawCaptureId = 31L), null, null)))
    }
}
