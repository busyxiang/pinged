package my.pinged.ledger.transfer

import android.content.Context
import java.io.OutputStream
import my.pinged.data.DatabaseKey
import my.pinged.data.Databases
import my.pinged.data.PingedDatabase
import my.pinged.data.Seed
import my.pinged.data.entity.Category
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn

/**
 * Design 5's salvage: [ExportJson]'s document, written from every row a
 * damaged ledger will still give up, **and restorable**.
 *
 * Read through [RowReader] on instances of its own
 * ([Databases.openAside]), never the one `Databases.shared` serves: every
 * damaged read poisons its connection, and on the shared one that is
 * capture's insert gone with it. No transaction (design 5): the file is kept
 * consistent by the filter below, and a transaction across a walk expected
 * to throw would serialise capture for its length.
 *
 * ## Every reference the import enforces, kept satisfiable
 *
 * `ImportJson.verifyReferences` refuses the whole file over any one of three,
 * and the import's own inserts over two foreign keys. Each is decided here
 * for the referring row when its target was not recovered:
 *
 * 1. **`raw_capture.duplicate_of_id`: kept, the link cleared.** The capture
 *    is the notification's own evidence and it read. Where `e04ab72` filed a
 *    copy of an undecidable original as a duplicate suspect, the original is
 *    the row that would not read and the copy, with its `PENDING`
 *    transaction, is the only record of the money left. A suspect naming no
 *    pair is a state `ParsePass` already writes when a layer-two lookup meets
 *    damage, and nothing in the app reads the link but the import's check.
 * 2. **`txn.raw_capture_id`: the transaction is left out.** Clearing it would
 *    make a captured payment read as a manual entry, which is what a null
 *    there means (spec 4's table), and spec 4 keeps captures so that every
 *    transaction can be traced to one. Design 5 makes the same call.
 *    Transactions with no capture to begin with -- manual entries -- are
 *    untouched.
 * 3. **The `Uncategorized` row: added if missing.** With `category` read
 *    whole and lacking it, a row with that name is appended under the next
 *    id; with `category` unreadable, the file carries [Seed.categories].
 * 4. **`txn.category_id`: kept, filed under `Uncategorized`**, where spec 7.1
 *    files money it cannot categorise, when the category was not read. Only
 *    categories read off the ledger count as read: with `category` lost,
 *    every transaction moves, since a seeded id matching an old one says
 *    nothing about what that id meant.
 * 5. **`merchant_rule.category_id`: the rule is left out.** A learned rule is
 *    a merchant mapped to a category and nothing else; pointed at
 *    `Uncategorized` it would go on filing that merchant there over whatever
 *    is learned next, and a rule can be learned again.
 *
 * Each is counted in [SalvageReport], so the user is told.
 *
 * ## A capture whose transaction would not read
 *
 * Written as it was read, `MATCHED` and all, because the captures section is
 * written before the transactions are reached. `ImportJson` hands such a
 * capture back to stage two, which writes its transaction again from the
 * notification; this counts them ([SalvageReport.txnsReread]) so the sheet
 * can say so.
 *
 * ## A link that points forward
 *
 * `duplicate_of_id` usually names an earlier id, but not always: layer one
 * picks the earliest *posted* match (`Dedup.EARLIEST_FIRST`), and layer two
 * names whichever capture a matching transaction came from, so a catch-up
 * arrival stored after its pair can be named by it. The walk is in id order,
 * so a capture naming a later id is held back until the section's last page
 * has been read and written after it, when the set of recovered ids is
 * complete. Deciding them by a read of the target instead could disagree
 * with the walk at the one row `RowReader` can lose although it
 * reads, and a wrong guess there refuses the whole file.
 *
 * **At most [FORWARD_HELD] are held**, the memory of one page of the walk.
 * How many a ledger has is not known -- one needs a catch-up arrival parsed
 * after a later copy of it was stored, and nothing here has measured a real
 * ledger's -- so the bound does not rest on a guess: past it, a capture
 * naming a later id is written at once with its link cleared, and counted.
 * Nothing in the app reads the link but the import's check (rule 1 above).
 *
 * ## The ledger as the salvage found it, and what changed meanwhile
 *
 * Capture runs beside the walk (no transaction, above), so a row can name
 * another that arrived after the section holding it was read. Every filter
 * above therefore tells "could not be read" from "arrived since", so that
 * neither sentence is said of the other's rows:
 *
 * - **Captures, by the highest id `sqlite_sequence` held when the salvage
 *   began**, read before anything else. Captures are never deleted, so one at
 *   or below it that was not recovered is one the walk could not read; one
 *   above it arrived since, and a transaction or a link naming it is counted
 *   apart. Unreadable itself, it is taken as unbounded, and every miss is
 *   called unreadable.
 * - **Categories, by the highest id the `category` read returned.** A row
 *   naming a category above it names one made since; with `category` lost,
 *   none read, and every miss is unreadable.
 *
 * ## What it costs, measured
 *
 * On emulator-5554, to a file in the cache directory, three runs each, a
 * ledger of N captures with a transaction each and `damageMidFile`'s damage
 * (a `raw_capture` leaf, 19 rows, at both sizes): **576-675 ms at 10,000
 * captures (11.0 MB written) and 2,049-2,276 ms at 50,000 (55.6 MB)**.
 * What grows with damage is a read and a reopen per unreadable row: a
 * damaged interior page at 50,000 left 7,410 unreadable and took
 * **9,721-10,906 ms**, 1.3-1.5 ms a row, with the key unwrapped once for the
 * whole salvage. Unwrapped at every reopen, 0.60 ms each on this emulator's
 * software keystore, the same walk took 15,661-16,792 ms; a phone's
 * hardware-backed keystore was not measured, and is where reading it once
 * saves the most.
 *
 * **Capture does not wait for it.** Each reopen takes `Databases`' monitor
 * for the open, and `Databases.shared` takes no lock while its instance is
 * current. Measured beside that 7,410-reopen salvage, an insert through
 * `CaptureStorage.guarded`, every 20 ms: median 0.90-0.91 ms, p95 1.32-1.83
 * ms, longest 5.07-5.20 ms over 412-421 inserts in two runs; alone, before
 * and after, median 1.02 and 0.88 ms, longest 3.79 and 2.44 ms over 200
 * each. Rows inserted meanwhile are read too, if the walk has not passed
 * their ids; see above for what is said of those it has.
 */
object SalvageJson {
    /**
     * Writes what reads onto [out] and says what did not. [onProgress] is
     * told rows written plus ids stepped over, since a page over damage can
     * step for seconds without writing one.
     *
     * Throws whatever [DatabaseKey.existingRawKeyPassphrase] and
     * [Databases.openAside] refuse for, and damage the reader has nothing to
     * bound by (`RowReader.bind`); either leaves [out] incomplete,
     * and the caller deletes it, as for [TransferCancelledException].
     */
    fun write(
        context: Context,
        out: OutputStream,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): SalvageReport {
        var written = 0
        var stepped = 0L
        val references = References()
        // Once for every reopen, and wiped once the reader has closed the
        // last instance it keyed; see `Databases.openAside`.
        val key = DatabaseKey.existingRawKeyPassphrase(context)
        try {
            RowReader(
                open = { Databases.openAside(context, key) },
                close = { Databases.closeAside(it) },
                isCancelled = isCancelled,
                onStep = { soFar ->
                    stepped = soFar
                    onProgress((written + stepped).toInt())
                },
            ).use { reader ->
                references.freeze(reader)
                val rows = ExportJson.writeDocument(
                    out = out,
                    schemaVersion = PingedDatabase.VERSION,
                    source = references.over(reader),
                    onProgress = { soFar ->
                        written = soFar
                        onProgress((written + stepped).toInt())
                    },
                    isCancelled = isCancelled,
                )
                return references.report(rows, reader.unreadable)
            }
        } finally {
            key.fill(0)
        }
    }

    /**
     * How many captures naming a later id [SalvageJson] holds back at once;
     * see its KDoc. One [ExportJson.PAGE], whose KDoc puts that many captures
     * with their text at about a megabyte.
     */
    const val FORWARD_HELD = ExportJson.PAGE
}

/**
 * What a salvage wrote and what it could not, for the screen to say.
 *
 * Four adjustments are counted twice, by why their target is not in the
 * file: the plain name for one that could not be read, `…Since` for one that
 * arrived after the salvage began (see [SalvageJson]'s KDoc).
 *
 * @property rows every row in the file, of every table.
 * @property unreadable per paged table, what [RowReader] stepped
 *   over; a table that lost nothing has no entry. `txn`'s
 *   [Unreadable.recovered] counts transactions read, both left-out counts
 *   included.
 * @property lostWhole the small tables read in one query that met damage,
 *   by table name, and so are not in the file.
 * @property txnsLeftOut transactions read and left out, their capture lost.
 * @property linksCleared captures kept with `duplicate_of_id` cleared, its
 *   target lost.
 * @property txnsUncategorized transactions kept and filed under
 *   `Uncategorized`, their category lost.
 * @property rulesLeftOut learned rules read and left out, their category lost.
 * @property uncategorizedAdded the file carries an `Uncategorized` the
 *   ledger's readable `category` table did not.
 * @property linksUnheld captures naming a later id, kept with the link
 *   cleared because [SalvageJson.FORWARD_HELD] were already held.
 * @property txnsReread `MATCHED` captures in the file whose transaction is
 *   not: stage two writes each again after the restore (`ImportJson`).
 */
data class SalvageReport(
    val rows: Int,
    val unreadable: Map<String, Unreadable>,
    val lostWhole: Set<String>,
    val txnsLeftOut: Int,
    val linksCleared: Int,
    val txnsUncategorized: Int,
    val rulesLeftOut: Int,
    val uncategorizedAdded: Boolean,
    val txnsLeftOutSince: Int = 0,
    val linksClearedSince: Int = 0,
    val txnsUncategorizedSince: Int = 0,
    val rulesLeftOutSince: Int = 0,
    val linksUnheld: Int = 0,
    val txnsReread: Int = 0,
)

/** The filter of [SalvageJson]'s KDoc, as the sections are read in order. */
private class References {
    private val knownCategories = HashSet<Long>()
    private var uncategorized = 0L

    /** The highest category id read; a higher one was made since. Null with `category` lost. */
    private var categoriesRead: Long? = null

    /** The highest capture id at the start; a higher one arrived since. */
    private var capturesAtStart = Long.MAX_VALUE
    private val recovered = AscendingIds()

    /** The `MATCHED` captures written, and how many of them a written transaction names. */
    private val matched = AscendingIds()
    private var matchedWithTxn = 0
    private val forward = ArrayList<RawCapture>()
    private val lostWhole = LinkedHashSet<String>()
    private var txnsLeftOut = 0
    private var linksCleared = 0
    private var txnsUncategorized = 0
    private var rulesLeftOut = 0
    private var uncategorizedAdded = false
    private var txnsLeftOutSince = 0
    private var linksClearedSince = 0
    private var txnsUncategorizedSince = 0
    private var rulesLeftOutSince = 0
    private var linksUnheld = 0

    /**
     * [capturesAtStart], before any section is read. `sqlite_sequence` has
     * no entry before the first capture, so none means every capture arrived
     * since; unreadable, it stays unbounded.
     */
    fun freeze(reader: RowReader<PingedDatabase>) {
        reader.whole("sqlite_sequence") { listOf(it.rawCaptureDao().highestIdEver() ?: 0L) }
            ?.let { capturesAtStart = it.single() }
    }

    fun over(reader: RowReader<PingedDatabase>) = DocumentSource(
        categories = { categories(reader.whole("category") { it.categoryDao().all() }) },
        captureSources = { reader.whole("capture_source") { it.captureSourceDao().all() } ?: lost("capture_source") },
        captureDays = { reader.whole("capture_day") { it.captureDayDao().all() } ?: lost("capture_day") },
        merchantRules = PagedRows(
            nextPage = { after -> reader.page(PagedTable.MERCHANT_RULE, after, ExportJson.PAGE) },
            idOf = MerchantRule::id,
            keep = ::rule,
        ),
        rawCaptures = PagedRows(
            nextPage = { after -> reader.page(PagedTable.RAW_CAPTURE, after, ExportJson.PAGE) },
            idOf = RawCapture::id,
            keep = ::capture,
            afterLastPage = { forward.map(::linked) },
        ),
        txns = PagedRows(
            nextPage = { after -> reader.page(PagedTable.TXN, after, ExportJson.PAGE) },
            idOf = Txn::id,
            keep = ::txn,
        ),
        // Kept whole, with nothing filtered: an alias or a name refers to no
        // row, so one whose transactions did not read refers to nothing worse
        // than a key no row carries, which every query ignores.
        merchantAliases = {
            reader.whole("merchant_alias") { it.merchantIdentityDao().allAliases() } ?: lost("merchant_alias")
        },
        merchantNames = {
            reader.whole("merchant_name") { it.merchantIdentityDao().allNames() } ?: lost("merchant_name")
        },
    )

    fun report(rows: Int, unreadable: Map<String, Unreadable>) = SalvageReport(
        rows = rows,
        unreadable = unreadable,
        lostWhole = lostWhole.toSet(),
        txnsLeftOut = txnsLeftOut,
        linksCleared = linksCleared,
        txnsUncategorized = txnsUncategorized,
        rulesLeftOut = rulesLeftOut,
        uncategorizedAdded = uncategorizedAdded,
        txnsLeftOutSince = txnsLeftOutSince,
        linksClearedSince = linksClearedSince,
        txnsUncategorizedSince = txnsUncategorizedSince,
        rulesLeftOutSince = rulesLeftOutSince,
        linksUnheld = linksUnheld,
        txnsReread = matched.size - matchedWithTxn,
    )

    private fun <T> lost(table: String): List<T> {
        lostWhole += table
        return emptyList()
    }

    private fun categories(read: List<Category>?): List<Category> {
        if (read == null) {
            lostWhole += "category"
            val seeded = Seed.categories().mapIndexed { index, category -> category.copy(id = index + 1L) }
            uncategorized = seeded.single { it.name == Seed.UNCATEGORIZED }.id
            return seeded
        }
        read.mapTo(knownCategories) { it.id }
        val highest = read.maxOfOrNull { it.id } ?: 0L
        categoriesRead = highest
        read.firstOrNull { it.name == Seed.UNCATEGORIZED }?.let {
            uncategorized = it.id
            return read
        }
        uncategorizedAdded = true
        uncategorized = highest + 1
        return read + Seed.categories().single { it.name == Seed.UNCATEGORIZED }.copy(
            id = uncategorized,
            sortOrder = (read.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
    }

    /** Whether a category missing from the file was made after `category` was read. */
    private fun madeSince(category: Long): Boolean = categoriesRead?.let { category > it } ?: false

    private fun rule(rule: MerchantRule): MerchantRule? {
        if (rule.categoryId in knownCategories) return rule
        if (madeSince(rule.categoryId)) rulesLeftOutSince++ else rulesLeftOut++
        return null
    }

    /** Called in id order, so every earlier id this walk will recover it already has. */
    private fun capture(capture: RawCapture): RawCapture? {
        recovered.add(capture.id)
        if (capture.parseStatus == ParseStatus.MATCHED) matched.add(capture.id)
        val target = capture.duplicateOfId ?: return capture
        if (target >= capture.id) {
            if (forward.size < SalvageJson.FORWARD_HELD) {
                forward += capture
                return null
            }
            linksUnheld++
            return capture.copy(duplicateOfId = null)
        }
        return linked(capture)
    }

    /** [capture] as written: its link kept if it names a recovered capture, else cleared. */
    private fun linked(capture: RawCapture): RawCapture {
        val target = capture.duplicateOfId ?: return capture
        if (target in recovered) return capture
        if (target > capturesAtStart) linksClearedSince++ else linksCleared++
        return capture.copy(duplicateOfId = null)
    }

    private fun txn(txn: Txn): Txn? {
        val capture = txn.rawCaptureId
        if (capture != null && capture !in recovered) {
            if (capture > capturesAtStart) txnsLeftOutSince++ else txnsLeftOut++
            return null
        }
        // `txn.raw_capture_id` is unique, so no capture is counted twice.
        if (capture != null && capture in matched) matchedWithTxn++
        if (txn.categoryId in knownCategories) return txn
        if (madeSince(txn.categoryId)) txnsUncategorizedSince++ else txnsUncategorized++
        return txn.copy(categoryId = uncategorized)
    }
}

/**
 * Ids added in ascending order, as a walk returns them: 8 bytes each, 400 KB
 * at 50,000, searched by halving.
 */
private class AscendingIds {
    private var ids = LongArray(1024)
    var size = 0
        private set

    fun add(id: Long) {
        check(size == 0 || id > ids[size - 1]) { "Capture $id arrived after ${ids[size - 1]}" }
        if (size == ids.size) ids = ids.copyOf(size * 2)
        ids[size++] = id
    }

    operator fun contains(id: Long): Boolean = ids.binarySearch(id, 0, size) >= 0
}
