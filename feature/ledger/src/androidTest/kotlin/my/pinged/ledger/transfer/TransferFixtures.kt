package my.pinged.ledger.transfer

import my.pinged.data.LocalDate
import java.io.ByteArrayOutputStream
import android.content.Context
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import my.pinged.capture.Graph
import my.pinged.data.DatabaseFactory
import my.pinged.data.PingedDatabase
import my.pinged.data.Seed
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.CaptureDay
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.Category
import my.pinged.parse.ExclusionReason
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.RuleOrigin
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction

/**
 * Room's `RoomDatabase` declares `close()` but does not implement
 * `Closeable`, so `kotlin.io.use` does not apply to it. This is the
 * equivalent, and it exists so a test cannot leak an open SQLCipher handle
 * onto the next test's `deleteDatabase()`.
 *
 * `core/data/src/androidTest/kotlin/my/pinged/data/Fixtures.kt` declares the
 * same helper, but that source set builds into its own test APK and is not on
 * this module's classpath -- a Gradle project dependency reaches another
 * module's `main`, never its `androidTest`. Duplicated rather than shared.
 */
inline fun <R> PingedDatabase.useDb(block: (PingedDatabase) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

/**
 * A whole export, as bytes.
 *
 * Six call sites wrote `ByteArrayOutputStream().also { ExportJson.write(...) }`
 * out longhand while `RoundTripTest` already had it under a name.
 */
fun exportBytes(from: PingedDatabase): ByteArray =
    ByteArrayOutputStream().also { ExportJson.write(from, it) }.toByteArray()

/**
 * The database every test in this package runs against.
 *
 * **Built through [DatabaseFactory], never `Room.inMemoryDatabaseBuilder`.**
 * Export and import are exactly where the encrypted open path and the seeded
 * categories have to hold: the import deletes and rewrites the `category` table
 * `DatabaseFactory.build` seeds, and the whole reason the export exists (spec
 * 11.1) is that the SQLCipher key does not travel.
 *
 * `Graph.reset()` first, because `Databases` memoizes the handle for the
 * process. The caller closes it; `RoomDatabase` does not implement `Closeable`.
 */
fun freshDatabase(context: Context): PingedDatabase {
    Graph.reset()
    context.deleteDatabase(DatabaseFactory.NAME)
    return DatabaseFactory.build(context)
}

/**
 * A ledger with one row of every shape the export has to carry, and with the
 * *last* row of each table deliberately maximal -- see [assertMaximal].
 *
 * Returned rather than re-read so that a caller has the ids without a second
 * query; every id here is the one SQLite assigned.
 */
class SeededLedger(
    val db: PingedDatabase,
    val uncategorized: Long,
    val userCategory: Long,
    val captures: List<Long>,
    val txns: List<Long>,
)

/**
 * Writes one of everything.
 *
 * The three shapes named in this milestone's brief are here on purpose,
 * because they are the newest columns and therefore the ones an export written
 * from memory would miss: a `PENDING` transaction carrying its
 * `pending_reason`, a `CATCHUP` capture, and a `GAVE_UP` capture.
 */
fun seedOneOfEverything(db: PingedDatabase): SeededLedger {
    val uncategorized = db.categoryDao().requireUncategorizedId()

    // A category the user added. The seeded fourteen would survive an import
    // that simply left the seed alone, so they prove nothing about the
    // category section; this one only exists in the file.
    db.categoryDao().insertAll(listOf(Category(name = "Kopi", iconKey = "coffee", sortOrder = 99)))
    val userCategory = db.categoryDao().all().first { it.name == "Kopi" }.id

    db.captureSourceDao().insertForImport(
        CaptureSource(pkg = "my.com.tngdigital.ewallet", label = "TNG", firstSeenAt = 10L),
    )
    db.captureSourceDao().insertForImport(
        CaptureSource(
            pkg = "com.maybank2u.life",
            label = "Maybank",
            enabled = true,
            isAuthoritative = true,
            firstSeenAt = 20L,
            lastNotificationAt = 30L,
            expectedMonthlyCount = 40,
        ),
    )

    db.captureDayDao().insertForImport(
        CaptureDay(localDate = LocalDate(20_260_101), listenerBound = false, sawAnyNotification = false),
    )
    db.captureDayDao().insertForImport(
        CaptureDay(localDate = LocalDate(20_260_102), listenerBound = true, sawAnyNotification = true),
    )

    db.merchantRuleDao().insert(
        MerchantRule(
            matchType = MatchType.EXACT,
            pattern = "STARBUCKS KLCC",
            merchantDisplay = "Starbucks KLCC",
            categoryId = uncategorized,
            origin = RuleOrigin.BUNDLED,
            priority = 10,
        ),
    )
    db.merchantRuleDao().insert(
        MerchantRule(
            matchType = MatchType.CONTAINS,
            pattern = "MCD",
            merchantDisplay = "McDonald's",
            categoryId = userCategory,
            origin = RuleOrigin.LEARNED,
            priority = 200,
            hitCount = 7,
            scopedPackage = "com.maybank2u.life",
        ),
    )

    val captures = mutableListOf<Long>()
    captures += db.rawCaptureDao().insert(
        capture(hash = "h1", status = ParseStatus.MATCHED, matchedRuleId = "tng.payment.v1"),
    )
    // Spec 5.6's timeout outcome. Newest of the ParseStatus values and the one
    // an export written from spec 4's list alone would not know exists.
    captures += db.rawCaptureDao().insert(
        capture(hash = "h2", status = ParseStatus.GAVE_UP),
    )
    captures += db.rawCaptureDao().insert(
        capture(hash = "h3", status = ParseStatus.REJECTED, rejectedByRuleId = "reject.tac.v1"),
    )
    // The maximal capture: every column non-empty, `CATCHUP` arrival, and a
    // `duplicate_of_id` that resolves to the first row above.
    captures += db.rawCaptureDao().insert(
        RawCapture(
            sourcePackage = "com.maybank2u.life",
            postedAt = 1_700_000_000_000L,
            whenMillis = 1_699_999_999_000L,
            capturedAt = 1_700_000_000_500L,
            sbnKey = "0|com.maybank2u.life|9|tag|0",
            notifId = 9,
            notifTag = "txn-tag",
            userHandle = 11,
            channelId = "transactions",
            flags = 16,
            arrival = Arrival.CATCHUP,
            title = "Maybank",
            text = "RM12.34 paid to STARBUCKS KLCC",
            bigText = "RM12.34 paid to STARBUCKS KLCC on 01 Jan",
            subText = "Savings ...1234",
            extrasJson = """{"android.title":"Maybank"}""",
            contentHash = "h4",
            parseStatus = ParseStatus.DUPLICATE_OF,
            rejectedByRuleId = "reject.promo.v1",
            matchedRuleId = "mbb.payment.v1",
            rejectCollisionId = "collide.v1",
            packVersion = 3,
            duplicateOfId = captures[0],
            userRejectRuleId = 77L,
        ),
    )

    val txns = mutableListOf<Long>()
    txns += db.txnDao().insert(
        Txn(
            rawCaptureId = captures[0],
            amountSen = 1_234L,
            direction = Direction.EXPENSE,
            occurredAt = 1_700_000_000_000L,
            localDate = LocalDate(20_260_102),
            merchantRaw = "STARBUCKS KLCC",
            merchantDisplay = "Starbucks KLCC",
            merchantKey = "STARBUCKS KLCC",
            categoryId = uncategorized,
            sourcePackage = "my.com.tngdigital.ewallet",
            sourceLabel = "TNG",
            confidence = Confidence.HIGH,
            state = TxnState.COMMITTED,
            createdAt = 1L,
            updatedAt = 2L,
        ),
    )
    // The maximal transaction. `PENDING` with its reason and excluded with
    // its reason at the same time is an unusual row and a legal one --
    // `requireStorable` treats the two pairs independently -- and it
    // is here because a column left at its Kotlin default survives an export
    // that never wrote it. `currency` is not "MYR" for exactly that reason:
    // it is the one column whose default is a plausible-looking value.
    txns += db.txnDao().insert(
        Txn(
            rawCaptureId = captures[3],
            amountSen = 80_000L,
            currency = "SGD",
            direction = Direction.REFUND,
            occurredAt = 1_700_000_100_000L,
            localDate = LocalDate(20_260_103),
            merchantRaw = "MCD KLCC",
            merchantDisplay = "McDonald's KLCC",
            merchantKey = "MCD KLCC",
            categoryId = userCategory,
            sourcePackage = "com.maybank2u.life",
            sourceLabel = "Maybank",
            confidence = Confidence.REVIEW,
            state = TxnState.PENDING,
            pendingReason = PendingReason.DUPLICATE_SUSPECT,
            isExcluded = true,
            exclusionReason = ExclusionReason.TRANSFER,
            note = "asked the bank about this one",
            userEdited = true,
            createdAt = 3L,
            updatedAt = 4L,
        ),
    )

    return SeededLedger(db, uncategorized, userCategory, captures, txns)
}

private fun capture(
    hash: String,
    status: ParseStatus,
    matchedRuleId: String? = null,
    rejectedByRuleId: String? = null,
) = RawCapture(
    sourcePackage = "my.com.tngdigital.ewallet",
    postedAt = 1_600_000_000_000L,
    whenMillis = null,
    capturedAt = 1_600_000_000_100L,
    sbnKey = "0|my.com.tngdigital.ewallet|1|null|0|$hash",
    notifId = 1,
    notifTag = null,
    userHandle = 0,
    channelId = "txn",
    flags = 0,
    arrival = Arrival.POSTED,
    title = "Touch 'n Go eWallet",
    text = "Payment of RM1.00 to A successful",
    bigText = null,
    subText = null,
    extrasJson = null,
    contentHash = hash,
    parseStatus = status,
    matchedRuleId = matchedRuleId,
    rejectedByRuleId = rejectedByRuleId,
)

/**
 * One of many. Used where a test needs a table big enough for the paging and
 * the progress reporting to have boundaries -- `content_hash` is the only
 * thing that varies, because nothing here is asserted about the contents.
 */
fun bulkCapture(index: Int) = RawCapture(
    sourcePackage = "my.com.tngdigital.ewallet",
    postedAt = 1_600_000_000_000L + index,
    whenMillis = null,
    capturedAt = 1_600_000_000_000L + index,
    sbnKey = "0|my.com.tngdigital.ewallet|$index|null|0",
    notifId = index,
    notifTag = null,
    userHandle = 0,
    channelId = "txn",
    flags = 0,
    arrival = Arrival.POSTED,
    title = "Touch 'n Go eWallet",
    text = "Payment of RM1.00 to merchant number $index successful",
    bigText = null,
    subText = null,
    extrasJson = null,
    contentHash = "bulk-$index",
    parseStatus = ParseStatus.NEW,
)

/**
 * Rewrites [document] only inside the named section, so that a test damaging
 * `category_id` in the transactions cannot silently damage it in the learned
 * rules as well -- which would move the failure to a different section and
 * make the test prove something other than what it says.
 */
fun String.editingOnly(section: String, edit: (String) -> String): String {
    val start = indexOf("\"$section\":[")
    check(start >= 0) { "No '$section' section in this document" }
    return substring(0, start) + edit(substring(start))
}

// ---- reflection ---------------------------------------------------------

/**
 * The fields an entity declares, which for a Kotlin `data class` is its primary
 * constructor's parameters and nothing else.
 *
 * Reading fields rather than constructor parameters is deliberate:
 * `KClass.primaryConstructor` needs `kotlin-reflect`, which is not a dependency
 * here, and for a data class whose every parameter is a `val` the backing
 * fields are exactly those parameters. Where the two could differ -- a property
 * declared in the body -- this errs strict, and somebody has to decide whether
 * the export should carry it.
 *
 * Static fields are filtered because `MerchantRule` has a companion object with
 * a `const`, emitted as a static field alongside a `Companion` reference.
 */
fun entityFields(type: Class<*>): List<Field> =
    type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        .onEach { it.isAccessible = true }

fun entityFieldNames(type: Class<*>): Set<String> = entityFields(type).map { it.name }.toSet()

/**
 * Every field of [row] read by name, for the field-by-field comparison a round
 * trip has to make.
 */
fun fieldValues(row: Any): Map<String, Any?> =
    entityFields(row.javaClass).associate { it.name to it.get(row) }

/**
 * Every field of [type] at the value it takes when nothing is supplied.
 *
 * Derived, not listed. Kotlin compiles a data class with default arguments into
 * a synthetic constructor taking the declared parameters, one `int` bitmask per
 * 32 of them, and a `DefaultConstructorMarker`. Passing a mask with every bit
 * set makes each defaulted parameter take its default -- including any added
 * tomorrow.
 *
 * A parameter with no default takes the zero value, which is wanted anyway.
 */
fun defaultsOf(type: Class<*>): Map<String, Any?> {
    // The declared parameter count comes from the entity's own fields, not
    // from arithmetic on the constructor's length. It used to be derived as
    // `(markerIndex * 32 - 1) / 33`, which assumed every marker-suffixed
    // constructor is the all-defaults one. That stopped being true the moment
    // `CaptureDay.localDate` became a value class: a value class erases to its
    // underlying type, so Kotlin adds a `DefaultConstructorMarker` to the
    // constructor purely to keep the JVM signature distinct, and that bridge
    // carries no bitmask at all. The old formula read one of `CaptureDay`'s
    // booleans as a mask slot and handed it an Int.
    val declaredCount = entityFields(type).size
    val maskCount = (declaredCount + 31) / 32

    val synthetic = type.declaredConstructors.firstOrNull { candidate ->
        candidate.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker" &&
            candidate.parameterTypes.size == declaredCount + maskCount + 1
    }
        // No constructor of that shape means no parameter has a default, so
        // there are no default values to recognise. CaptureDay is such an
        // entity. The weak-value set in [assertMaximal] still applies.
        ?: return emptyMap()

    val parameters = synthetic.parameterTypes
    val arguments = arrayOfNulls<Any?>(parameters.size)
    val markerIndex = parameters.size - 1

    // Every reference slot gets a non-null placeholder. Nullability cannot be
    // detected here -- Kotlin's @NotNull has CLASS retention, so it is not
    // visible to runtime reflection -- and handing null to a non-null
    // parameter trips the intrinsic check before the body runs. The
    // consequence is handled in [assertMaximal]: a nullable field's real
    // default is null, and null is in the weak-value set there anyway.
    for (i in parameters.indices) {
        val type = parameters[i]
        arguments[i] = when {
            type == Boolean::class.javaPrimitiveType -> false
            type == Byte::class.javaPrimitiveType -> 0.toByte()
            type == Short::class.javaPrimitiveType -> 0.toShort()
            type == Int::class.javaPrimitiveType -> 0
            type == Long::class.javaPrimitiveType -> 0L
            type == Float::class.javaPrimitiveType -> 0f
            type == Double::class.javaPrimitiveType -> 0.0
            type == Char::class.javaPrimitiveType -> '\u0000'
            type == String::class.java -> ""
            type.isEnum -> type.enumConstants.first()
            else -> null
        }
    }
    // All bits set on every mask slot: take the default for everything that has one.
    for (i in declaredCount until markerIndex) arguments[i] = -1
    arguments[markerIndex] = null

    synthetic.isAccessible = true
    return fieldValues(synthetic.newInstance(*arguments))
}

/**
 * Asserts that [row] leaves no field at a value a dropped column would produce.
 *
 * This is what makes the round-trip comparison mean anything: a column the
 * reader never reads takes its Kotlin default, and if the fixture used that
 * same default the comparison passes while the column is lost.
 *
 * The defaults are read off the entity by [defaultsOf] rather than listed. As a
 * hand-maintained list, adding `val arrival: Arrival = Arrival.POSTED` would
 * have passed it -- the completeness test forces the column into `Backup`, the
 * fixture sits at the new default, this check does not recognise the value, and
 * the round trip compares default to default and goes green with the column
 * dropped by every backup and restore.
 */
fun assertMaximal(row: Any) {
    val defaults = defaultsOf(row.javaClass)
    val empty = fieldValues(row).filter { (name, value) ->
        // Either the entity's own default for that field, derived rather than
        // listed -- or one of the universal weak values, which covers the
        // nullable fields [defaultsOf] cannot construct a true default for and
        // keeps the original rule that a fixture full of zeroes proves nothing.
        value == defaults[name] ||
            value == null || value == 0L || value == 0 || value == false || value == ""
    }
    if (empty.isNotEmpty()) {
        throw AssertionError(
            "${row.javaClass.simpleName} is meant to be the fixture row with nothing " +
                "left at a default, so that a column the importer never reads shows up " +
                "as a difference rather than as a coincidence. These fields are at a " +
                "value a dropped column would also produce: " +
                empty.keys.sorted().joinToString(", ") + ".",
        )
    }
}

/** The tables SQLite is actually holding, minus the ones Room and Android own. */
fun PingedDatabase.userTables(): Set<String> {
    val out = mutableSetOf<String>()
    openHelper.readableDatabase
        .query("SELECT name FROM sqlite_master WHERE type = 'table'")
        .use { c -> while (c.moveToNext()) out += c.getString(0) }
    return out - setOf("room_master_table", "sqlite_sequence", "android_metadata")
}

/** The column names of one table, straight off the database. */
fun PingedDatabase.columnsOf(table: String): Set<String> {
    val out = mutableSetOf<String>()
    openHelper.readableDatabase.query("PRAGMA table_info($table)").use { c ->
        val nameIndex = c.getColumnIndexOrThrow("name")
        while (c.moveToNext()) out += c.getString(nameIndex)
    }
    return out
}

/** The name SQLite gives Uncategorized, so a test can say it out loud once. */
val UNCATEGORIZED_NAME: String get() = Seed.UNCATEGORIZED

/**
 * Flip one byte halfway into a database file.
 *
 * A second copy of `:core:data`'s helper of the same name, because `androidTest`
 * source sets are not shared between modules and there is no test-fixtures
 * module here. Keep the two identical: the offset is the part that matters, and
 * a copy that drifts to page 1 silently tests a different state -- an open that
 * fails outright rather than a database that opens and fails its check.
 */
fun damageMidFile(file: java.io.File) {
    java.io.RandomAccessFile(file, "rw").use { raf ->
        val at = file.length() / 2
        raf.seek(at)
        val original = raf.readByte()
        raf.seek(at)
        raf.writeByte(original.toInt() xor 0xFF)
    }
}
