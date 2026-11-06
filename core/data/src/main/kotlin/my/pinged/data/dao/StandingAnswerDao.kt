package my.pinged.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.entity.StandingAnswer

/**
 * Spec 7.3's standing answers: made from the review inbox's Always, read by
 * stage two, listed and deleted under Settings' LEFT OUT.
 *
 * Every lookup compares `merchant_key` with `IS`, so a null key matches only
 * a null key; see [StandingAnswer] for why the unique index cannot.
 */
@Dao
interface StandingAnswerDao {
    /** Every answer, for the export: one per transfer the user answered for good, so read whole. */
    @Query("SELECT * FROM standing_answer ORDER BY id ASC")
    fun all(): List<StandingAnswer>

    @Query("SELECT COUNT(*) FROM standing_answer")
    fun countAll(): Int

    /**
     * The import's batch insert. Plain `ABORT`, so a duplicate with a merchant
     * key fails the restore; [duplicateCount] finds one without.
     */
    @Insert
    fun insertAll(answers: List<StandingAnswer>)

    /** The answer for exactly this rule, app and counterparty, if there is one. */
    @Query(
        """
        SELECT id FROM standing_answer
        WHERE matched_rule_id = :ruleId AND source_package = :pkg AND merchant_key IS :merchantKey
        """,
    )
    fun idFor(ruleId: String, pkg: String, merchantKey: String?): Long?

    @Insert
    fun insert(answer: StandingAnswer): Long

    /**
     * Always: [answer], unless one of its key is already stored. Returns the
     * new row's id, or null when nothing was written. One transaction, so
     * two taps cannot both pass the check; Room 2.8's single connection
     * serialises it against every other writer.
     */
    @Transaction
    fun answer(answer: StandingAnswer): Long? =
        if (idFor(answer.matchedRuleId, answer.sourcePackage, answer.merchantKey) != null) null else insert(answer)

    /** Forget answer [id]. Later captures go back to review; no `txn` row changes. */
    @Query("DELETE FROM standing_answer WHERE id = :id")
    fun delete(id: Long): Int

    /**
     * Keys stored more than once. `GROUP BY` puts NULLs in one group, so this
     * counts the null-key duplicates the unique index lets through. Zero on
     * every file the app wrote.
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT 1 FROM standing_answer
            GROUP BY matched_rule_id, source_package, merchant_key HAVING COUNT(*) > 1
        )
        """,
    )
    fun duplicateCount(): Int

    /**
     * The settings list, newest first, each with its app's label and its
     * counterparty's name. The name is the exact key's, never its merged
     * identity's, since the answer matches only that key: the user's rename of
     * it, else what the latest transaction to it was shown as.
     */
    @Query(
        """
        SELECT a.id, a.matched_rule_id, a.source_package, a.merchant_key, s.label,
               CASE WHEN a.merchant_key IS NULL THEN NULL ELSE COALESCE(
                   (SELECT display FROM merchant_name WHERE merchant_name.merchant_key = a.merchant_key),
                   (SELECT merchant_display FROM txn WHERE txn.merchant_key = a.merchant_key ORDER BY id DESC LIMIT 1),
                   a.merchant_key
               ) END AS counterparty
        FROM standing_answer a LEFT JOIN capture_source s ON s.pkg = a.source_package
        ORDER BY a.created_at DESC, a.id DESC
        """,
    )
    fun rows(): List<StandingAnswerRow>
}

/** One standing answer in the settings list. */
data class StandingAnswerRow(
    val id: Long,
    @ColumnInfo(name = "matched_rule_id") val matchedRuleId: String,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    @ColumnInfo(name = "merchant_key") val merchantKey: String?,
    /** `capture_source.label`, or null for an app no longer on the list. */
    val label: String?,
    /** The counterparty's name; null exactly when [merchantKey] is. */
    val counterparty: String?,
)
