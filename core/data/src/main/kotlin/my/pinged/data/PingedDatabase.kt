package my.pinged.data

import androidx.room.Database
import androidx.room.RoomDatabase
import my.pinged.data.dao.CaptureDayDao
import my.pinged.data.dao.CaptureSourceDao
import my.pinged.data.dao.CategoryDao
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.CaptureDay
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.Category
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn

@Database(
    entities = [
        RawCapture::class, Txn::class, Category::class, CaptureSource::class,
        MerchantRule::class, CaptureDay::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PingedDatabase : RoomDatabase() {
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
}
