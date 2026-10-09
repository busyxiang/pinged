package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RuleOrigin

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
 * Learned rules (#48) are one per merchant identity: `EXACT`, `LEARNED`, unscoped,
 * the pattern the tapped row's canonical key. [teach] writes one and [learnedCategoryFor]
 * is stage two's read of it. Nothing here writes a `BUNDLED` rule or a `CONTAINS`
 * one; the dictionary is parser-pack data and never a row of this table.
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

    /**
     * The category the learned rule for [key]'s merchant identity files under,
     * or null. One statement, which is stage two's per-capture read: the
     * identity is `COALESCE(canonical_key, key)` as `MerchantSql.IDENTITY` has
     * it, resolved by a primary-key subquery on `merchant_alias`, and the rule is
     * found through the unique index on `(match_type, pattern, scoped_package)`.
     *
     * A rule on a key that has been merged away is not found, because the
     * identity it would be read under is the target's: it stays stored for
     * Separate, as `merchant_name` does. A `BUNDLED`, `CONTAINS` or scoped row is
     * not a learned rule and is not read.
     */
    @Query(
        """
        SELECT category_id FROM merchant_rule
        WHERE """ + MerchantSql.LEARNED + """
          AND pattern = COALESCE((SELECT canonical_key FROM merchant_alias WHERE merchant_key = :key), :key)
        """,
    )
    fun learnedCategoryFor(
        key: String,
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): Long?

    /**
     * Every learned rule's identity and category, for the ledger's read-time
     * "filed by the user's rule" marking. The table has one row per merchant
     * the user has taught, so it is read whole rather than once per row.
     */
    @Query(
        """
        SELECT pattern, category_id AS categoryId FROM merchant_rule
        WHERE """ + MerchantSql.LEARNED + """
        """,
    )
    fun learnedRules(
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): List<LearnedRow>

    /**
     * The settings list (#50): one row per live learned rule, A-Z by name.
     *
     * - **Live only.** A rule whose pattern is itself merged away (`merchant_alias`
     *   `merchant_key`) is the dormant source rule kept for Separate; it is not
     *   a merchant the user sees, so it is neither listed nor counted.
     * - **name** is the user's merchant name for the identity, else what the
     *   identity's own latest non-rejected row (highest `id`) shows now, else
     *   the rule's `merchant_display`. That column is the row's name when the
     *   rule was written and nothing updates it when a pack upgrade re-keys the
     *   merchant ([MerchantDecisions.moveLearned]) or a merge copies the rule
     *   ([MerchantIdentityDao.carryRule]), so it is only the last resort, for
     *   a rule with no payments left to name it.
     * - **payments** is spec 6.3's count: committed, non-excluded rows of the
     *   identity (the pattern, or any key merged into it), counted live because
     *   `merchant_rule.hit_count` is unused and nothing increments it. Both
     *   branches are `merchant_key` lookups.
     *
     * `COLLATE NOCASE` orders `mr diy` among the Ms; the pattern then breaks ties
     * so two shops with one name keep a stable order.
     */
    @Query(
        """
        SELECT r.pattern AS identity,
               COALESCE(
                   (SELECT display FROM merchant_name WHERE merchant_key = r.pattern),
                   (SELECT COALESCE(t.merchant_display, t.merchant_raw) FROM txn t
                    WHERE t.merchant_key = r.pattern AND t.state != 'REJECTED' ORDER BY t.id DESC LIMIT 1),
                   r.merchant_display
               ) AS name,
               r.category_id AS categoryId,
               category.name AS categoryName,
               (SELECT COUNT(*) FROM txn
                WHERE """ + TxnDao.COUNTED + """
                  AND (txn.merchant_key = r.pattern
                       OR txn.merchant_key IN (SELECT merchant_key FROM merchant_alias WHERE canonical_key = r.pattern))
               ) AS payments
        FROM merchant_rule r
        JOIN category ON category.id = r.category_id
        WHERE """ + MerchantSql.LEARNED + """
          AND NOT EXISTS (SELECT 1 FROM merchant_alias WHERE merchant_key = r.pattern)
        ORDER BY name COLLATE NOCASE ASC, r.pattern ASC
        """,
    )
    fun learnedMerchants(
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): List<LearnedMerchant>

    /** The row count of [learnedMerchants], for the settings row's value. */
    @Query(
        """
        SELECT COUNT(*) FROM merchant_rule r
        WHERE """ + MerchantSql.LEARNED + """
          AND NOT EXISTS (SELECT 1 FROM merchant_alias WHERE merchant_key = r.pattern)
        """,
    )
    fun learnedCount(
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): Int

    /**
     * Forget the teaching for the whole identity [identity] (#50): its learned
     * rule and the dormant ones on keys merged into it, which would otherwise
     * come back through Separate. One statement; **no `txn` row is touched**.
     * Returns the rules deleted. The one-key form [MerchantDecisions.carry] uses is
     * `MerchantDecisions.deleteLearnedOn`.
     */
    @Query(
        """
        DELETE FROM merchant_rule
        WHERE """ + MerchantSql.LEARNED + """
          AND (pattern = :identity
               OR pattern IN (SELECT merchant_key FROM merchant_alias WHERE canonical_key = :identity))
        """,
    )
    fun deleteLearnedOfIdentity(
        identity: String,
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): Int

    /**
     * A teaching save (#48): the tapped row to [categoryId] **filed by the
     * rule, not by hand**, and the merchant's learned rule written or updated,
     * in one transaction. Returns false, writing nothing, when the row has no
     * merchant key (or is gone): there is nothing to learn from, and the caller
     * saves a one-off.
     *
     * `user_edited` goes to 0, which is what makes `user_edited` mean exactly
     * "a one-off": a row the rule filed stays one the rule can later re-file.
     * Targeted statements throughout -- an `UPDATE` naming its columns, then an
     * `INSERT` only if it found no row -- never a whole-row `REPLACE`.
     */
    @Transaction
    fun teach(txnId: Long, categoryId: Long, now: Long): Boolean {
        val key = merchantKeyOf(txnId) ?: return false
        val identity = identityOf(key)
        fileByRule(txnId, categoryId, now)
        val updated = updateLearned(identity, categoryId, MatchType.EXACT, RuleOrigin.LEARNED, MerchantRule.UNSCOPED)
        if (updated == 0) {
            insert(
                MerchantRule(
                    matchType = MatchType.EXACT,
                    pattern = identity,
                    merchantDisplay = displayOf(txnId) ?: identity,
                    categoryId = categoryId,
                    origin = RuleOrigin.LEARNED,
                    priority = LEARNED_PRIORITY,
                ),
            )
        }
        return true
    }

    /**
     * What the chooser would also fix if the user taught [categoryId] on the
     * row [txnId] (#49): how many past payments of the merchant would move, and
     * which one-offs stand in the way.
     *
     * One read of one statement, so the two halves are of the same moment.
     * [RetroPreview.movable] is the eligible rows that are not one-offs and not
     * already in [categoryId]; [RetroPreview.conflicts] are the eligible
     * one-offs in any other category, per category. A one-off already in
     * [categoryId] is neither. Eligibility is [ELIGIBLE]. Dictionary-filed,
     * own-rule, old-rule and Uncategorized rows are all `user_edited = 0` and
     * so all movable: only `user_edited = 1` is confirmed history.
     */
    fun retroPreview(txnId: Long, categoryId: Long): RetroPreview {
        val key = merchantKeyOf(txnId) ?: return RetroPreview(0, emptyList())
        val groups = retroGroups(identityOf(key), txnId, categoryId)
        return RetroPreview(
            movable = groups.filter { !it.oneOff }.sumOf { it.total },
            conflicts = groups.filter { it.oneOff }.map { CategoryCount(it.categoryId, it.total) },
        )
    }

    /**
     * The eligible rows of [identity] other than [txnId] and not already in
     * [categoryId], counted per `(category_id, user_edited)`: what
     * [retroPreview] splits into the rows that would move (`user_edited = 0`)
     * and the one-offs that stand in the way (`user_edited = 1`).
     */
    @Query(
        """
        SELECT category_id AS categoryId, user_edited AS oneOff, COUNT(*) AS total FROM txn
        WHERE """ + ELIGIBLE + """ AND id != :txnId AND category_id != :categoryId
        GROUP BY category_id, user_edited
        """,
    )
    fun retroGroups(identity: String, txnId: Long, categoryId: Long): List<RetroGroup>

    /**
     * A teaching save that also fixes the merchant's past payments (#49): the
     * rule, the tapped row and the move of every other movable row, in **one
     * transaction that re-checks the conflict inside itself**. A payment the
     * user confirmed can land between the sheet's count and the tap, and the
     * count the sheet showed is then stale; this one is read after the rule is
     * written and before any row moves, under the write lock.
     *
     * A conflict is all-or-nothing: nothing moves, and the rule still saves.
     * Returns `taught = false`, writing nothing, for a row with no merchant key
     * (see [teach]).
     *
     * **A moved row has only `category_id` written.** `user_edited` stays 0,
     * which is what lets a later correction of the rule move it again, and
     * `updated_at` stays too: the ticket's "no other column changes" is taken
     * literally, and nothing here is a decision about that row by a person.
     *
     * The move is one bulk `UPDATE` and so holds the write lock for its
     * duration, under the caller's `Databases.leasing` lease.
     *
     * **Measured on emulator-5554, a Pixel 10a AVD and not a phone**, shared
     * with other work while it ran, by `RetroactiveFixTimingTest`: the whole
     * `teachAndFix`, moving every row of one merchant in a ledger twice that
     * size, five runs each, after [ELIGIBLE] took `+state`. 10,000 rows moved
     * (20,002 in the ledger): 40, 27, 26, 28, 26 ms. 100,000 moved (200,002 in
     * the ledger, the largest seeded): 6347, 3065, 4009, 3157, 3983 ms. The cost
     * per row is not constant (about 3us at 10,000, 31us at 100,000 on the
     * fastest run), so the figures do not extrapolate downward in a straight
     * line.
     *
     * **At 100,000 rows this breaks `CLAUDE.md`'s rule that no lease outlasts a
     * gate.** A gate waits `Databases.RESET_LEASE_WAIT_MILLIS` (5000) for the
     * lease and the first run, 6347 ms, is past it; the other four are 61% to
     * 80% of it, so one more loaded host would put them past as well. A gate
     * raised during such a move then closes the instance under a lease that is
     * still in its transaction: harmless only because both production callers
     * of a gate unlink the file next, which leaves the lock on a dead inode. A
     * gate already up is refused before the move starts (`Databases.shared`);
     * nothing can stop one statement once it is running.
     *
     * Not chunked: the ticket asks for one transaction, and splitting it would
     * let a capture land between chunks. No cheaper correct way to stay inside
     * the 5000 ms was found, so the risk is documented and not removed. The
     * device export this milestone is specified from holds 80 transactions; one
     * merchant reaching 10,000 is 27 years at a payment a day, so the 100,000
     * case is a bound and not a ledger anyone has. The 10,000 figure is asserted
     * to stay under the 5000 ms, and the 100,000 one is recorded only.
     */
    @Transaction
    fun teachAndFix(txnId: Long, categoryId: Long, now: Long): TeachOutcome {
        if (!teach(txnId, categoryId, now)) return TeachOutcome(taught = false, moved = 0, conflicts = 0)
        val preview = retroPreview(txnId, categoryId)
        val conflicts = preview.conflicts.sumOf { it.count }
        if (conflicts > 0) return TeachOutcome(taught = true, moved = 0, conflicts = conflicts)
        val identity = identityOf(requireNotNull(merchantKeyOf(txnId)))
        return TeachOutcome(taught = true, moved = moveToCategory(identity, txnId, categoryId), conflicts = 0)
    }

    /**
     * Every movable row of [identity] to [categoryId], touching `category_id`
     * alone. `user_edited = 0` is in the `WHERE` as well as being checked by
     * [retroPreview], so a one-off can never be overwritten even by a caller
     * that skipped the check.
     */
    @Query(
        """
        UPDATE txn SET category_id = :categoryId
        WHERE """ + ELIGIBLE + """ AND id != :txnId AND category_id != :categoryId AND user_edited = 0
        """,
    )
    fun moveToCategory(identity: String, txnId: Long, categoryId: Long): Int

    @Query("SELECT merchant_key FROM txn WHERE id = :txnId")
    fun merchantKeyOf(txnId: Long): String?

    @Query("SELECT COALESCE(merchant_display, merchant_raw) FROM txn WHERE id = :txnId")
    fun displayOf(txnId: Long): String?

    /** [key] resolved through `merchant_alias`, as `MerchantSql.IDENTITY` does for a row. */
    @Query("SELECT COALESCE((SELECT canonical_key FROM merchant_alias WHERE merchant_key = :key), :key)")
    fun identityOf(key: String): String

    @Query("UPDATE txn SET category_id = :categoryId, user_edited = 0, updated_at = :now WHERE id = :txnId")
    fun fileByRule(txnId: Long, categoryId: Long, now: Long): Int

    /**
     * Re-points the rule on the unique key `(match_type, pattern, scoped_package)`.
     * `origin` is set as well as matched on the key alone, so a row of another
     * origin already holding the key is taken over rather than colliding with
     * the insert and failing the save.
     */
    @Query(
        """
        UPDATE merchant_rule SET category_id = :categoryId, origin = :origin
        WHERE match_type = :matchType AND pattern = :pattern AND scoped_package = :scopedPackage
        """,
    )
    fun updateLearned(
        pattern: String,
        categoryId: Long,
        matchType: MatchType,
        origin: RuleOrigin,
        scopedPackage: String,
    ): Int

    companion object {
        /**
         * The rows a retroactive fix may count or move (#49): the merchant
         * identity `:identity`, committed and not excluded. Spelled as the
         * identity's own key plus every key aliased to it rather than as
         * `MerchantSql.IDENTITY` over a join, so the `merchant_key` index
         * serves it, which `QueryPlanTest` asserts. `+state` is SQLite's unary
         * plus, as in [TxnDao.COUNTED]: without it the planner took
         * `index_txn_state_occurred_at` on the equality and read every
         * committed payment of every merchant. `PENDING` rows are left to the
         * inbox and `REJECTED` rows are not transactions.
         */
        const val ELIGIBLE =
            "+state = 'COMMITTED' AND is_excluded = 0 AND merchant_key IN " +
                "(SELECT :identity UNION SELECT merchant_key FROM merchant_alias WHERE canonical_key = :identity)"

        /**
         * What the writer stores in `priority`. Nothing orders learned rules
         * against each other (the unique index allows one per identity) and the
         * dictionary is not a table, so the value only has to match what
         * `Fixtures.insertMerchantRule` has always used.
         */
        const val LEARNED_PRIORITY = 100
    }
}

/** One row of [MerchantRuleDao.learnedMerchants]. */
data class LearnedMerchant(
    val identity: String,
    val name: String,
    val categoryId: Long,
    val categoryName: String,
    val payments: Int,
)

/** One row of [MerchantRuleDao.learnedRules]. */
data class LearnedRow(val pattern: String, val categoryId: Long)

/** What [MerchantRuleDao.retroPreview] found. */
data class RetroPreview(val movable: Int, val conflicts: List<CategoryCount>)

/** [count] one-offs the user set to [categoryId] by hand. */
data class CategoryCount(val categoryId: Long, val count: Int)

/** One group of [MerchantRuleDao.retroGroups]. */
data class RetroGroup(val categoryId: Long, val oneOff: Boolean, val total: Int)

/**
 * What [MerchantRuleDao.teachAndFix] did: whether a rule was written, how many
 * past rows moved, and how many confirmed one-offs stopped them (then 0 moved).
 */
data class TeachOutcome(val taught: Boolean, val moved: Int, val conflicts: Int)
