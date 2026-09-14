package my.pinged.data

import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileInputStream
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.RuleOrigin
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason

fun sampleCapture(
    hash: String = "hash-0",
    postedAt: Long = 1_000L,
    pkg: String = "my.com.tngdigital.ewallet",
    text: String? = "Payment of RM1.00 to A successful",
    sbnKey: String = "key-$hash",
    status: ParseStatus = ParseStatus.NEW,
    arrival: Arrival = Arrival.POSTED,
) = RawCapture(
    sourcePackage = pkg,
    postedAt = postedAt,
    whenMillis = null,
    capturedAt = postedAt,
    sbnKey = sbnKey,
    notifId = 1,
    notifTag = null,
    userHandle = 0,
    channelId = "txn",
    flags = 0,
    arrival = arrival,
    title = "Touch 'n Go eWallet",
    text = text,
    bigText = null,
    subText = null,
    extrasJson = null,
    contentHash = hash,
    parseStatus = status,
)

/**
 * `pendingReason` defaults to whatever [state] requires rather than to null,
 * because spec 7.1's invariant is enforced at the write path: a `PENDING` row
 * must say which gate fired and a non-`PENDING` row must not. Either constant
 * default makes one whole class of call site an illegal row. A caller that
 * wants a specific reason, or a deliberately contradictory pair, passes it
 * explicitly.
 */
fun sampleTxn(
    amountSen: Long = 1_000L,
    occurredAt: Long = 1_000L,
    pkg: String? = "my.com.tngdigital.ewallet",
    rawCaptureId: Long? = null,
    categoryId: Long = 1L,
    state: TxnState = TxnState.COMMITTED,
    pendingReason: PendingReason? =
        if (state == TxnState.PENDING) PendingReason.RULE_REVIEW else null,
    direction: Direction = Direction.EXPENSE,
    isExcluded: Boolean = false,
    exclusionReason: ExclusionReason? = if (isExcluded) ExclusionReason.USER else null,
    currency: String = "MYR",
    // Derived from [occurredAt] unless a caller says otherwise. The two are
    // not the same fact -- spec 15.7 stores the day the money moved in the
    // zone it moved in -- and a fixture that needs them to disagree (an
    // imported row, a device that has flown west) is the only reason this is
    // a parameter.
    localDate: LocalDate = LocalDates.of(occurredAt),
) = Txn(
    rawCaptureId = rawCaptureId,
    amountSen = amountSen,
    direction = direction,
    occurredAt = occurredAt,
    localDate = localDate,
    merchantRaw = "A",
    merchantDisplay = "A",
    merchantKey = "A",
    categoryId = categoryId,
    sourcePackage = pkg,
    sourceLabel = null,
    isExcluded = isExcluded,
    exclusionReason = exclusionReason,
    currency = currency,
    confidence = Confidence.HIGH,
    state = state,
    pendingReason = pendingReason,
    createdAt = occurredAt,
    updatedAt = occurredAt,
)

/**
 * `findByContentHash` for the window spec 7.2 rule 2 actually describes: the
 * 60 seconds *before* [postedAt], and not one millisecond after it.
 *
 * A test helper rather than a DAO default, because the upper bound is the
 * whole point of the fix and a defaulted `untilMillis` would let a caller
 * forget it again. Every call site here passes the capture's own `posted_at`,
 * which is what production passes.
 */
fun my.pinged.data.dao.RawCaptureDao.contentHashWindow(
    hash: String,
    postedAt: Long,
) = findByContentHash(
    hash = hash,
    sinceMillis = postedAt - DuplicateWindows.CONTENT_HASH_MILLIS,
    untilMillis = postedAt,
)

fun enabledSource(pkg: String) = CaptureSource(
    pkg = pkg, label = pkg, enabled = true, firstSeenAt = 0L,
)

fun disabledSource(pkg: String) = CaptureSource(
    pkg = pkg, label = pkg, enabled = false, firstSeenAt = 0L,
)

/**
 * Room's `RoomDatabase` declares `close()` but does not implement
 * `Closeable`, so `kotlin.io.use` does not apply to it. This is the
 * equivalent, and it exists so a test cannot leak an open SQLCipher handle
 * onto the next test's `deleteDatabase()`.
 */
inline fun <R> PingedDatabase.useDb(block: (PingedDatabase) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

/**
 * Insert a `merchant_rule` through the open helper rather than through
 * [my.pinged.data.dao.MerchantRuleDao], because the entity cannot express what
 * one test needs: `SchemaConstraintTest` asserts that an explicit NULL
 * `scoped_package` is refused by the column, and `MerchantRule.scopedPackage`
 * is non-null, so only raw SQL can attempt it.
 */
fun PingedDatabase.insertMerchantRule(
    pattern: String,
    categoryId: Long,
    origin: RuleOrigin = RuleOrigin.LEARNED,
    matchType: MatchType = MatchType.EXACT,
    /**
     * Defaults to [my.pinged.data.entity.MerchantRule.UNSCOPED], which is what
     * spec 6.1's learned-rule writer produces. The type stays nullable purely
     * so `SchemaConstraintTest.anExplicitNullScopeIsRefusedByTheColumn` can
     * prove the column itself refuses a null -- production has no way to
     * express one.
     */
    scopedPackage: String? = MerchantRule.UNSCOPED,
    priority: Int = 100,
): Long {
    val values = android.content.ContentValues().apply {
        put("match_type", matchType.name)
        put("pattern", pattern)
        put("merchant_display", pattern)
        put("category_id", categoryId)
        put("origin", origin.name)
        put("priority", priority)
        put("hit_count", 0)
        put("scoped_package", scopedPackage)
    }
    return openHelper.writableDatabase.insert(
        "merchant_rule",
        android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
        values,
    )
}

/**
 * The `category_id` of every `merchant_rule` row, ordered by pattern and then
 * by `id`.
 *
 * **`id` is the tiebreaker and it is not decoration.** Ordering by `pattern`
 * alone ties whenever two rules share a pattern -- which is most of what the
 * uniqueness tests insert -- so a multi-row assertion would be asserting an
 * order SQL does not promise.
 */
fun PingedDatabase.merchantRuleCategoryIds(): List<Long> {
    val out = mutableListOf<Long>()
    openHelper.readableDatabase
        .query("SELECT category_id FROM merchant_rule ORDER BY pattern ASC, id ASC")
        .use { c -> while (c.moveToNext()) out += c.getLong(0) }
    return out
}

/** Reads `PRAGMA foreign_keys` back off a live connection. */
fun SupportSQLiteDatabase.foreignKeysPragma(): Int = pragma("foreign_keys").toInt()

/**
 * Reads any single-value `PRAGMA` back off a live connection.
 *
 * `CategoryDeleteTest` keeps an inline `PRAGMA foreign_keys` read that looks
 * like a duplicate and is not: it goes through Room's own query routing rather
 * than a handle this helper was given, which is the third connection path that
 * test exists to check. Leave it alone.
 */
fun SupportSQLiteDatabase.pragma(name: String): Long =
    query("PRAGMA $name").use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

/**
 * Assert a file is not a plaintext SQLite database.
 *
 * This is the one assertion in the module that, if it silently weakens, makes
 * every other test pass against an unencrypted database, so it exists once and
 * must not be copied: two copies drifted apart once already.
 */
fun File.assertNotPlaintextSqlite(label: String) {
    assertTrue("No database file at $absolutePath", isFile)
    val head = ByteArray(16)
    FileInputStream(this).use { assertEquals(16, it.read(head)) }
    val hex = head.joinToString(" ") { "%02x".format(it) }
    Log.i(OpenTest.REPORT_TAG, "$label header (first 16 bytes, hex): $hex")

    val ascii = String(head, Charsets.ISO_8859_1)
    assertNotEquals(
        "$label is PLAINTEXT SQLite -- SQLCipher was bypassed. Header: $hex",
        "SQLite format 3\u0000",
        ascii,
    )
    // Belt and braces: the ASCII prefix on its own, in case a future SQLite
    // bumps the trailing NUL.
    assertTrue(
        "$label begins with the plaintext SQLite magic. Header: $hex",
        !ascii.startsWith("SQLite format"),
    )
}

/**
 * A database with nothing in it but the seed, opened the way the app opens one.
 *
 * The one place any change to how a test database is opened has to reach: a
 * class that opens its own and misses the change leaks an open SQLCipher
 * handle onto the next class's `deleteDatabase`. `:feature:ledger`'s
 * `TransferFixtures` carries the same helper under the same name.
 */
fun freshDatabase(
    context: Context = InstrumentationRegistry.getInstrumentation().targetContext,
): PingedDatabase {
    context.deleteDatabase(DatabaseFactory.NAME)
    return DatabaseFactory.build(context)
}

/** The id of a seeded category, by name. */
fun PingedDatabase.categoryId(name: String): Long =
    categoryDao().all().single { it.name == name }.id

/** One column of a query, as strings, nulls preserved. */
fun SupportSQLiteDatabase.column(sql: String): List<String?> {
    val out = mutableListOf<String?>()
    query(sql).use { c -> while (c.moveToNext()) out += if (c.isNull(0)) null else c.getString(0) }
    return out
}
