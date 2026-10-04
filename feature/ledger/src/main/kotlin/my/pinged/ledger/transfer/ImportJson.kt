package my.pinged.ledger.transfer

import my.pinged.data.LocalDate
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.util.JsonReader
import android.util.JsonToken
import android.util.MalformedJsonException
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.Callable
import my.pinged.data.Databases
import my.pinged.data.PingedDatabase
import my.pinged.data.Seed
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.CaptureDay
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.Category
import my.pinged.parse.ExclusionReason
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RuleOrigin
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction

/**
 * Spec 11.2's restore: [ExportJson]'s document read back with `JsonReader`,
 * straight off the stream.
 *
 * Import is the dangerous half -- the only writer in this app that can destroy
 * history -- so the decisions below all lean the same way.
 *
 * ## Into a database that already has data: refused, and not merged
 *
 * [read] throws [ImportNotEmptyException] before writing anything if `txn`,
 * `raw_capture` or `merchant_rule` holds a row. Neither merge nor
 * replace-by-id can be made correct:
 *
 * - Ids collide, and re-keying means rewriting `txn.raw_capture_id`,
 *   `raw_capture.duplicate_of_id`, `txn.category_id` and
 *   `merchant_rule.category_id` consistently across a document this
 *   deliberately never holds in memory.
 * - Replace-by-id destroys the newer row: this month's transaction 41
 *   overwritten by last month's. A restore that deletes data is worse than one
 *   that refuses to run.
 * - Duplicate detection cannot be re-run. Spec 7.2's layers are a capture-time
 *   judgement over a ten-minute window and nothing stores the pairs, so double
 *   counting introduced by a merge would stay.
 *
 * The way through is spec 11.3's delete-all-data, which the message says.
 *
 * `category`, `capture_source` and `capture_day` are exempt and replaced
 * wholesale, because the target's copy is either the factory seed or a few
 * minutes of a fresh install.
 *
 * ## A partial or truncated file: nothing is written
 *
 * The emptiness check, every delete, every insert and the closing integrity
 * checks run inside **one** `runInTransaction`, so a truncated file rolls back
 * to where it started. Chunked commits would bound the WAL and would also mean
 * a failed restore leaving two thirds of a ledger, with no way to tell which
 * two thirds. The file is still read a row at a time, so the single
 * transaction bounds durability, not memory.
 *
 * ## A file from a future export version: refused by number
 *
 * `format` is the first name in the document and [read] gates on it before it
 * believes a single row. See [Backup.FORMAT_VERSION].
 *
 * ## Rows whose references do not resolve
 *
 * `txn.category_id` and `merchant_rule.category_id` are `ON DELETE RESTRICT`
 * foreign keys enforced pool-wide by `DatabaseFactory`, so a row naming an
 * absent category fails with SQLite 787 and takes the import down.
 *
 * Two references the **schema does not enforce** are checked explicitly at the
 * end of the same transaction: `raw_capture.duplicate_of_id` has no foreign
 * key, and `txn.raw_capture_id` has a unique index and no foreign key. The last
 * check is that the restored `category` table still holds the row spec 7.1
 * files unrecognised money into -- without it the ledger opens and then throws
 * on the first capture, which is a restore that looks successful and is not.
 */
object ImportJson {
    /**
     * How often the row counter is reported and cancellation is asked about. A
     * restore of spec 15.8's fixture is 50,000 captures, so a hundred updates;
     * per row would call two lambdas fifty thousand times to make the progress bar
     * no smoother than the screen can draw it.
     */
    const val CHECK_EVERY = 500

    /**
     * **A closed [db] is refused before the transaction opens.**
     * `runInTransaction` reopens a closed instance's file (ruling R42), so
     * without the check an import handed a stale handle writes a ledger
     * outside `Databases` and outside its gate.
     *
     * [Databases.requireLive] rather than [Databases.whileLive]: [db] is always
     * its caller's own -- `Restore`'s probe or its rebuild -- which
     * `Databases` never serves and so never closes, and holding that object's
     * monitor across a whole import would stall every open of the shared
     * database behind a restore's validation, for seconds at 50,000 rows.
     */
    fun read(
        db: PingedDatabase,
        input: InputStream,
        onProgress: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ImportReport {
        val budget = RowBudgetReader(InputStreamReader(input, Charsets.UTF_8))
        val reader = JsonReader(budget)
        Databases.requireLive(db)
        val report = db.runInTransaction(
            Callable { readDocument(db, reader, budget, onProgress, isCancelled) },
        )
        return report
    }

    private fun readDocument(
        db: PingedDatabase,
        reader: JsonReader,
        budget: RowBudgetReader,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
    ): ImportReport {
        requireEmptyLedger(db)
        val state = Progress(onProgress, isCancelled)
        val counts = LinkedHashMap<String, Int>()
        var exportedAt: Long? = null
        var lastSection = -1

        try {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw ImportFormatException(
                    "This file does not start with a JSON object, so it is not a " +
                        "Pinged backup. Nothing was imported.",
                )
            }
            reader.beginObject()

            val first = if (reader.hasNext()) reader.nextName() else null
            if (first != Backup.FIELD_FORMAT) {
                throw ImportFormatException(
                    "A Pinged backup names '${Backup.FIELD_FORMAT}' first, so that a " +
                        "file from a different version can be recognised before any of " +
                        "it is believed. This one names " +
                        (first?.let { "'$it'" } ?: "nothing at all") +
                        ". Nothing was imported.",
                )
            }
            val format = reader.nextInt()
            if (format != Backup.FORMAT_VERSION) {
                throw ImportFormatException(
                    "This backup is in format version $format and this build of " +
                        "Pinged reads version ${Backup.FORMAT_VERSION}. " +
                        (if (format > Backup.FORMAT_VERSION) {
                            "It was written by a newer build. Importing the parts this " +
                                "build understands would silently drop the rest, so it is " +
                                "refused instead; update Pinged and try again."
                        } else {
                            "It was written by an older build that this one can no longer " +
                                "read."
                        }) + " Nothing was imported.",
                )
            }

            while (reader.hasNext()) {
                val name = reader.nextName()
                val sectionIndex = Backup.SECTION_ORDER.indexOf(name)
                if (sectionIndex >= 0) {
                    if (sectionIndex <= lastSection) {
                        throw ImportFormatException(
                            "'$name' appears out of order in this backup. The sections " +
                                "have to arrive as ${Backup.SECTION_ORDER.joinToString(", ")} " +
                                "because the rows are written to the database as they are " +
                                "read, and a transaction cannot be stored before the " +
                                "category it belongs to. Nothing was imported.",
                        )
                    }
                    lastSection = sectionIndex
                    counts[name] = readSection(db, reader, budget, name, state)
                } else if (name == Backup.FIELD_EXPORTED_AT) {
                    exportedAt = reader.nextLong()
                } else {
                    // Includes `app_schema_version`, which is diagnostic and never a gate, and
                    // anything a future build adds beside the sections. Safe here and only here:
                    // an unknown *top-level* name cannot be a column missing from a row, and the
                    // format version has already established which build wrote this.
                    reader.skipValue()
                }
            }
            reader.endObject()

            // No check for content after the closing brace: `android.util.JsonReader` is
            // not lenient, so a second object appended to a real export throws
            // `MalformedJsonException` from `peek()` rather than reaching a branch here.
            reader.peek()
        } catch (e: MalformedJsonException) {
            throw ImportFormatException(malformed(e), e)
        } catch (e: EOFException) {
            throw ImportFormatException(truncated(), e)
        } catch (e: IOException) {
            throw ImportFormatException(
                "This backup could not be read to the end: ${e.message}. That is either " +
                    "a truncated file or a storage error. Nothing was imported.",
                e,
            )
        } catch (e: NumberFormatException) {
            throw ImportFormatException(
                "A number in this backup is not one this build can read: ${e.message}. " +
                    "Nothing was imported.",
                e,
            )
        } catch (e: IllegalStateException) {
            // What android.util.JsonReader raises when the token it meets is not the one
            // the caller asked for -- an array where an object belongs, for instance.
            throw ImportFormatException(malformed(e), e)
        } catch (e: SQLiteConstraintException) {
            throw ImportIntegrityException(constraint(e), e)
        } catch (e: SQLiteException) {
            // Everything else the database can raise: a full disk, an IO error, a file
            // that stopped being readable mid-restore. Without this they leave the sealed
            // `ImportException` hierarchy `Backup` documents as "everything an import can
            // refuse for" -- and a full disk is realistic in exactly spec 11.1's
            // situation, restoring 50,000 captures onto a new phone.
            throw ImportIntegrityException(
                "This backup could not be written to the database: ${e.message}. That " +
                    "is a storage problem on this device rather than a problem with " +
                    "the file. Nothing was imported.",
                e,
            )
        } catch (e: IllegalArgumentException) {
            // `Txn.requireStorable`, reached through `TxnDao.insert`: a backup carrying a
            // PENDING transaction with no reason is refused by the same guard the app's
            // own write path goes through, so the importer cannot write a row no other
            // code here can produce.
            throw ImportIntegrityException(
                "A transaction in this backup contradicts itself: ${e.message} " +
                    "Nothing was imported.",
                e,
            )
        }

        val missing = Backup.SECTION_ORDER.filterNot { counts.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw ImportFormatException(
                "This backup has no ${missing.joinToString(", ")} section. Every Pinged " +
                    "export writes all ${Backup.SECTION_ORDER.size}, even when a table is " +
                    "empty, so a file missing one has been cut short or edited. Nothing " +
                    "was imported.",
            )
        }

        verifyReferences(db)
        requeueCapturesWithoutTheirTxn(db)

        val report = ImportReport(
            formatVersion = Backup.FORMAT_VERSION,
            exportedAt = exportedAt,
            categories = counts.getValue(Backup.CATEGORIES),
            captureSources = counts.getValue(Backup.CAPTURE_SOURCES),
            captureDays = counts.getValue(Backup.CAPTURE_DAYS),
            merchantRules = counts.getValue(Backup.MERCHANT_RULES),
            rawCaptures = counts.getValue(Backup.RAW_CAPTURES),
            txns = counts.getValue(Backup.TXNS),
        )
        state.report()
        return report
    }

    // ---- preconditions and postconditions -------------------------------

    private fun requireEmptyLedger(db: PingedDatabase) {
        val held = buildList {
            db.rawCaptureDao().countAll().let { if (it > 0) add("$it captured notification(s)") }
            db.txnDao().countAll().let { if (it > 0) add("$it transaction(s)") }
            db.merchantRuleDao().countAll().let { if (it > 0) add("$it learned rule(s)") }
        }
        if (held.isEmpty()) return
        throw ImportNotEmptyException(
            "This device already holds ${held.joinToString(" and ")}, and importing a " +
                "backup on top of them would have to decide which copy of each row to " +
                "keep. There is no answer to that which does not throw away something " +
                "the user has: the two files number their rows from one, so the newer " +
                "row and the older row have the same id. Export what is here first if " +
                "it matters, then delete all data (spec 11.3) and import into the empty " +
                "ledger. Nothing was imported.",
        )
    }

    /**
     * The two references SQLite is not checking, plus the one seeded row the
     * app cannot run without. All three inside the import's transaction, so a
     * failure rolls the restore back rather than leaving a ledger that opens
     * and then misbehaves.
     */
    private fun verifyReferences(db: PingedDatabase) {
        val dangling = db.rawCaptureDao().danglingDuplicateOfCount()
        if (dangling > 0) {
            throw ImportIntegrityException(
                "$dangling capture(s) in this backup are marked as duplicates of a " +
                    "capture the backup does not contain. Spec 7.2's duplicate marking " +
                    "points at the original notification, and a pointer at nothing would " +
                    "show up later as a review item that cannot be explained. Nothing " +
                    "was imported.",
            )
        }
        val orphans = db.txnDao().danglingRawCaptureIdCount()
        if (orphans > 0) {
            throw ImportIntegrityException(
                "$orphans transaction(s) in this backup refer to a captured notification " +
                    "the backup does not contain. Spec 4 keeps raw captures forever so " +
                    "that every transaction can be traced back to the notification that " +
                    "produced it, and these could not be. Nothing was imported.",
            )
        }
        db.categoryDao().uncategorizedIdOrNull() ?: throw ImportIntegrityException(
            "This backup's categories do not include one named '${Seed.UNCATEGORIZED}'. " +
                "Spec 7.1 files money it cannot categorise into that row, so a ledger " +
                "without it would restore cleanly and then fail on the first capture. " +
                "Nothing was imported.",
        )
    }

    /**
     * Gives stage two back every `MATCHED` capture the file holds without its
     * transaction, so that the money comes back once stage two has run.
     *
     * **The file that holds one is a salvage whose `txn` page would not
     * read.** Its captures section is written first, at the status each
     * capture had; a capture whose transaction then did not read stays
     * `MATCHED` with nothing behind it, and `ParsePass` claims only `NEW`
     * and `Reparse` only revisitable statuses, so restored as it is the money
     * is gone for good, its notification sitting in the ledger.
     *
     * **Here rather than in the salvage**, which would have to know, at each
     * capture, whether a transaction it has not reached yet will read: the
     * sections arrive in [Backup.SECTION_ORDER], and asking `txn`'s index
     * ahead of the walk could disagree with the walk at exactly the damaged
     * rows -- the reason `SalvageJson` holds forward links back rather than
     * reading their targets. Here the restored `txn` table is the answer.
     *
     * **A backup the app exported moves nothing**: there, every `MATCHED`
     * capture has its transaction (`RawCaptureDao.requeueMatchedWithoutTxn`),
     * and nothing in the app deletes a transaction and keeps its capture.
     * Measured on emulator-5554 at 50,000 `MATCHED` captures, inside a
     * transaction, three runs each: 19.4-20.0 ms with every transaction
     * present, moving none, and 124-128 ms with one in fifty missing, moving
     * 1,000 -- against 2.39-2.40 s for the whole restore of 50,000
     * (`DatabaseBeingDeletedException`'s KDoc).
     *
     * Stage two's next run writes them -- the next notification, or Pinged's
     * next foreground (`ListenerStatus.onAppForeground`) -- as it does any
     * `NEW` capture a backup carries.
     */
    private fun requeueCapturesWithoutTheirTxn(db: PingedDatabase) {
        db.rawCaptureDao().requeueMatchedWithoutTxn()
    }

    // ---- sections --------------------------------------------------------

    /**
     * The section named [name], read and inserted through its own declaration.
     *
     * A lookup rather than a `when`: [Backup.SECTION_ORDER] is derived from
     * [ALL_SECTIONS], so anything reaching here is in that list by construction
     * and a missing arm is impossible to forget. `first`, not `firstOrNull` --
     * `readDocument` skips an unrecognised name before ever calling this.
     */
    private fun readSection(
        db: PingedDatabase,
        reader: JsonReader,
        budget: RowBudgetReader,
        name: String,
        state: Progress,
    ): Int {
        val section = ALL_SECTIONS.first { it.name == name }
        section.clearFirst?.invoke(db)
        return readArray(reader, budget, section, state) { rows -> section.insert(db, rows) }
    }

    private fun readArray(
        reader: JsonReader,
        budget: RowBudgetReader,
        section: BackupSection<*>,
        state: Progress,
        insert: (List<BackupRow>) -> Unit,
    ): Int {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            throw ImportFormatException(
                "'${section.name}' in this backup is not a list of rows. Nothing was " +
                    "imported.",
            )
        }
        reader.beginArray()
        var read = 0
        // One chunk at a time, never the document. [CHECK_EVERY] is reused as the
        // batch size rather than given a constant of its own. The flush and the
        // progress tick do not coincide -- `chunk` is per section and `Progress.rows`
        // accumulates across the document -- and nothing depends on them agreeing.
        val chunk = ArrayList<BackupRow>(CHECK_EVERY)
        while (reader.hasNext()) {
            budget.startRow()
            chunk += readRow(reader, section)
            read++
            if (chunk.size == CHECK_EVERY) {
                insert(chunk)
                chunk.clear()
            }
            state.rowDone(section.name)
        }
        if (chunk.isNotEmpty()) insert(chunk)
        reader.endArray()
        return read
    }

    private fun readRow(reader: JsonReader, section: BackupSection<*>): BackupRow {
        val table = section.table
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw ImportFormatException(
                "A '$table' entry in this backup is not an object. Nothing was imported.",
            )
        }
        reader.beginObject()
        // Sized for the row. At the default capacity of 16 a 25-column
        // raw_capture resized twice per row, rehashing ~36 entries each time.
        val values = HashMap<String, Any?>(section.columns.size * 4 / 3 + 1)
        while (reader.hasNext()) {
            val name = reader.nextName()
            values[name] = when (reader.peek()) {
                JsonToken.NULL -> {
                    reader.nextNull()
                    null
                }

                JsonToken.BOOLEAN -> reader.nextBoolean()
                // `nextLong()` and not the obvious choice, because
                // `android.util.JsonReader.nextLong` falls back to `Double.parseDouble` and
                // casts: the round trip compares equal for 9223372036854775808, so no
                // `NumberFormatException` is raised and an `amount_sen` one past `Long.MAX`
                // stores as `Long.MAX` sen. Reading the token as text and parsing it here
                // refuses what does not fit.
                JsonToken.NUMBER -> reader.nextString().toLongOrNull()
                    ?: throw ImportFormatException(
                        "A number in '$table' is not a whole number this build can " +
                            "read. Nothing was imported.",
                    )
                JsonToken.STRING -> reader.nextString()
                else -> {
                    // A nested object or array. No column of this format is
                    // one; skip it so an unknown name a future build added
                    // cannot stop the read, and record it as unreadable so
                    // that a *known* name arriving in that shape still fails.
                    reader.skipValue()
                    UNREADABLE
                }
            }
        }
        reader.endObject()
        return BackupRow(table, values)
    }

    // ---- rows ------------------------------------------------------------
    //
    // One builder per entity. These name every column, which is exactly the
    // duplication `Backup`'s column list exists to police: the list is what the
    // export writes, these are what the import reads, and
    // `RoundTripTest.everyFieldOfEveryEntitySurvives` compares the two by
    // reflection over a row with nothing left at its default value.

    internal fun category(r: BackupRow) = Category(
        id = r.rowId("id"),
        name = r.text("name"),
        iconKey = r.text("icon_key"),
        sortOrder = r.int("sort_order"),
        isProtected = r.bool("is_protected"),
    )

    internal fun captureSource(r: BackupRow) = CaptureSource(
        pkg = r.text("pkg"),
        label = r.text("label"),
        enabled = r.bool("enabled"),
        isAuthoritative = r.bool("is_authoritative"),
        firstSeenAt = r.long("first_seen_at"),
        lastNotificationAt = r.optLong("last_notification_at"),
        expectedMonthlyCount = r.optInt("expected_monthly_count"),
    )

    internal fun captureDay(r: BackupRow) = CaptureDay(
        localDate = LocalDate(r.int("local_date")),
        listenerBound = r.bool("listener_bound"),
        sawAnyNotification = r.bool("saw_any_notification"),
    )

    internal fun merchantRule(r: BackupRow) = MerchantRule(
        id = r.rowId("id"),
        matchType = r.enum("match_type", MatchType.entries),
        pattern = r.text("pattern"),
        merchantDisplay = r.text("merchant_display"),
        categoryId = r.long("category_id"),
        origin = r.enum("origin", RuleOrigin.entries),
        priority = r.int("priority"),
        hitCount = r.int("hit_count"),
        scopedPackage = r.text("scoped_package"),
    )

    internal fun rawCapture(r: BackupRow) = RawCapture(
        id = r.rowId("id"),
        sourcePackage = r.text("source_package"),
        postedAt = r.long("posted_at"),
        whenMillis = r.optLong("when_millis"),
        capturedAt = r.long("captured_at"),
        sbnKey = r.text("sbn_key"),
        notifId = r.int("notif_id"),
        notifTag = r.optText("notif_tag"),
        userHandle = r.int("user_handle"),
        channelId = r.optText("channel_id"),
        flags = r.int("flags"),
        arrival = r.enum("arrival", Arrival.entries),
        title = r.optText("title"),
        text = r.optText("text"),
        bigText = r.optText("big_text"),
        subText = r.optText("sub_text"),
        extrasJson = r.optText("extras_json"),
        contentHash = r.text("content_hash"),
        parseStatus = r.enum("parse_status", ParseStatus.entries),
        rejectedByRuleId = r.optText("rejected_by_rule_id"),
        matchedRuleId = r.optText("matched_rule_id"),
        rejectCollisionId = r.optText("reject_collision_id"),
        packVersion = r.int("pack_version"),
        duplicateOfId = r.optLong("duplicate_of_id"),
        userRejectRuleId = r.optRowId("user_reject_rule_id"),
    )

    internal fun txn(r: BackupRow) = Txn(
        id = r.rowId("id"),
        rawCaptureId = r.optLong("raw_capture_id"),
        amountSen = r.long("amount_sen"),
        currency = r.text("currency"),
        direction = r.enum("direction", Direction.entries),
        occurredAt = r.long("occurred_at"),
        localDate = LocalDate(r.int("local_date")),
        merchantRaw = r.optText("merchant_raw"),
        merchantDisplay = r.optText("merchant_display"),
        merchantKey = r.optText("merchant_key"),
        categoryId = r.long("category_id"),
        sourcePackage = r.optText("source_package"),
        sourceLabel = r.optText("source_label"),
        confidence = r.enum("confidence", Confidence.entries),
        state = r.enum("state", TxnState.entries),
        pendingReason = r.optEnum("pending_reason", PendingReason.entries),
        isExcluded = r.bool("is_excluded"),
        exclusionReason = r.optEnum("exclusion_reason", ExclusionReason.entries),
        note = r.optText("note"),
        userEdited = r.bool("user_edited"),
        createdAt = r.long("created_at"),
        updatedAt = r.long("updated_at"),
    )

    // ---- messages --------------------------------------------------------

    /**
     * Deliberately does not quote [e]. `android.util.JsonReader` says things like
     * "Expected BEGIN_OBJECT but was STRING at line 1 column 4931", which is
     * [BackupRow]'s own example of a message that tells a restoring user nothing
     * they can act on. The cause is still attached for the log.
     */
    private fun malformed(@Suppress("UNUSED_PARAMETER") e: Exception) =
        "This file is not a Pinged backup, or it has been changed since it was " +
            "written. If it was copied off the device or opened in an editor it may " +
            "have been altered or cut short. Nothing was imported."

    private fun truncated() =
        "This backup ends in the middle of the data. It is incomplete -- a copy that " +
            "was interrupted, or an export that was cancelled. Nothing was imported."

    private fun constraint(e: SQLiteException) =
        "This backup's rows do not fit together: ${e.message}. The usual cause is a " +
            "transaction or a learned rule naming a category the backup does not " +
            "contain, which the database refuses rather than storing money that " +
            "belongs nowhere. Nothing was imported."
}

/** Row counting, progress reporting and cancellation, in one place. */
private class Progress(
    private val onProgress: (Int) -> Unit,
    private val isCancelled: () -> Boolean,
) {
    private var rows = 0

    fun rowDone(section: String) {
        rows++
        // Asked every row, not every [ImportJson.CHECK_EVERY]. Below the modulo,
        // cancelling an import of under 500 rows did nothing: the restore ran to
        // completion and `requireEmptyLedger` then refused the retry. `isCancelled` is
        // a lambda read, and 50,000 of them is nothing beside 50,000 inserts.
        if (isCancelled()) {
            throw TransferCancelledException(
                "Import cancelled after $rows rows, while reading $section. It ran " +
                    "inside one transaction, so nothing was written.",
            )
        }
        if (rows % ImportJson.CHECK_EVERY != 0) return
        onProgress(rows)
    }

    fun report() = onProgress(rows)
}

/** A value that was in the file in a shape no column of this format uses. */
private val UNREADABLE = Any()

/**
 * Bounds one row of the document, so nothing in a file decides how much memory
 * this process uses.
 *
 * Every other count in the import is bounded; the size of a single row was
 * whatever the file said. `readRow` builds a map from the names it meets, so
 * one object carrying a million unrecognised names is a million entries, and
 * `JsonReader.nextString` materialises one value whole. Either is an
 * out-of-memory kill during a restore.
 *
 * Counting characters as they leave the reader is the only place that can
 * bound both: the refusal has to happen *while* an enormous scalar is being
 * read, not after it exists.
 */
private class RowBudgetReader(private val delegate: java.io.Reader) : java.io.Reader() {
    private var consumed = 0L

    /** Called at each row boundary; between rows the count is meaningless. */
    fun startRow() {
        consumed = 0L
    }

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        val n = delegate.read(cbuf, off, len)
        if (n > 0) {
            consumed += n
            if (consumed > BUDGET_CHARS) {
                throw ImportFormatException(
                    "A single row of this backup is larger than $BUDGET_CHARS characters. " +
                        "No notification can be: Android delivers them through a Binder " +
                        "transaction of about a megabyte, so a row this size was not " +
                        "captured by this app. Nothing was imported.",
                )
            }
        }
        return n
    }

    override fun close() = delegate.close()

    private companion object {
        /**
         * One megabyte of characters per row -- the size of the largest row this app
         * can ever have written. A notification reaches a listener through a Binder
         * transaction capped at roughly one megabyte, so a `raw_capture` exceeding
         * this could not have been delivered to the listener that stored it.
         *
         * Generous on purpose: a bound too tight means a legitimate backup that will
         * not restore, which costs more than the memory it saves.
         */
        const val BUDGET_CHARS = 1_000_000L
    }
}

/**
 * One row of one section, read into memory and no further.
 *
 * Every accessor names the table and the column it failed on, because spec
 * 11.2's user is restoring after losing something.
 *
 * **An absent column is an error, not a null.** The export writes every column
 * of every row, including the null ones, so a row missing one did not come
 * from this format -- and read as a null, a missing `pending_reason` would turn
 * a review item into a committed transaction, and a missing `is_excluded`
 * would count money spec 7.3 says was never spent.
 */
internal class BackupRow(
    private val table: String,
    private val values: Map<String, Any?>,
) {
    private fun raw(column: String): Any? {
        if (!values.containsKey(column)) {
            throw ImportFormatException(
                "A row of '$table' in this backup has no '$column'. Every Pinged export " +
                    "writes every column of every row, including the empty ones, so this " +
                    "file was written by something else or has been edited. Nothing was " +
                    "imported.",
            )
        }
        val value = values[column]
        if (value === UNREADABLE) {
            throw wrongType(column, "a string, a number, a boolean or null", "a list or an object")
        }
        return value
    }

    private fun wrongType(column: String, expected: String, got: String) = ImportFormatException(
        "'$table.$column' in this backup is $got, and this build reads it as $expected. " +
            "Nothing was imported.",
    )

    private fun nullNotAllowed(column: String) = ImportFormatException(
        "'$table.$column' in this backup is empty, and this build has no value to fall " +
            "back on for it. Nothing was imported.",
    )

    fun optText(column: String): String? = when (val v = raw(column)) {
        null -> null
        is String -> v
        else -> throw wrongType(column, "text", describe(v))
    }

    fun text(column: String): String = optText(column) ?: throw nullNotAllowed(column)

    fun optLong(column: String): Long? = when (val v = raw(column)) {
        null -> null
        is Long -> v
        else -> throw wrongType(column, "a number", describe(v))
    }

    fun long(column: String): Long = optLong(column) ?: throw nullNotAllowed(column)

    /**
     * A primary key that may be absent, for a reference this build cannot check
     * any other way: `raw_capture.user_reject_rule_id` points at spec 5.7's
     * `user_reject_rule` table, which this milestone does not create, so it has
     * no foreign key and no dangling-count check. Its shape is still checkable.
     *
     * The milestone that builds spec 5.7 owes the real check here.
     */
    fun optRowId(column: String): Long? = optLong(column)?.also { rowIdOrThrow(column, it) }

    /** A primary key, which this format requires to be 1 or more. */
    fun rowId(column: String): Long = long(column).also { rowIdOrThrow(column, it) }

    /**
     * Neither end of the range is pedantry. **Zero** is what Room's
     * `autoGenerate` reads as "assign me one", so the row is stored under an id
     * the file did not name and every reference to it in the same file points
     * elsewhere. **Negative** restores cleanly and disappears later: export
     * walks each large table with `WHERE id > :after` from 0, so the row is
     * skipped by every backup this device will ever write, with nothing
     * reporting it.
     */
    private fun rowIdOrThrow(column: String, id: Long) {
        if (id < 1) {
            throw ImportFormatException(
                "'$table.$column' in this backup is $id. Row ids must be 1 or more: " +
                    "SQLite assigns them from 1, zero means 'assign me one' and would " +
                    "renumber the row, and a negative id is skipped by the keyset " +
                    "cursor every export uses -- so the row would restore and then be " +
                    "missing from every backup afterwards. Nothing was imported.",
            )
        }
    }

    fun optInt(column: String): Int? {
        val v = optLong(column) ?: return null
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) {
            throw wrongType(column, "a 32-bit number", "$v, which does not fit in one")
        }
        return v.toInt()
    }

    fun int(column: String): Int = optInt(column) ?: throw nullNotAllowed(column)

    fun bool(column: String): Boolean = when (val v = raw(column)) {
        // Not lenient about 0/1 or "true". SQLite stores these as integers and
        // this format writes them as JSON booleans; anything else in the slot
        // means the file was produced by something that guessed, and a guess
        // about `is_excluded` is a guess about whether money counts.
        is Boolean -> v
        null -> throw nullNotAllowed(column)
        else -> throw wrongType(column, "true or false", describe(v))
    }

    fun <E : Enum<E>> optEnum(column: String, vocabulary: List<E>): E? {
        val name = optText(column) ?: return null
        return vocabulary.firstOrNull { it.name == name } ?: throw ImportFormatException(
            "'$table.$column' in this backup is '$name', which this build of Pinged does " +
                "not recognise. It reads ${vocabulary.joinToString(", ") { it.name }}. A " +
                "newer build may have added a value; importing the row without it would " +
                "change what the row means. Nothing was imported.",
        )
    }

    fun <E : Enum<E>> enum(column: String, vocabulary: List<E>): E =
        optEnum(column, vocabulary) ?: throw nullNotAllowed(column)

    private fun describe(value: Any?): String = when (value) {
        null -> "empty"
        is String -> "text"
        is Long -> "a number"
        is Boolean -> "true or false"
        else -> value.javaClass.simpleName
    }
}
