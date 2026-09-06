package my.pinged.data

import android.content.Context
import my.pinged.data.dao.CaptureDayDao
import my.pinged.data.dao.CaptureSourceDao
import my.pinged.data.dao.CategoryDao
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.TxnDao

/**
 * The process's one [PingedDatabase], and the DAOs reached through it.
 *
 * Here rather than in `:feature:capture`, where it used to live as half of that
 * module's `Graph`: nothing about memoizing a database handle is specific to
 * notification capture, and while it lived there `:feature:ledger` took a
 * compile dependency on the capture feature in order to reach the database.
 *
 * Manual, because a DI framework here would be scaffolding with one consumer --
 * the system constructs the listener, so it has to reach for its dependencies.
 */
object Databases {
    @Volatile private var db: PingedDatabase? = null

    /**
     * The database, built on first use and kept for the life of the process.
     *
     * [DatabaseFactory.build] is eager, so this throws
     * [DatabaseKeyUnavailableException] from the first call in spec 11.1's
     * device-transfer state. `CaptureStorage.guarded` in `:feature:capture` is
     * the one place that says what to do about that.
     */
    fun shared(context: Context): PingedDatabase =
        db ?: synchronized(this) {
            db ?: DatabaseFactory.build(context.applicationContext).also { db = it }
        }

    fun rawCaptureDao(context: Context): RawCaptureDao = shared(context).rawCaptureDao()
    fun txnDao(context: Context): TxnDao = shared(context).txnDao()
    fun categoryDao(context: Context): CategoryDao = shared(context).categoryDao()
    fun captureSourceDao(context: Context): CaptureSourceDao = shared(context).captureSourceDao()
    fun captureDayDao(context: Context): CaptureDayDao = shared(context).captureDayDao()

    /**
     * Tests only: closes the database and forces the next call to rebuild.
     *
     * The close is the point. This used to drop the handle without closing it,
     * which leaves SQLCipher's pool open on a file the caller is usually about
     * to delete -- and the callers do exactly that. `FreshInstallTest`
     * documents the consequence: the next statement writes to a deleted inode
     * and the failure surfaces in some other test class entirely.
     */
    fun reset() = synchronized(this) {
        runCatching { db?.close() }
        db = null
    }
}
