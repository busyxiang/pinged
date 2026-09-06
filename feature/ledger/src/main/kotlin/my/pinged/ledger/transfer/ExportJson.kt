package my.pinged.ledger.transfer

import java.util.concurrent.Callable
import android.util.JsonWriter
import java.io.OutputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import my.pinged.data.PingedDatabase

/**
 * Spec 12's JSON export: every row of every table, written straight onto the
 * stream.
 *
 * ## Streaming, and what that has to mean to be worth claiming
 *
 * Spec 12 says nothing is assembled in memory first; spec 15.5 adds that a
 * batch job over the whole capture history is chunked, cancellable and bounded.
 * Together:
 *
 * - Rows are read a [PAGE] at a time with a **keyset** cursor, so at spec
 *   15.8's 50,000-capture fixture the live set is five hundred rows. Keyset
 *   rather than `LIMIT/OFFSET` because `OFFSET` counts and discards --
 *   `RawCaptureDao.pageAfter` carries the measurement.
 * - The writer is flushed at every page boundary, so bytes are on their way to
 *   the file while the next page is read. Without it the claim is true of the
 *   row objects and not of the document.
 * - [isCancelled] is asked at every page boundary, the granularity at which
 *   cancelling is free.
 *
 * ## Cancelling leaves a broken file, deliberately
 *
 * A cancelled export throws [TransferCancelledException] half-written, and the
 * caller must delete the SAF document. A prefix of a JSON object is not a JSON
 * object, so the alternative is a file that closes its braces and silently
 * claims to be a complete backup of a ledger it holds part of.
 */
object ExportJson {
    /**
     * Rows per DAO read. Five hundred raw captures with their full text is on
     * the order of a megabyte, which is the size of thing this can afford to
     * hold; ten times fewer would multiply the query count for no gain, and
     * ten times more starts to be the payload again.
     */
    const val PAGE = 500

    /**
     * BufferedWriter, and not decoration. OutputStreamWriter buffers *bytes* -- an
     * 8 KB ByteBuffer in its StreamEncoder -- but not chars, and
     * android.util.JsonWriter emits one writer call per token fragment. At 25
     * columns that is ~190 calls per raw_capture row, each allocating a fresh
     * char[]: across a 50,000-row fixture, ~15M encoder invocations and hundreds of
     * megabytes of short-lived garbage. Buffering chars collapses it to a few
     * thousand.
     *
     * The streaming guarantee is untouched: the per-page flush pushes through both
     * layers, which is what StreamingTest's interleaving assertion checks.
     *
     * ImportJson deliberately has no BufferedReader: android.util.JsonReader
     * carries its own 1024-char buffer and pulls in 1 KB chunks.
     */
    fun write(
        db: PingedDatabase,
        out: OutputStream,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int = db.runInTransaction(
        Callable { writeDocument(db, out, onProgress, isCancelled) },
    )

    /**
     * The whole document is read inside **one** transaction.
     *
     * As six independent snapshots, a capture committed after the `raw_captures`
     * section closed and before `txns` was read put a transaction in the file whose
     * `raw_capture_id` named a capture the file did not contain -- and
     * `ImportJson.verifyReferences` then refuses the **entire** file. The export
     * reported success; the user found out on the new phone. `duplicate_of_id`
     * gives the same shape.
     *
     * WAL would ordinarily make a read transaction a snapshot rather than a lock,
     * but Room 2.8 passes a single connection through to SQLCipher, so a
     * transaction serialises every other caller for its duration: measured at a
     * writer blocked 6001 ms against a 6000 ms hold, and asserted by
     * `ExportIsolationTest.aWriterCannotCommitWhileTheExportIsOpen`.
     *
     * So capture stalls while an export runs -- seconds at 50,000 rows. The
     * notifications are not lost, since Android delivers them to a listener that is
     * simply slow to return, but it is worth knowing before the export screen
     * exists, because it reads as a frozen app.
     */
    private fun writeDocument(
        db: PingedDatabase,
        out: OutputStream,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): Int {
        var written = 0

        // Not `setIndent`, i.e. no pretty printing. At 50,000 captures the
        // indentation is megabytes of spaces in a file whose only reader is
        // this app and the occasional curious user with `jq`.
        JsonWriter(BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 16 * 1024)).use { w ->
            w.beginObject()

            // First, and the reader depends on it being first: a file from a
            // future version has to be refused before a single row of it is
            // believed.
            w.name(Backup.FIELD_FORMAT).value(Backup.FORMAT_VERSION.toLong())
            w.name(Backup.FIELD_SCHEMA_VERSION)
                .value(db.openHelper.readableDatabase.version.toLong())
            w.name(Backup.FIELD_EXPORTED_AT).value(System.currentTimeMillis())

            // The order is Backup.SECTION_ORDER and it is not cosmetic: the
            // reader inserts as it goes, so parents have to be on the wire
            // before their children.
            written = writeWhole(w, CATEGORY_SECTION, db.categoryDao().all(), written, onProgress)
            written = writeWhole(
                w, CAPTURE_SOURCE_SECTION, db.captureSourceDao().all(), written, onProgress,
            )
            written = writeWhole(
                w, CAPTURE_DAY_SECTION, db.captureDayDao().all(), written, onProgress,
            )
            written = writePaged(
                w, MERCHANT_RULE_SECTION, written, onProgress, isCancelled,
                nextPage = { after -> db.merchantRuleDao().pageFrom(after, PAGE) },
                idOf = { it.id },
            )
            written = writePaged(
                w, RAW_CAPTURE_SECTION, written, onProgress, isCancelled,
                nextPage = { after -> db.rawCaptureDao().pageFrom(after, PAGE) },
                idOf = { it.id },
            )
            written = writePaged(
                w, TXN_SECTION, written, onProgress, isCancelled,
                nextPage = { after -> db.txnDao().pageFrom(after, PAGE) },
                idOf = { it.id },
            )

            w.endObject()
        }
        onProgress(written)
        return written
    }

    /**
     * A section read in one query, for the three small tables only. `category` is
     * bounded by spec 4's fourteen plus what the user adds, `capture_source` by the
     * number of apps on the phone, and `capture_day` gains one row a day. Paging
     * machinery on a fourteen-row table is code never exercised at its interesting
     * boundary.
     */
    private fun <T> writeWhole(
        w: JsonWriter,
        section: BackupSection<T>,
        rows: List<T>,
        writtenSoFar: Int,
        onProgress: (Int) -> Unit,
    ): Int {
        w.name(section.name).beginArray()
        rows.forEach { section.writeRow(w, it) }
        w.endArray()
        w.flush()
        val written = writtenSoFar + rows.size
        onProgress(written)
        return written
    }

    /**
     * A section read with a keyset cursor, a page at a time.
     *
     * [nextPage] is handed the last id written; [idOf] reads the id back out,
     * because `RawCapture`, `Txn` and `MerchantRule` share no supertype that has
     * one.
     *
     * **The cursor starts at 0 and the queries say `id > :after`, so this writes no
     * row whose id is 0 or less.** Safe only because ids here are 1 or more, which
     * `BackupRow.rowId` enforces at the one boundary where an id arrives as data.
     * Without that, a restored row could be invisible to every export this device
     * writes. Make the cursor total -- `Long.MIN_VALUE` and the queries to match --
     * and the import check can be relaxed with it; `ExportCursorTest` says so in
     * the other direction.
     */
    private fun <T> writePaged(
        w: JsonWriter,
        section: BackupSection<T>,
        writtenSoFar: Int,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        nextPage: (Long) -> List<T>,
        idOf: (T) -> Long,
    ): Int {
        var written = writtenSoFar
        var after = 0L
        w.name(section.name).beginArray()
        while (true) {
            if (isCancelled()) {
                throw TransferCancelledException(
                    "Export cancelled after $written rows, while writing " +
                        "${section.name}. The file is incomplete and must be deleted.",
                )
            }
            val page = nextPage(after)
            if (page.isEmpty()) break
            page.forEach { section.writeRow(w, it) }
            written += page.size
            after = idOf(page.last())
            // Before the progress callback, so that a caller which reads the
            // stream's byte count on progress sees the bytes this page
            // produced. That is what `StreamingTest` measures, and it is also
            // simply what "straight to the stream" means.
            w.flush()
            onProgress(written)
            if (page.size < PAGE) break
        }
        w.endArray()
        w.flush()
        return written
    }
}
