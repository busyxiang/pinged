package my.pinged.ledger.transfer

import android.util.JsonWriter
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.CaptureDay
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.Category
import my.pinged.data.entity.MerchantAlias
import my.pinged.data.entity.MerchantName
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn

/**
 * The backup file format: what is in it, what version it is, and what it
 * cannot bring back.
 *
 * Spec 12 calls the JSON export "full fidelity ... sufficient to rebuild the
 * database", and spec 11.2 makes reading it back a v1 feature because without
 * that the sentence is a claim nothing tests. This file is the single
 * description of what is written; [ExportJson] holds no column list of its own.
 * [ImportJson]'s six row builders do name every column again, because the read
 * side feeds named constructor arguments -- the round-trip test is what holds
 * the reader to this.
 *
 * ## Why the column list is data and not code
 *
 * A `writeCapture` that calls `w.name("title")` twenty-five times fails in a
 * way both ends report as success: a column added to an entity and forgotten
 * here exports clean and restores clean, and is discovered when somebody
 * wonders why every restored transaction lost the reason it needed review. This
 * schema gained `pending_reason`, `arrival` and `scoped_package` after the
 * export was specified.
 *
 * A round trip only exercises the fields somebody remembered to write, so the
 * columns are a **list**, each naming the Kotlin property it reads, and
 * `ExportCompletenessTest` asserts reflectively that it covers every field the
 * entity declares and that every name is a real column of the current schema.
 * These are instrumented tests, so a working branch stays green until a PR into
 * main -- the same shape as `EnumVocabularyTest` and `MigrationTest`.
 *
 * ## What a restore cannot bring back
 *
 * - **The SQLCipher key.** Keystore keys are non-exportable and destroyed by
 *   uninstall (spec 11.1), which is why this file exists at all. A restore
 *   always lands in a new database under a new key, so the export contains no
 *   key material.
 * - **The `DataStore` counters** -- spec 10.2's heartbeat and 9.6's seen counts.
 *   Nothing about the ledger depends on them.
 * - **The notification-access grant.** An OS permission, re-granted by hand.
 * - **The parser pack.** It ships in the APK; `pack_version` travels with each
 *   capture, so a restored row still records which pack decided it.
 * - **`user_reject_rule` and `user_template_rule`** (spec 4), which do not exist
 *   yet -- `raw_capture.user_reject_rule_id` restores as a number whose referent
 *   this schema version cannot hold.
 */
object Backup {
    /**
     * The version of **this document format**, which is not
     * [my.pinged.data.PingedDatabase]'s schema version and moves for different
     * reasons: the schema version says what shape the tables have, this says which
     * sections exist, in what order, and what a reader may assume. Reordering the
     * sections would move this and not that; widening a nullable column would move
     * that and not this.
     *
     * [ImportJson] accepts this value and every older one back to
     * [OLDEST_READABLE]. A newer file is refused by number rather than by
     * whatever it fails on first, because reading what it recognises and
     * skipping the rest is a restore that silently drops the columns the newer
     * build added -- the exact failure the column list above prevents.
     *
     * 2 added spec 6.4's `merchant_aliases` and `merchant_names`. A format-1
     * file has neither and restores with both tables empty, which is exactly
     * what the ledger it came from held.
     */
    const val FORMAT_VERSION = 2

    /** The oldest format [ImportJson] still reads; see [BackupSection.sinceFormat]. */
    const val OLDEST_READABLE = 1

    /** The first name in the document, so a reader can gate before it reads a row. */
    const val FIELD_FORMAT = "format"

    /**
     * The Room schema version the export was taken from, written for
     * diagnostics and **not** used as a gate. A human looking at a file that
     * will not import wants to know which database shape produced it; the
     * decision itself is [FIELD_FORMAT]'s alone, so that there is exactly one
     * gate rather than two that can disagree.
     */
    const val FIELD_SCHEMA_VERSION = "app_schema_version"

    const val FIELD_EXPORTED_AT = "exported_at"

    const val CATEGORIES = "categories"
    const val CAPTURE_SOURCES = "capture_sources"
    const val CAPTURE_DAYS = "capture_days"
    const val MERCHANT_RULES = "merchant_rules"
    const val RAW_CAPTURES = "raw_captures"
    const val TXNS = "txns"
    const val MERCHANT_ALIASES = "merchant_aliases"
    const val MERCHANT_NAMES = "merchant_names"

    /**
     * Derived from [ALL_SECTIONS] rather than restated: the two were hand-
     * maintained lists of the same six things, kept in step by a third artefact.
     *
     * A `get()` rather than an initialiser -- [ALL_SECTIONS] is declared after this
     * object, and the section-name constants it reads are `const`, so they inline
     * and no initialisation cycle exists.
     */
    val SECTION_ORDER: List<String> get() = ALL_SECTIONS.map { it.name }

    /** [SECTION_ORDER] as a file of [format] carries it: the sections that existed then. */
    fun sectionsIn(format: Int): List<String> =
        ALL_SECTIONS.filter { it.sinceFormat <= format }.map { it.name }
}

/**
 * One column of one table, in both directions.
 *
 * [column] is the JSON name **and** the SQL column name, deliberately the same
 * string: a name that matches the column it came from is one both this app and
 * whoever the user shows the file to can check.
 *
 * [property] is carried purely so `ExportCompletenessTest` can compare this
 * list against the entity by reflection.
 */
internal class BackupColumn<T>(
    val column: String,
    val property: String,
    private val emit: (JsonWriter, T) -> Unit,
) {
    fun write(writer: JsonWriter, row: T) {
        writer.name(column)
        emit(writer, row)
    }
}

/**
 * A section of the document: a JSON array of one table's rows, **in both
 * directions**, the same way [BackupColumn] is.
 *
 * [clearFirst] and [insert] are the read half. As a `when` over the section
 * name in [ImportJson] with an `else -> throw`, adding a seventh section
 * compiled, exported correctly, passed the ordering check and died at import --
 * and `readDocument` rewrites `IllegalStateException` as "not valid JSON", so
 * the user was told their file was malformed about a file this build had just
 * written. Hanging the read side here makes a new section one entry in
 * [ALL_SECTIONS].
 *
 * [clearFirst] is null for the sections an import appends to rather than
 * replaces; see each declaration.
 *
 * [insert] takes a chunk rather than a row: Room's `@Insert(List)` is the same
 * statement count as a loop but acquires the prepared statement once, measured
 * 4x on a 20,000-row import. The reader still holds one chunk at a time.
 */
internal class BackupSection<T>(
    val name: String,
    val table: String,
    val columns: List<BackupColumn<T>>,
    val clearFirst: ((PingedDatabase) -> Unit)? = null,
    /**
     * The first [Backup.FORMAT_VERSION] that writes this section. An older file
     * is not expected to carry it, and one that does has been edited.
     */
    val sinceFormat: Int = 1,
    val insert: (PingedDatabase, List<BackupRow>) -> Unit,
) {
    fun writeRow(writer: JsonWriter, row: T) {
        writer.beginObject()
        columns.forEach { it.write(writer, row) }
        writer.endObject()
    }
}

// `JsonWriter.value(String)` already writes a JSON null for a null string;
// there is no such overload for the primitives, so nullable numbers need
// these. Booleans go out as JSON `true`/`false` rather than as SQLite's 0/1,
// and enums as the same constant name SQLite holds -- `EnumVocabularyTest`
// freezes those strings, so the file and the column cannot disagree about them.
private fun JsonWriter.nullableLong(value: Long?): JsonWriter =
    if (value == null) nullValue() else value(value)

private fun JsonWriter.nullableInt(value: Int?): JsonWriter =
    if (value == null) nullValue() else value(value.toLong())

private fun <E : Enum<E>> JsonWriter.enum(value: E?): JsonWriter =
    if (value == null) nullValue() else value(value.name)

internal val CATEGORY_SECTION = BackupSection<Category>(
    name = Backup.CATEGORIES,
    table = "category",
    columns = listOf(
        BackupColumn("id", "id") { w, r -> w.value(r.id) },
        BackupColumn("name", "name") { w, r -> w.value(r.name) },
        BackupColumn("icon_key", "iconKey") { w, r -> w.value(r.iconKey) },
        BackupColumn("sort_order", "sortOrder") { w, r -> w.value(r.sortOrder.toLong()) },
        BackupColumn("is_protected", "isProtected") { w, r -> w.value(r.isProtected) },
    ),
    clearFirst = { it.categoryDao().deleteAllForImport() },
    insert = { db, rows -> db.categoryDao().insertAll(rows.map(ImportJson::category)) },
)

internal val CAPTURE_SOURCE_SECTION = BackupSection<CaptureSource>(
    name = Backup.CAPTURE_SOURCES,
    table = "capture_source",
    columns = listOf(
        BackupColumn("pkg", "pkg") { w, r -> w.value(r.pkg) },
        BackupColumn("label", "label") { w, r -> w.value(r.label) },
        BackupColumn("enabled", "enabled") { w, r -> w.value(r.enabled) },
        BackupColumn("is_authoritative", "isAuthoritative") { w, r -> w.value(r.isAuthoritative) },
        BackupColumn("first_seen_at", "firstSeenAt") { w, r -> w.value(r.firstSeenAt) },
        BackupColumn("last_notification_at", "lastNotificationAt") { w, r ->
            w.nullableLong(r.lastNotificationAt)
        },
        BackupColumn("expected_monthly_count", "expectedMonthlyCount") { w, r ->
            w.nullableInt(r.expectedMonthlyCount)
        },
    ),
    clearFirst = { it.captureSourceDao().deleteAllForImport() },
    insert = { db, rows -> db.captureSourceDao().insertAllForImport(rows.map(ImportJson::captureSource)) },
)

internal val CAPTURE_DAY_SECTION = BackupSection<CaptureDay>(
    name = Backup.CAPTURE_DAYS,
    table = "capture_day",
    columns = listOf(
        BackupColumn("local_date", "localDate") { w, r -> w.value(r.localDate.yyyymmdd.toLong()) },
        BackupColumn("listener_bound", "listenerBound") { w, r -> w.value(r.listenerBound) },
        BackupColumn("saw_any_notification", "sawAnyNotification") { w, r ->
            w.value(r.sawAnyNotification)
        },
    ),
    clearFirst = { it.captureDayDao().deleteAllForImport() },
    insert = { db, rows -> db.captureDayDao().insertAllForImport(rows.map(ImportJson::captureDay)) },
)

internal val MERCHANT_RULE_SECTION = BackupSection<MerchantRule>(
    name = Backup.MERCHANT_RULES,
    table = "merchant_rule",
    columns = listOf(
        BackupColumn("id", "id") { w, r -> w.value(r.id) },
        BackupColumn("match_type", "matchType") { w, r -> w.enum(r.matchType) },
        BackupColumn("pattern", "pattern") { w, r -> w.value(r.pattern) },
        BackupColumn("merchant_display", "merchantDisplay") { w, r -> w.value(r.merchantDisplay) },
        BackupColumn("category_id", "categoryId") { w, r -> w.value(r.categoryId) },
        BackupColumn("origin", "origin") { w, r -> w.enum(r.origin) },
        BackupColumn("priority", "priority") { w, r -> w.value(r.priority.toLong()) },
        BackupColumn("hit_count", "hitCount") { w, r -> w.value(r.hitCount.toLong()) },
        BackupColumn("scoped_package", "scopedPackage") { w, r -> w.value(r.scopedPackage) },
    ),
    insert = { db, rows -> db.merchantRuleDao().insertAll(rows.map(ImportJson::merchantRule)) },
)

internal val RAW_CAPTURE_SECTION = BackupSection<RawCapture>(
    name = Backup.RAW_CAPTURES,
    table = "raw_capture",
    columns = listOf(
        BackupColumn("id", "id") { w, r -> w.value(r.id) },
        BackupColumn("source_package", "sourcePackage") { w, r -> w.value(r.sourcePackage) },
        BackupColumn("posted_at", "postedAt") { w, r -> w.value(r.postedAt) },
        BackupColumn("when_millis", "whenMillis") { w, r -> w.nullableLong(r.whenMillis) },
        BackupColumn("captured_at", "capturedAt") { w, r -> w.value(r.capturedAt) },
        BackupColumn("sbn_key", "sbnKey") { w, r -> w.value(r.sbnKey) },
        BackupColumn("notif_id", "notifId") { w, r -> w.value(r.notifId.toLong()) },
        BackupColumn("notif_tag", "notifTag") { w, r -> w.value(r.notifTag) },
        BackupColumn("user_handle", "userHandle") { w, r -> w.value(r.userHandle.toLong()) },
        BackupColumn("channel_id", "channelId") { w, r -> w.value(r.channelId) },
        BackupColumn("flags", "flags") { w, r -> w.value(r.flags.toLong()) },
        BackupColumn("arrival", "arrival") { w, r -> w.enum(r.arrival) },
        BackupColumn("title", "title") { w, r -> w.value(r.title) },
        BackupColumn("text", "text") { w, r -> w.value(r.text) },
        BackupColumn("big_text", "bigText") { w, r -> w.value(r.bigText) },
        BackupColumn("sub_text", "subText") { w, r -> w.value(r.subText) },
        BackupColumn("extras_json", "extrasJson") { w, r -> w.value(r.extrasJson) },
        BackupColumn("content_hash", "contentHash") { w, r -> w.value(r.contentHash) },
        BackupColumn("parse_status", "parseStatus") { w, r -> w.enum(r.parseStatus) },
        BackupColumn("rejected_by_rule_id", "rejectedByRuleId") { w, r ->
            w.value(r.rejectedByRuleId)
        },
        BackupColumn("matched_rule_id", "matchedRuleId") { w, r -> w.value(r.matchedRuleId) },
        BackupColumn("reject_collision_id", "rejectCollisionId") { w, r ->
            w.value(r.rejectCollisionId)
        },
        BackupColumn("pack_version", "packVersion") { w, r -> w.value(r.packVersion.toLong()) },
        BackupColumn("duplicate_of_id", "duplicateOfId") { w, r ->
            w.nullableLong(r.duplicateOfId)
        },
        BackupColumn("user_reject_rule_id", "userRejectRuleId") { w, r ->
            w.nullableLong(r.userRejectRuleId)
        },
    ),
    insert = { db, rows -> db.rawCaptureDao().insertAll(rows.map(ImportJson::rawCapture)) },
)

internal val TXN_SECTION = BackupSection<Txn>(
    name = Backup.TXNS,
    table = "txn",
    columns = listOf(
        BackupColumn("id", "id") { w, r -> w.value(r.id) },
        BackupColumn("raw_capture_id", "rawCaptureId") { w, r -> w.nullableLong(r.rawCaptureId) },
        BackupColumn("amount_sen", "amountSen") { w, r -> w.value(r.amountSen) },
        BackupColumn("currency", "currency") { w, r -> w.value(r.currency) },
        BackupColumn("direction", "direction") { w, r -> w.enum(r.direction) },
        BackupColumn("occurred_at", "occurredAt") { w, r -> w.value(r.occurredAt) },
        BackupColumn("local_date", "localDate") { w, r -> w.value(r.localDate.yyyymmdd.toLong()) },
        BackupColumn("merchant_raw", "merchantRaw") { w, r -> w.value(r.merchantRaw) },
        BackupColumn("merchant_display", "merchantDisplay") { w, r -> w.value(r.merchantDisplay) },
        BackupColumn("merchant_key", "merchantKey") { w, r -> w.value(r.merchantKey) },
        BackupColumn("category_id", "categoryId") { w, r -> w.value(r.categoryId) },
        BackupColumn("source_package", "sourcePackage") { w, r -> w.value(r.sourcePackage) },
        BackupColumn("source_label", "sourceLabel") { w, r -> w.value(r.sourceLabel) },
        BackupColumn("confidence", "confidence") { w, r -> w.enum(r.confidence) },
        BackupColumn("state", "state") { w, r -> w.enum(r.state) },
        BackupColumn("pending_reason", "pendingReason") { w, r -> w.enum(r.pendingReason) },
        BackupColumn("is_excluded", "isExcluded") { w, r -> w.value(r.isExcluded) },
        BackupColumn("exclusion_reason", "exclusionReason") { w, r -> w.enum(r.exclusionReason) },
        BackupColumn("note", "note") { w, r -> w.value(r.note) },
        BackupColumn("user_edited", "userEdited") { w, r -> w.value(r.userEdited) },
        BackupColumn("created_at", "createdAt") { w, r -> w.value(r.createdAt) },
        BackupColumn("updated_at", "updatedAt") { w, r -> w.value(r.updatedAt) },
    ),
    // `TxnDao.insert`, never `insertRow`: the checked one. An import is a write
    // path like any other and inherits spec 7.1's invariant by going through the
    // DAO, which is the whole reason that guard lives there.
    insert = { db, rows -> db.txnDao().insertAll(rows.map(ImportJson::txn)) },
)

/**
 * Spec 6.4's merges. After `txns` only because they arrived later: neither
 * table has a foreign key, so their place in the order constrains nothing.
 */
internal val MERCHANT_ALIAS_SECTION = BackupSection<MerchantAlias>(
    name = Backup.MERCHANT_ALIASES,
    table = "merchant_alias",
    columns = listOf(
        BackupColumn("merchant_key", "merchantKey") { w, r -> w.value(r.merchantKey) },
        BackupColumn("canonical_key", "canonicalKey") { w, r -> w.value(r.canonicalKey) },
    ),
    sinceFormat = 2,
    insert = { db, rows -> db.merchantIdentityDao().insertAllAliases(rows.map(ImportJson::merchantAlias)) },
)

/** Spec 6.4's renames. */
internal val MERCHANT_NAME_SECTION = BackupSection<MerchantName>(
    name = Backup.MERCHANT_NAMES,
    table = "merchant_name",
    columns = listOf(
        BackupColumn("merchant_key", "merchantKey") { w, r -> w.value(r.merchantKey) },
        BackupColumn("display", "display") { w, r -> w.value(r.display) },
    ),
    sinceFormat = 2,
    insert = { db, rows -> db.merchantIdentityDao().insertAllNames(rows.map(ImportJson::merchantName)) },
)

/**
 * Every section, **in the order they appear in the file, and the order is
 * load-bearing.**
 *
 * [ImportJson] inserts as it goes, so a child row is inserted long before the
 * rest of the file is seen -- and SQLite's foreign keys are immediate, so `txn`
 * cannot be read before `category`. The reader enforces the order rather than
 * discovering it as a constraint failure, so a hand-assembled file is told what
 * is actually wrong with it.
 */
internal val ALL_SECTIONS: List<BackupSection<*>> = listOf(
    CATEGORY_SECTION,
    CAPTURE_SOURCE_SECTION,
    CAPTURE_DAY_SECTION,
    MERCHANT_RULE_SECTION,
    RAW_CAPTURE_SECTION,
    TXN_SECTION,
    MERCHANT_ALIAS_SECTION,
    MERCHANT_NAME_SECTION,
)

/** What an import wrote, for the screen that reports it back to the user. */
data class ImportReport(
    val formatVersion: Int,
    val exportedAt: Long?,
    val categories: Int,
    val captureSources: Int,
    val captureDays: Int,
    val merchantRules: Int,
    val rawCaptures: Int,
    val txns: Int,
    val merchantAliases: Int,
    val merchantNames: Int,
) {
    val rows: Int
        get() = categories + captureSources + captureDays + merchantRules + rawCaptures + txns +
            merchantAliases + merchantNames
}

/**
 * Everything an import can refuse for.
 *
 * A `RuntimeException` and a sealed hierarchy, because the caller has three
 * things to say: this file is not one of ours, this device already holds a
 * ledger, and this file's rows do not hang together. Every message is written
 * for the user, because spec 11.2's reader has already lost something once.
 */
sealed class ImportException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** The document is not a Pinged backup, is truncated, or is from another version. */
class ImportFormatException(message: String, cause: Throwable? = null) :
    ImportException(message, cause)

/** This device already holds captures, transactions or learned rules. */
class ImportNotEmptyException(message: String) : ImportException(message)

/** The document parses, and its rows refer to each other in ways that cannot hold. */
class ImportIntegrityException(message: String, cause: Throwable? = null) :
    ImportException(message, cause)

/**
 * The caller's `isCancelled` said stop.
 *
 * Not an [ImportException]: both directions raise it, and it is not a failure
 * of the file. On import it leaves nothing behind, because it is thrown from
 * inside the one transaction the whole import runs in. On export it leaves a
 * truncated document, which the caller must delete -- there is no way to write
 * a prefix of a JSON file that is also a valid JSON file.
 */
class TransferCancelledException(message: String) : RuntimeException(message)
