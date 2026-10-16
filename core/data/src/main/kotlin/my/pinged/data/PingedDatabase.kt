package my.pinged.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import my.pinged.data.dao.CaptureDayDao
import my.pinged.data.dao.CaptureSourceDao
import my.pinged.data.dao.CategoryDao
import my.pinged.data.dao.MerchantIdentityDao
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.CaptureDay
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.Category
import my.pinged.data.entity.MerchantAlias
import my.pinged.data.entity.MerchantName
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnFts

@Database(
    entities = [
        RawCapture::class, Txn::class, Category::class, CaptureSource::class,
        MerchantRule::class, CaptureDay::class, MerchantAlias::class, MerchantName::class,
        TxnFts::class,
    ],
    version = PingedDatabase.VERSION,
    exportSchema = true,
    // v2 adds spec 6.4's two tables and changes nothing in v1's, so Room's
    // generated migration is two CREATE TABLEs. `MigrationTest` runs it under
    // the SQLCipher factory, from v1, as spec 13 requires. v3 adds spec 15.2's
    // search index, which Room creates empty: [IndexExistingRows] fills it.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = PingedDatabase.IndexExistingRows::class),
    ],
)
abstract class PingedDatabase : RoomDatabase() {
    companion object {
        /**
         * Every file Room opens is at this version, since it migrates or refuses
         * any other. Salvage writes it into its document's header from here,
         * because reading it back through `openHelper` is a path ruling R42
         * keeps to an allowlist.
         */
        const val VERSION = 3
    }

    /**
     * v2 to v3's one step beyond Room's DDL. An external-content FTS table is
     * created empty, and its triggers index only rows written after them, so
     * without this every transaction from before the update is unfindable.
     * `rebuild` reads `txn` whole: one pass, inside the migration's
     * transaction.
     */
    class IndexExistingRows : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT INTO txn_fts(txn_fts) VALUES ('rebuild')")
        }
    }

    abstract fun rawCaptureDao(): RawCaptureDao
    abstract fun txnDao(): TxnDao
    abstract fun categoryDao(): CategoryDao
    abstract fun captureSourceDao(): CaptureSourceDao
    abstract fun captureDayDao(): CaptureDayDao

    /**
     * `merchant_rule` has been an entity of this database since Task 8 and had
     * no DAO until the export needed to read it. Adding one changes no DDL, so
     * `schemas/1.json` and its identity hash are untouched.
     */
    abstract fun merchantRuleDao(): MerchantRuleDao

    abstract fun merchantIdentityDao(): MerchantIdentityDao

    /**
     * Whether [close] has been called on this instance, which Room's own
     * `isOpen` cannot say. Measured on emulator-5554: `runInTransaction` or
     * `openHelper.writableDatabase` on a closed instance reopens its helper
     * without a word, `isOpen` then answers true, and a second [close] leaves
     * it open. [Databases.whileLive] refuses on this, and on [retired].
     */
    @Volatile var closed: Boolean = false
        private set

    /**
     * Whether [Databases] has stopped serving this instance: [Databases.retire]
     * sets it when a connection of it met damage, and [Databases.reset] for a
     * delete or a restore. Open, unlike a [closed] one, until the last
     * [Databases.leasing] lease is out (or [Databases.reset]'s bound has
     * passed); [Databases.whileLive] refuses it all the same, because it is
     * no longer the database [Databases.shared] serves.
     */
    @Volatile var retired: Boolean = false
        private set

    internal fun retire() {
        retired = true
    }

    override fun close() {
        closed = true
        super.close()
    }
}
