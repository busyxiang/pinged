package my.pinged.ledger

import android.content.Context
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason
import java.io.RandomAccessFile

// What the ledger's instrumented classes need of a database and of a
// transaction, in one place. They are several classes in one package and one
// source set, so a fixture spelled out per class is a fixture kept in step by
// hand, with nothing failing when the copies drift.

/** Covers a cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
internal const val TIMEOUT = 30_000L

/** SQLite's page size, and so what has to be overwritten to break a file. */
internal const val FIRST_PAGE = 4_096

/** [ledgerTxn]'s `merchant_display`, which is the row's first line. */
internal const val MERCHANT = "Warung Pak Ali"

/** [ledgerTxn]'s `source_label`, which the row prints after its category. */
internal const val SOURCE_LABEL = "Touch 'n Go eWallet"

/**
 * Forget the memoized handle, and delete the file behind it.
 *
 * Neither half is tidiness. `Databases` memoizes its handle for the life of the
 * process while these tests delete the database file -- `FreshInstallTest`
 * records what a statement against a deleted inode does to some later,
 * unrelated test. And the delete is what leaves the file **openable** after
 * [corruptTheDatabase] has overwritten its first page, which every class in
 * this APK that opens the database afterwards depends on.
 */
internal fun Context.discardTheDatabase() {
    Databases.reset()
    deleteDatabase(DatabaseFactory.NAME)
}

/**
 * An empty, seeded database, opened the way the app opens one, and the DAO the
 * view model will reach for.
 *
 * The delete is the fixture, not the cleanup: these tests assert on totals, and
 * the app database in this test APK outlives the run.
 */
internal fun Context.freshLedger(): TxnDao {
    discardTheDatabase()
    return Databases.txnDao(this)
}

/**
 * A database file that exists and cannot be opened.
 *
 * The corruption is `StorageFailureTest`'s -- the first page overwritten
 * through `RandomAccessFile` -- and it is the only member of the
 * `DatabaseUnavailableException` family a test can produce: nothing in an
 * instrumented test can take a Keystore key away, and §11.1's missing key
 * reaches every caller through the same type. A real database is built first,
 * because the failure has to be a broken page rather than a missing file, which
 * Room would answer by creating one.
 *
 * Whoever calls this owes the next class a [discardTheDatabase].
 */
internal fun Context.corruptTheDatabase() {
    discardTheDatabase()
    DatabaseFactory.build(this).close()
    RandomAccessFile(getDatabasePath(DatabaseFactory.NAME), "rw").use { file ->
        file.seek(0)
        file.write(ByteArray(FIRST_PAGE) { 0x5A })
    }
}

/**
 * A transaction of the shape the ledger draws. Not `core:data`'s `sampleTxn`,
 * which lives in that module's own androidTest source set and is not on this
 * one's compile path.
 */
internal fun ledgerTxn(
    amountSen: Long,
    occurredAt: Long = 1_000L,
    localDate: LocalDate = LocalDates.of(occurredAt),
    categoryId: Long = 1L,
    state: TxnState = TxnState.COMMITTED,
    pendingReason: PendingReason? = null,
    isExcluded: Boolean = false,
    exclusionReason: ExclusionReason? = null,
    /**
     * Left at Room's `autoGenerate` default for the fixtures that get
     * inserted, and set by the ones that never reach a database: the chip
     * reports the row it is on, and a callback assertion against 0 would
     * agree with a screen that reported nothing at all.
     */
    id: Long = 0L,
) = Txn(
    id = id,
    rawCaptureId = null,
    amountSen = amountSen,
    direction = Direction.EXPENSE,
    occurredAt = occurredAt,
    localDate = localDate,
    merchantRaw = "WARUNG PAK ALI",
    merchantDisplay = MERCHANT,
    merchantKey = "WARUNG PAK ALI",
    categoryId = categoryId,
    sourcePackage = "my.com.tngdigital.ewallet",
    sourceLabel = SOURCE_LABEL,
    confidence = Confidence.HIGH,
    state = state,
    pendingReason = pendingReason,
    isExcluded = isExcluded,
    exclusionReason = exclusionReason,
    createdAt = occurredAt,
    updatedAt = occurredAt,
)
