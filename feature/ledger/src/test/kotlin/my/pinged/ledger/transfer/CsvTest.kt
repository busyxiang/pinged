package my.pinged.ledger.transfer

import java.util.Locale
import my.pinged.data.LocalDate
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 12's three CSV requirements that are easy to miss, and its
 * `Locale.ROOT` rule, each against a literal written out by hand from
 * RFC 4180 rather than built the way [Csv] builds it.
 */
class CsvTest {
    @Test fun aPlainFieldIsWrittenAsItIs() {
        assertEquals("Grab,12.34\r\n", Csv.line(listOf("Grab", "12.34")))
    }

    @Test fun commasQuotesAndNewlinesAreQuotedAndQuotesDoubled() {
        assertEquals(
            "\"Kedai, Kopi\",\"Say \"\"hi\"\"\",\"two\nlines\",\"cr\rhere\"\r\n",
            Csv.line(listOf("Kedai, Kopi", "Say \"hi\"", "two\nlines", "cr\rhere")),
        )
    }

    @Test fun anAbsentFieldIsEmpty() {
        assertEquals("a,,b\r\n", Csv.line(listOf("a", null, "b")))
    }

    /**
     * A merchant string arrives from outside the app, and a spreadsheet
     * reads a field that starts with one of these as a formula. Tab and
     * carriage return are OWASP's two more.
     */
    @Test fun aLeadingFormulaCharacterIsNeutralised() {
        assertEquals("'=HYPERLINK(1)\r\n", Csv.line(listOf("=HYPERLINK(1)")))
        assertEquals("'+60123\r\n", Csv.line(listOf("+60123")))
        assertEquals("'-2\r\n", Csv.line(listOf("-2")))
        assertEquals("'@SUM(A1)\r\n", Csv.line(listOf("@SUM(A1)")))
        assertEquals("'\tx\r\n", Csv.line(listOf("\tx")))
        assertEquals("\"'\rx\"\r\n", Csv.line(listOf("\rx")))
        // Neutralised first and quoted after, so the quote survives quoting.
        assertEquals("\"'=1,2\"\r\n", Csv.line(listOf("=1,2")))
        // Only a leading one: inside a field it is text already.
        assertEquals("a=b\r\n", Csv.line(listOf("a=b")))
    }

    @Test fun theDocumentOpensWithAUtf8ByteOrderMark() {
        assertEquals(listOf(0xEF, 0xBB, 0xBF), Csv.BOM.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF })
    }

    /** `String.format` under a comma-decimal locale writes `12,34`; spec 12. */
    @Test fun amountsAndDatesIgnoreTheDefaultLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("12.34", Csv.amount(1234))
            assertEquals("1234567.05", Csv.amount(123456705))
            assertEquals("0.07", Csv.amount(7))
            assertEquals("2026-09-07", Csv.date(LocalDate(20260907)))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun theHeaderNamesSpecTwelvesTenColumnsInItsOrder() {
        assertEquals(
            "date,amount,currency,direction,merchant_display,merchant_raw,category,source_app,excluded,note\r\n",
            Csv.line(ExportCsv.HEADER),
        )
    }

    @Test fun aRowIsItsTenColumns() {
        val row = FeedRow(
            txn = Txn(
                rawCaptureId = 1,
                amountSen = 1840,
                currency = "MYR",
                direction = Direction.REFUND,
                occurredAt = 0,
                localDate = LocalDate(20260930),
                merchantRaw = "GRAB*TRIP-A1",
                merchantDisplay = "Grab",
                merchantKey = "GRAB",
                categoryId = 3,
                sourcePackage = "com.grabtaxi.passenger",
                sourceLabel = "Grab",
                confidence = Confidence.HIGH,
                state = TxnState.COMMITTED,
                isExcluded = true,
                exclusionReason = ExclusionReason.USER,
                note = "airport",
                createdAt = 0,
                updatedAt = 0,
            ),
            identityKey = "GRAB",
            displayName = "Grab rides",
        )
        assertEquals(
            listOf("2026-09-30", "18.40", "MYR", "REFUND", "Grab rides", "GRAB*TRIP-A1", "Transport", "Grab", "true", "airport"),
            ExportCsv.fields(row, "Transport"),
        )
    }

    @Test fun aManualEntryHasNoSourceAppAndNoRawName() {
        val row = FeedRow(
            txn = Txn(
                rawCaptureId = null,
                amountSen = 500,
                direction = Direction.EXPENSE,
                occurredAt = 0,
                localDate = LocalDate(20260901),
                merchantRaw = null,
                merchantDisplay = "Warung",
                merchantKey = null,
                categoryId = 1,
                sourcePackage = null,
                sourceLabel = null,
                confidence = Confidence.HIGH,
                state = TxnState.COMMITTED,
                createdAt = 0,
                updatedAt = 0,
            ),
            identityKey = null,
            displayName = "Warung",
        )
        assertEquals(
            listOf("2026-09-01", "5.00", "MYR", "EXPENSE", "Warung", null, "Food", null, "false", null),
            ExportCsv.fields(row, "Food"),
        )
    }
}
