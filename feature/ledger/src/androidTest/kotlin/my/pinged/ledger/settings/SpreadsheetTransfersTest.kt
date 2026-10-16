package my.pinged.ledger.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.SearchFilter
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.transfer.ExportCsv
import my.pinged.ledger.transfer.TransferStore
import my.pinged.ledger.transfer.useDb
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Transfers.spreadsheet] end to end, from the shipped database into a file
 * (#201): spec 12's columns as a spreadsheet will read them, every row the
 * query finds however many, and none of a backup's side effects.
 */
@RunWith(AndroidJUnit4::class)
class SpreadsheetTransfersTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val target get() = File(app.cacheDir, "spreadsheet-test.csv")
    private val recordedBefore = Transfers.recordExport

    @Before fun startClean() {
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
        target.delete()
    }

    @After fun restore() {
        Transfers.recordExport = recordedBefore
        target.delete()
    }

    /**
     * The whole file, written out by hand: the BOM, the header, a merchant
     * that is a formula with a comma and a quote in it, a refund, an
     * excluded row, a manual entry with no app or raw name, and a note on
     * two lines -- newest first, as the feed draws them.
     */
    @Test fun theFileIsSpecTwelvesColumnsAsASpreadsheetReadsThem() {
        val category = DatabaseFactory.build(app).useDb { db ->
            val id = db.categoryDao().requireUncategorizedId()
            db.txnDao().insert(txn(id, 1234, SEP_7, display = "=Kedai, \"Kopi\"", raw = "QR-KEDAI"))
            db.txnDao().insert(
                txn(id, 500, SEP_8, display = "Grab", raw = "GRAB*1", direction = Direction.REFUND, excluded = true, note = "two\nlines"),
            )
            db.txnDao().insert(txn(id, 7, SEP_6, display = "Warung", raw = null, pkg = null))
            db.categoryDao().all().single { it.id == id }.name
        }
        Databases.reset()

        val job = spreadsheet(SearchFilter())

        assertEquals(TransferJob.Spreadsheet(3, savedAs = "mine.csv"), job)
        assertEquals(
            "\uFEFF" +
                "date,amount,currency,direction,merchant_display,merchant_raw,category,source_app,excluded,note\r\n" +
                "2026-09-08,5.00,MYR,REFUND,Grab,GRAB*1,$category,Grab,true,\"two\nlines\"\r\n" +
                "2026-09-07,12.34,MYR,EXPENSE,\"'=Kedai, \"\"Kopi\"\"\",QR-KEDAI,$category,Grab,false,\r\n" +
                "2026-09-06,0.07,MYR,EXPENSE,Warung,,$category,,false,\r\n",
            target.readText(Charsets.UTF_8),
        )
    }

    /**
     * No `LIMIT`: more rows than search draws (200) and than a page (500)
     * are all written, and they are the rows search's totals count for the
     * same filter -- the month's, not the one outside it.
     */
    @Test fun everyRowTheTotalsCountIsWrittenWithNoLimit() {
        val month = SearchFilter(from = SEP_1, to = SEP_30)
        val counted = DatabaseFactory.build(app).useDb { db ->
            val id = db.categoryDao().requireUncategorizedId()
            db.runInTransaction {
                repeat(ROWS) { db.txnDao().insert(txn(id, 100L + it, SEP_7, occurredAt = it.toLong())) }
                db.txnDao().insert(txn(id, 99, LocalDate(20_261_001)))
            }
            db.txnDao().searchTotals("", month).sumOf { it.rowCount }
        }
        Databases.reset()

        val job = spreadsheet(month)

        assertEquals(ROWS, counted)
        assertEquals(TransferJob.Spreadsheet(ROWS, savedAs = "mine.csv"), job)
        // The header, then a line per row.
        assertEquals(ROWS + 1, target.readText().split("\r\n").count { it.isNotEmpty() })
    }

    /**
     * A spreadsheet rebuilds nothing, so it must not tell the backup nudge a
     * backup was made: a month of transactions would silence it for thirty
     * days over a ledger nobody has backed up.
     */
    @Test fun aSpreadsheetIsNotRecordedAsABackup() = runBlocking {
        DatabaseFactory.build(app).useDb { db ->
            db.txnDao().insert(txn(db.categoryDao().requireUncategorizedId(), 100, SEP_7))
        }
        Databases.reset()
        val recorded = AtomicInteger()
        Transfers.recordExport = { _, _ -> recorded.incrementAndGet() }
        val before = TransferStore.lastExportAt(app)

        spreadsheet(SearchFilter())

        assertEquals("a spreadsheet was recorded as a backup", 0, recorded.get())
        assertEquals(before, TransferStore.lastExportAt(app))
    }

    /**
     * Its screen going away part way removes the document, as a backup's
     * does: a prefix of a CSV is a CSV, and reads as the whole export.
     */
    @Test fun aSpreadsheetWhoseScreenWentAwayLeavesNoFile() = runBlocking {
        DatabaseFactory.build(app).useDb { db ->
            val id = db.categoryDao().requireUncategorizedId()
            db.runInTransaction { repeat(3 * ExportCsv.PAGE) { db.txnDao().insert(txn(id, 100L + it, SEP_7)) } }
        }
        Databases.reset()
        val requester = Job()
        // The first page's flush proves the writer is inside its loop, with
        // two pages still ahead of the next `isCancelled`.
        val sink = sink(onFlush = { requester.cancel() })

        val answer = Transfers.spreadsheet(app, sink, "", SearchFilter(), requester).await()

        assertEquals(Transfers.Answer.Abandoned, answer)
        assertFalse("a cut-off spreadsheet was left where the user put it", target.exists())
    }

    private fun spreadsheet(filter: SearchFilter): TransferJob? = runBlocking {
        val answer = Transfers.spreadsheet(app, sink(), "", filter, Job()).await()
        assertTrue("the export did not settle: $answer", answer is Transfers.Answer.Settled)
        (answer as Transfers.Answer.Settled).outcome.job
    }

    private fun sink(onFlush: () -> Unit = {}) = object : ExportSink {
        override fun open(): OutputStream = object : FilterOutputStream(target.outputStream()) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun flush() {
                super.flush()
                onFlush()
            }
        }

        override fun delete(): Boolean = target.delete()
        override fun name(): String = "mine.csv"
    }

    private fun txn(
        categoryId: Long,
        amountSen: Long,
        date: LocalDate,
        display: String = "Grab",
        raw: String? = "GRAB",
        pkg: String? = "com.grabtaxi.passenger",
        direction: Direction = Direction.EXPENSE,
        excluded: Boolean = false,
        note: String? = null,
        occurredAt: Long = date.yyyymmdd.toLong(),
    ) = Txn(
        rawCaptureId = null,
        amountSen = amountSen,
        direction = direction,
        occurredAt = occurredAt,
        localDate = date,
        merchantRaw = raw,
        merchantDisplay = display,
        merchantKey = raw,
        categoryId = categoryId,
        sourcePackage = pkg,
        sourceLabel = pkg?.let { "Grab" },
        confidence = Confidence.HIGH,
        state = TxnState.COMMITTED,
        isExcluded = excluded,
        exclusionReason = if (excluded) ExclusionReason.TRANSFER else null,
        note = note,
        createdAt = 0,
        updatedAt = 0,
    )

    private companion object {
        /** Past a page and past search's own limit. */
        const val ROWS = ExportCsv.PAGE + 1
        val SEP_1 = LocalDate(20_260_901)
        val SEP_6 = LocalDate(20_260_906)
        val SEP_7 = LocalDate(20_260_907)
        val SEP_8 = LocalDate(20_260_908)
        val SEP_30 = LocalDate(20_260_930)
    }
}
