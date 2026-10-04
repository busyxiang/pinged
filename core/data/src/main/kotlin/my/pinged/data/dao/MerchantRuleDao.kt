package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import my.pinged.data.entity.MerchantRule

/**
 * `merchant_rule` had no DAO at all until export needed one, and that absence
 * is worth stating rather than quietly fixing.
 *
 * The entity, the `(pattern, priority)` index and the unique constraint were
 * declared in task 8; the learned-rule writer arrives with the categorizer in a
 * later milestone. So the table is real and the only way to put a row in it was
 * `Fixtures.insertMerchantRule`, a `ContentValues` write in `androidTest`.
 *
 * Spec 12 names learned rules as one of the four things the export carries, and
 * spec 6.1 describes them as what makes one tap permanent. An export that
 * skipped this table because no production code read it would lose every
 * category the user had taught the app, silently -- the restore would look
 * complete and the next unrecognised merchant would land in Uncategorized.
 *
 * The methods here are the minimum export and import need: nothing yet resolves
 * a rule, because nothing yet learns one.
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
}
