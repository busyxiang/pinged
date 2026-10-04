package my.pinged.ledger.transfer

import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.Closeable
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn

/**
 * Salvage's reader (design 5): keyset pages of one table at a time, with
 * every row that will still read returned and the ones that will not
 * counted.
 *
 * A salvage document's paged sections stop on a short page
 * (`ExportJson.writePaged`), so **a short page means the table has ended**,
 * and [page] never returns one for any other reason.
 *
 * [D] is the connection a read goes through: [PingedDatabase] in production,
 * a fake in `RowReaderTest`.
 *
 * ## What damage does, measured
 *
 * On emulator-5554, SQLCipher 4.18.0, one byte flipped in a chosen page of
 * a ledger of N captures with a transaction each, every read on a fresh
 * connection unless stated:
 *
 * - **The damage throws `SQLiteDatabaseCorruptException`, "database disk
 *   image is malformed (code 11)"**, whether the page is a table leaf, an
 *   interior page, the table's root or an index leaf.
 * - **After that, the connection is poisoned.** Every later statement on it
 *   throws `SQLiteNotADatabaseException`, "file is not a database (code
 *   26)", reads of healthy tables included, and `Integrity.check` on a
 *   damaged file leaves its connection the same way. A second instance
 *   opened alongside it reads normally. So a connection is discarded after
 *   every damaged read, and code 26 is never stepped over: from a fresh
 *   connection it is a damaged header or a wrong key. In SQLCipher 4.18.0
 *   that class extends `SQLiteException` and not
 *   `SQLiteDatabaseCorruptException`, which `RowReaderDamageTest` pins.
 * - **A seek past a damaged leaf succeeds, but not from its last id.**
 *   Leaf ids 2521..2539 of 5,000: `id > 2539` throws, `id >= 2540` reads.
 *   Fetches therefore start *at* an id ([PagedTable.fetch]); an exclusive
 *   cursor loses the first row after every damaged leaf.
 * - **Damage on the rightmost path answers nothing past it.** Rightmost leaf
 *   (ids 4991..5000 of 5,000), rightmost interior page (45271..50000 of
 *   50,000) and root: `id > 5000`, `id > 5010`, `id > Long.MAX_VALUE - 1`
 *   and `max(id)` all throw, so a walk that waits for an empty page to stop
 *   never stops.
 * - **An index still lists every id.** In all three of those cases, and
 *   under a mid-table leaf, all four `raw_capture` indexes and
 *   `txn(raw_capture_id)` returned exactly 1..N, in 69-80 ms at 50,000. So did
 *   `sqlite_sequence`. With an index leaf damaged instead, that index
 *   throws, the table and the other indexes read in full, and a plain
 *   `SELECT id` throws too, because SQLite serves it from that same index.
 * - A closed instance throws `android.database.SQLException` "Error code:
 *   21, message: connection is closed", which is not a `SQLiteException`.
 *   A full disk throws `SQLiteFullException`, code 13.
 *
 * ## The bound, and why the walk stops
 *
 * On the first damaged read in a table, the table's ids are read off its
 * indexes ([PagedTable.idSources], each tried until one reads). Only if
 * every index is damaged too does it fall back to `sqlite_sequence`, and
 * then every integer up to it counts as listed. With neither, the damage
 * is rethrown: there is nothing to stop at.
 *
 * The list is consulted only when one row fails alone. At a listed id,
 * that id is counted and stepped over by one; at an id the list does not
 * hold, the walk moves up to the next listed id without counting, and ends
 * if there is none. Either way a failure reaches or passes a listed id, a
 * success moves past the rows it returned, and halving is bounded by the
 * span, so the walk cannot outlive the list.
 *
 * ## Stepping, never skipping
 *
 * A failing span is halved until one row fails alone, and only that row is
 * stepped over. After a step the span stays small and doubles with each
 * read that succeeds. Nothing is jumped: a gallop can jump a readable
 * island between two damaged pages.
 *
 * Reads start where the last one ended whatever the index says, so a row
 * the index does not list is still read. The one row that can be lost
 * although it reads is one the index does not list, lying between a failed
 * read at an unlisted id and the next listed id: finding it would take a
 * read per integer across the gap, one reopen each, and a gap is as wide
 * as the ids deleted there.
 *
 * ## Cancelling, and progress, inside a page
 *
 * A page over damage can run for seconds, since every unreadable row costs
 * a read and a reopen and none of them fills the page. [isCancelled] is
 * therefore asked before every read, not only at the page boundary where
 * `ExportJson.writePaged` asks it, and throws [TransferCancelledException]
 * as that does. [onStep] is told after every id stepped over how many
 * have been, in every table, since this reader was made; rows returned
 * reach the caller's own progress at the page boundary.
 *
 * ## What it costs
 *
 * Walking `raw_capture` and `txn` past one damaged leaf (19 rows) took
 * 235-242 ms at 10,000 captures and 864-885 ms at 50,000, three runs each
 * (`RowReaderDamageTest`). The cost that grows is one failed read and one
 * reopen per unreadable row: a damaged interior page at 50,000 left 4,730
 * unreadable and took 11,960 ms, a damaged root at 5,000 took 11,610 ms,
 * so 2.3-2.5 ms a row, each reopen through `DatabaseFactory.build`, which
 * unwraps the key every time; `SalvageJson` reads it once, and pays
 * 1.3-1.5 ms a row. A table of 50,000 that will not read at all would
 * take about two minutes to prove it; that is extrapolated, not run.
 * Cancelled from another thread 100 rows into a damaged root at 2,000,
 * the page threw 2.37-3.23 ms later over seven runs: one read and reopen.
 *
 * The [open]ed connections are this reader's own; [close] closes the last.
 */
class RowReader<D : Any>(
    private val open: () -> D,
    private val close: (D) -> Unit,
    private val isCancelled: () -> Boolean = { false },
    private val onStep: (steppedSoFar: Long) -> Unit = {},
) : Closeable {
    private var connection: D? = null
    private val bounds = HashMap<String, Bound>()
    private val recovered = HashMap<String, Int>()
    private val stepped = HashMap<String, Long>()
    private var steppedSoFar = 0L

    /**
     * What could not be read, per [PagedTable.name], for each table where
     * an id was stepped over. A table that lost nothing has no entry, even
     * where damage was met: an empty table's damaged root, measured.
     */
    val unreadable: Map<String, Unreadable>
        get() = stepped.mapValues { (name, ids) ->
            bounds.getValue(name).report(recovered[name] ?: 0, ids)
        }

    /** Up to [limit] rows of [table] with ids above [after], in id order. */
    fun <T> page(table: PagedTable<D, T>, after: Long, limit: Int): List<T> {
        require(limit > 0) { "A page of $limit rows" }
        val kept = ArrayList<T>(limit)
        if (after == Long.MAX_VALUE) return kept
        var from = after + 1
        var span = limit
        while (kept.size < limit) {
            if (isCancelled()) {
                throw TransferCancelledException(
                    "Salvage cancelled in ${table.name} at id $from. " +
                        "The file is incomplete and must be deleted.",
                )
            }
            val want = minOf(span, limit - kept.size)
            val rows = try {
                table.fetch(connection(), from, want)
            } catch (damage: SQLiteDatabaseCorruptException) {
                discard()
                if (want > 1) {
                    span = want / 2
                    continue
                }
                val bound = bounds.getOrPut(table.name) { bind(table, damage) }
                if (!bound.lists(from)) {
                    from = bound.atOrAfter(from) ?: return kept
                    continue
                }
                stepped[table.name] = (stepped[table.name] ?: 0L) + 1
                onStep(++steppedSoFar)
                if (from == Long.MAX_VALUE) return kept
                from++
                continue
            }
            kept += rows
            recovered[table.name] = (recovered[table.name] ?: 0) + rows.size
            // A read that succeeded ran to the table's end without meeting
            // damage, so fewer rows than asked is the true end.
            if (rows.size < want) return kept
            val last = table.idOf(rows.last())
            if (last == Long.MAX_VALUE) return kept
            from = last + 1
            span = minOf(want * 2, limit)
        }
        return kept
    }

    /**
     * One of the three small tables `ExportJson` reads in one query, or
     * null if that query met damage: read whole, so lost whole, and
     * nothing is counted. They are small enough that a walk around their
     * damage would rescue a few rows of fourteen categories or of one
     * row a day. The connection the damage poisoned is discarded, as
     * [page] discards one.
     */
    fun <T> whole(table: String, read: (D) -> List<T>): List<T>? {
        if (isCancelled()) {
            throw TransferCancelledException("Salvage cancelled at $table. The file is incomplete and must be deleted.")
        }
        return try {
            read(connection())
        } catch (damage: SQLiteDatabaseCorruptException) {
            discard()
            null
        }
    }

    /** Closes the connection this reader holds, if it holds one. */
    override fun close() = discard()

    private fun connection(): D = connection ?: open().also { connection = it }

    private fun discard() {
        val held = connection ?: return
        connection = null
        close(held)
    }

    /**
     * The ids of [table] from the first index that reads, else the
     * highest id `sqlite_sequence` records; else [damage] is rethrown.
     */
    private fun bind(table: PagedTable<D, *>, damage: SQLiteDatabaseCorruptException): Bound {
        for (source in table.idSources) {
            try {
                return Bound.Listed(source(connection()).toLongArray().apply { sort() })
            } catch (alsoDamaged: SQLiteDatabaseCorruptException) {
                discard()
                damage.addSuppressed(alsoDamaged)
            }
        }
        try {
            val highest = table.highestIdEver(connection())
            if (highest != null) return Bound.Sequence(highest)
        } catch (alsoDamaged: SQLiteDatabaseCorruptException) {
            discard()
            damage.addSuppressed(alsoDamaged)
        }
        throw damage
    }
}

/**
 * What one table's salvage could not read. The two cases say different things
 * to the user: only [Counted] is a count of rows.
 */
sealed interface Unreadable {
    /** How many rows of the table salvage returned, whatever any index says. */
    val recovered: Int

    /**
     * **[recovered] rows read and [unread] ids the index lists would not**, so
     * `recovered + unread` is every row the walk could see, by construction.
     *
     * Each of [unread] is a listed id at which a read failed on a fresh
     * connection. A listed id that a successful read ran across without
     * returning is one the table does not hold, and is in neither figure; a
     * row the index does not list is in [recovered] when it reads. So
     * [unread] is the rows lost unless the index disagrees with the table
     * inside the damage itself, where no read can check it: a listed id the
     * table lacks is counted there, and an unlisted row is missed. A page that
     * passes SQLCipher's HMAC is one this database wrote, but not necessarily
     * its latest version, so a write lost whole could make them disagree.
     * Nothing here has measured that.
     */
    data class Counted(override val recovered: Int, val unread: Int) : Unreadable

    /**
     * **An upper bound, not a count.** No index would read, so the walk stepped
     * one id at a time up to `sqlite_sequence`, and [ids] is how many it stepped
     * over. Ids that were never assigned or were deleted are in it, so at most
     * [ids] rows were lost and possibly none of them.
     */
    data class AtMost(override val recovered: Int, val ids: Long) : Unreadable
}

/** Which ids a table's walk steps over once damage has been met in it. */
private sealed interface Bound {
    /** Whether a failed read at [id] is counted and stepped over by one. */
    fun lists(id: Long): Boolean

    /** The first listed id at or above [id], or null when none is left. */
    fun atOrAfter(id: Long): Long?

    /** The loss, given how many rows were [recovered] and ids [stepped] over. */
    fun report(recovered: Int, stepped: Long): Unreadable

    /** Every id an index lists, sorted: 8 bytes an id, 400 KB at 50,000. */
    class Listed(private val ids: LongArray) : Bound {
        override fun lists(id: Long) = ids.binarySearch(id) >= 0

        override fun atOrAfter(id: Long): Long? {
            val at = ids.binarySearch(id)
            val index = if (at >= 0) at else -at - 1
            return if (index < ids.size) ids[index] else null
        }

        override fun report(recovered: Int, stepped: Long) =
            Unreadable.Counted(recovered, stepped.toInt())
    }

    /** No index read, so every integer up to the highest id ever assigned. */
    class Sequence(private val highest: Long) : Bound {
        override fun lists(id: Long) = id <= highest

        override fun atOrAfter(id: Long): Long? = id.takeIf { it <= highest }

        override fun report(recovered: Int, stepped: Long) = Unreadable.AtMost(recovered, stepped)
    }
}

/**
 * One table salvage pages through, as [RowReader] needs it.
 *
 * @property fetch up to `limit` rows with ids **at or above** `from`, in id
 *   order. Inclusive, which [RowReader] depends on.
 * @property idSources every id in the table, each read off a different index
 *   and never off the table's own pages.
 * @property highestIdEver the table's `sqlite_sequence` entry.
 */
class PagedTable<D, T>(
    val name: String,
    val fetch: (connection: D, from: Long, limit: Int) -> List<T>,
    val idOf: (T) -> Long,
    val idSources: List<(D) -> List<Long>>,
    val highestIdEver: (D) -> Long?,
) {
    /** The three tables `ExportJson` pages, in `Backup.SECTION_ORDER`. */
    companion object {
        val MERCHANT_RULE = PagedTable<PingedDatabase, MerchantRule>(
            name = "merchant_rule",
            fetch = { db, from, limit -> db.merchantRuleDao().pageStartingAt(from, limit) },
            idOf = { it.id },
            idSources = listOf(
                { db -> db.merchantRuleDao().idsFromCategoryIndex() },
                { db -> db.merchantRuleDao().idsFromPatternIndex() },
            ),
            highestIdEver = { db -> db.merchantRuleDao().highestIdEver() },
        )

        val RAW_CAPTURE = PagedTable<PingedDatabase, RawCapture>(
            name = "raw_capture",
            fetch = { db, from, limit -> db.rawCaptureDao().pageStartingAt(from, limit) },
            idOf = { it.id },
            idSources = listOf(
                { db -> db.rawCaptureDao().idsFromStatusIndex() },
                { db -> db.rawCaptureDao().idsFromSbnKeyIndex() },
            ),
            highestIdEver = { db -> db.rawCaptureDao().highestIdEver() },
        )

        val TXN = PagedTable<PingedDatabase, Txn>(
            name = "txn",
            fetch = { db, from, limit -> db.txnDao().pageStartingAt(from, limit) },
            idOf = { it.id },
            idSources = listOf(
                { db -> db.txnDao().idsFromRawCaptureIndex() },
                { db -> db.txnDao().idsFromOccurredAtIndex() },
            ),
            highestIdEver = { db -> db.txnDao().highestIdEver() },
        )
    }
}
