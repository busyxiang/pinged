package my.pinged.ledger

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import my.pinged.capture.GuardedRunner
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.dao.TxnDao
import java.io.RandomAccessFile
import my.pinged.ledger.home.RowActions
import my.pinged.ledger.home.rowScreen
import my.pinged.read.ScreenRead

// What the ledger's instrumented classes need of a database, in one place.
// They are several classes in one package and one source set, so a fixture
// spelled out per class is a fixture kept in step by hand, with nothing failing
// when the copies drift. The transaction they insert is `ledgerTxn`, in
// src/sharedTest, which the Robolectric classes draw too.

/** Covers a cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
internal const val TIMEOUT = 30_000L

/** SQLite's page size, and so what has to be overwritten to break a file. */
internal const val FIRST_PAGE = 4_096

/**
 * Forget the memoized handle, and delete the file behind it.
 *
 * Neither half is tidiness. `Databases` memoizes its handle for the life of the
 * process while these tests delete the database file -- `FreshInstallTest`
 * records what a statement against a deleted inode does to some later,
 * unrelated test. And the delete is what leaves the file **openable** after
 * [corruptTheDatabase] has overwritten its first page, which every class in
 * this APK that opens the database afterwards depends on.
 *
 * **Under the delete's gate**, as spec 11.3's delete is. A view model an
 * earlier test left reading opens the database whenever it next reads; without
 * the gate an open between the reset and the delete builds on the file about
 * to be unlinked, and that instance is the one served after it (#140,
 * `LedgerFixturesTest`).
 */
internal fun Context.discardTheDatabase() {
    Databases.whileDeleting { deleteDatabase(DatabaseFactory.NAME) }
}

/**
 * The holder every row writes through, with no screen around it: for a test
 * about what its writes leave in the real database. The screen it reads again
 * after each write reads nothing.
 */
internal fun Context.rowActions(scope: CoroutineScope): RowActions {
    val runner = GuardedRunner(applicationContext as Application)
    return RowActions(scope, runner, ScreenRead.fixed(scope, runner, "The test cannot be read") {}.rowScreen())
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

