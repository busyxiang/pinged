package my.pinged.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.UserTemplateRule
import my.pinged.parse.Direction

/** Spec 5.8's taught rules: saved from the Teach screen, read by stage two, listed and deleted in settings. */
@Dao
interface UserTemplateRuleDao {
    /** Every rule, for stage two and the export: one per message shape a user taught, so read whole. */
    @Query("SELECT * FROM user_template_rule ORDER BY id ASC")
    fun all(): List<UserTemplateRule>

    @Query("SELECT COUNT(*) FROM user_template_rule")
    fun countAll(): Int

    /** The import's batch insert. Plain `ABORT`, so a duplicate shape fails the restore. */
    @Insert
    fun insertAll(rules: List<UserTemplateRule>)

    /** The settings list, newest first. */
    @Query(
        """
        SELECT r.id, r.source_package, r.sample_text, r.direction, r.has_merchant_group, r.match_count, s.label
        FROM user_template_rule r LEFT JOIN capture_source s ON s.pkg = r.source_package
        ORDER BY r.created_at DESC, r.id DESC
        """,
    )
    fun rows(): List<TaughtRuleRow>

    @Query("SELECT id FROM user_template_rule WHERE source_package = :pkg AND pattern = :pattern")
    fun idFor(pkg: String, pattern: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOrIgnore(rule: UserTemplateRule): Long

    @Query(
        """
        UPDATE raw_capture SET parse_status = :to
        WHERE id IN (:ids) AND parse_status = :from AND user_reject_rule_id IS NULL
        """,
    )
    fun requeue(ids: List<Long>, from: ParseStatus = ParseStatus.UNMATCHED, to: ParseStatus = ParseStatus.NEW): Int

    /**
     * Spec 5.8's "Save this rule": [rule], or the one of its shape already
     * saved, and [unread] -- the unread captures the caller found it reads,
     * the one taught on among them -- handed back to stage two at `NEW`, so
     * they become transactions on its next run. Only a capture still unread
     * moves; one stage two or a pack upgrade decided since is left alone.
     *
     * A shape taught again keeps its row and its count, and takes the
     * direction answered this time: the user is correcting it.
     */
    @Transaction
    fun teach(rule: UserTemplateRule, unread: List<Long>): Long {
        val id = idFor(rule.sourcePackage, rule.pattern)?.also { setDirection(it, rule.direction) }
            ?: insertOrIgnore(rule)
        // SQLite's default bound-parameter ceiling is 999.
        unread.chunked(500).forEach { requeue(it) }
        return id
    }

    @Query("UPDATE user_template_rule SET direction = :direction WHERE id = :id")
    fun setDirection(id: Long, direction: Direction): Int

    /** Forget rule [id]. The transactions it wrote stay as they are (spec 5.8). */
    @Query("DELETE FROM user_template_rule WHERE id = :id")
    fun delete(id: Long): Int
}

/** One taught rule in the settings list. */
data class TaughtRuleRow(
    val id: Long,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    @ColumnInfo(name = "sample_text") val sampleText: String,
    val direction: Direction,
    @ColumnInfo(name = "has_merchant_group") val hasMerchantGroup: Boolean,
    @ColumnInfo(name = "match_count") val matchCount: Int,
    /** `capture_source.label`, or null for an app no longer on the list. */
    val label: String?,
)
