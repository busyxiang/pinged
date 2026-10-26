package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import my.pinged.data.entity.MerchantRule

/**
 * `merchant_rule` as a table: insert, the export's cursor and the integrity
 * scans. Spec 12 names learned rules as one of the four things the export
 * carries, so a restore that dropped this table would silently lose every
 * category the user had taught.
 *
 * **Nothing here knows what a row means.** Which identity a rule files, whether
 * it is dormant, and teaching and forgetting it are [MerchantIdentityDao]'s.
 * The dictionary is parser-pack data and never a row of this table.
 */
@Dao
interface MerchantRuleDao {
    /**
     * Plain `@Insert`, i.e. `ABORT`. The unique index on
     * `(match_type, pattern, scoped_package)` exists to stop one merchant
     * resolving to two categories, so a backup file that carries two rules
     * colliding on it must fail the import rather than have one of them
     * silently win -- `IGNORE` or `REPLACE` here would each pick a winner and
     * say nothing.
     */
    @Insert
    fun insert(rule: MerchantRule): Long

    /**
     * The batch form, for [my.pinged.ledger.transfer.ImportJson].
     *
     * Room's `@Insert(List)` runs the same number of INSERT statements as a
     * loop does -- see `CategoryDao.seedIfEmpty` -- but acquires the prepared
     * statement once and rebinds per row instead of acquiring and releasing
     * per row. Measured on a Pixel 10a emulator at 20,000 rows inside one
     * transaction: 997ms per-row against 231ms in batches of 500.
     */
    @Insert
    fun insertAll(rules: List<MerchantRule>)

    @Query("SELECT COUNT(*) FROM merchant_rule")
    fun countAll(): Int

    /**
     * The export cursor: keyset on `id`, not `LIMIT/OFFSET`.
     *
     * `RawCaptureDao.pageAfter` records the measurement. Learned rules grow with
     * the number of distinct merchants the user has ever taught, which has no
     * ceiling, so the argument applies even though the table is small today.
     * `id > :afterId ORDER BY id ASC` is served by the integer primary key.
     */
    @Query("SELECT * FROM merchant_rule WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    fun pageFrom(afterId: Long, limit: Int): List<MerchantRule>

    /**
     * [pageFrom] starting **at** [fromId]. Salvage's read: why it is inclusive
     * is on `RawCaptureDao.pageStartingAt`.
     */
    @Query("SELECT * FROM merchant_rule WHERE id >= :fromId ORDER BY id ASC LIMIT :limit")
    fun pageStartingAt(fromId: Long, limit: Int): List<MerchantRule>

    /** Every id, off `merchant_rule(category_id)` and not the table's pages. */
    @Query("SELECT id FROM merchant_rule INDEXED BY index_merchant_rule_category_id")
    fun idsFromCategoryIndex(): List<Long>

    /** [idsFromCategoryIndex] off a second index. */
    @Query("SELECT id FROM merchant_rule INDEXED BY index_merchant_rule_pattern_priority")
    fun idsFromPatternIndex(): List<Long>

    /** The highest id ever assigned, from `sqlite_sequence`; null if none. */
    @Query("SELECT seq FROM sqlite_sequence WHERE name = 'merchant_rule'")
    fun highestIdEver(): Long?

    companion object {
        /**
         * What the writer stores in `priority`. Nothing orders learned rules
         * against each other (the unique index allows one per identity) and the
         * dictionary is not a table, so the value only has to match what
         * `Fixtures.insertMerchantRule` has always used.
         */
        const val LEARNED_PRIORITY = 100
    }
}
