package my.pinged.ledger.transfer

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import my.pinged.data.Databases
import my.pinged.data.PingedDatabase
import my.pinged.data.SearchCursor
import my.pinged.data.SearchFilter
import my.pinged.data.dao.FeedRow

/**
 * Spec 12's CSV export: one row per transaction, for a spreadsheet, and
 * every row of it a row search would find for the same words and filter
 * (#201). The export sheet's range and leave-outs are a [SearchFilter] with
 * no words; search's "Export these N rows" is its own.
 *
 * **Read through `TxnDao.searchPage`, the query search's rows and totals
 * come from**, so the count the screen showed and the rows the file holds
 * cannot disagree about which rows match. Unlike the screen's read it has no
 * limit: it covers every row the totals count, not the 200 search draws.
 *
 * Streamed as `ExportJson` is, and for its reasons: a keyset page of
 * [PAGE] rows at a time, the writer flushed at every page, [write]'s
 * `isCancelled` asked at every page boundary, and all of it in one
 * transaction so the file is one moment's ledger. A cancelled CSV is
 * deleted by the caller like a cancelled backup: a prefix of a CSV reads
 * as a complete one.
 */
object ExportCsv {
    /**
     * Rows per read: `ExportJson.PAGE`, whose KDoc gives its reasoning for a
     * raw capture's full text. A CSV row is narrower, so the live set is
     * smaller still; not measured separately.
     */
    const val PAGE = ExportJson.PAGE

    /** Spec 12's columns, in its order. */
    val HEADER = listOf(
        "date", "amount", "currency", "direction", "merchant_display",
        "merchant_raw", "category", "source_app", "excluded", "note",
    )

    /**
     * Writes the rows [text] and [filter] find to [out], newest first in the
     * feed's order, and answers how many. [out] is flushed, not closed.
     */
    fun write(
        db: PingedDatabase,
        out: OutputStream,
        text: String,
        filter: SearchFilter,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int = Databases.whileLive(db) {
        db.runInTransaction(
            Callable {
                val categories = db.categoryDao().all().associate { it.id to it.name }
                // Chars buffered, for `ExportJson.write`'s reason; its size.
                val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 16 * 1024)
                w.write(Csv.BOM)
                w.write(Csv.line(HEADER))
                var written = 0
                var after: SearchCursor? = null
                while (true) {
                    if (isCancelled()) {
                        throw TransferCancelledException(
                            "Export cancelled after $written rows. The file is incomplete and must be deleted.",
                        )
                    }
                    val page = db.txnDao().searchPage(text, filter, after, PAGE)
                    for (row in page) w.write(Csv.line(fields(row, categories[row.txn.categoryId])))
                    written += page.size
                    w.flush()
                    onProgress(written)
                    if (page.size < PAGE) break
                    after = SearchCursor.after(page.last())
                }
                w.flush()
                written
            },
        )
    }

    /**
     * [row] as [HEADER]'s columns. The merchant is the name the screen draws
     * (spec 6.4's, through `FeedRow.displayName`), beside the raw string the
     * notification carried; the source app is the label its rows carry, and
     * a manual entry has none.
     */
    fun fields(row: FeedRow, category: String?): List<String?> {
        val txn = row.txn
        return listOf(
            Csv.date(txn.localDate),
            Csv.amount(txn.amountSen),
            txn.currency,
            txn.direction.name,
            row.displayName,
            txn.merchantRaw,
            category,
            txn.sourceLabel ?: txn.sourcePackage,
            txn.isExcluded.toString(),
            txn.note,
        )
    }
}
