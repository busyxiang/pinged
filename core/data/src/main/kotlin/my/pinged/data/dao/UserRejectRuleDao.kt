package my.pinged.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.UserRejectRule
import my.pinged.parse.Skeleton

/**
 * Spec 5.6's unread list and 5.7's user reject rules, which decide only what
 * that list shows.
 *
 * "Unread" is `parse_status = UNMATCHED` with no rule hiding it. `NO_EXTRAS`
 * is not unread -- there is no text to read (spec 3) -- and neither is
 * `GAVE_UP`, which re-parse revisits.
 */
@Dao
interface UserRejectRuleDao {
    /**
     * The unread list, newest first, a page at a time from the keyset
     * ([beforePostedAt], [beforeId]); `Long.MAX_VALUE` twice for the first.
     * Served by `raw_capture(parse_status, posted_at)`, read backwards.
     */
    @Query(UNREAD_PAGE_SQL)
    fun unreadPage(
        beforePostedAt: Long,
        beforeId: Long,
        limit: Int,
        status: ParseStatus = ParseStatus.UNMATCHED,
    ): List<UnreadCapture>

    /** The settings row's value: [unreadPage]'s rows, all of them. */
    @Query(UNREAD_COUNT_SQL)
    fun unreadCount(status: ParseStatus = ParseStatus.UNMATCHED): Int

    /**
     * Every rule, newest first, each with the captures it hides now. A
     * subquery per rule over the `UNMATCHED` range of the status index; there
     * are as many rules as message shapes a user has dismissed.
     */
    @Query(RULES_SQL)
    fun rules(status: ParseStatus = ParseStatus.UNMATCHED): List<RejectRuleRow>

    @Query("SELECT COUNT(*) FROM user_reject_rule")
    fun countAll(): Int

    /** Every row, for the export: one per shape dismissed, so read whole. */
    @Query("SELECT * FROM user_reject_rule ORDER BY id ASC")
    fun all(): List<UserRejectRule>

    /** The import's batch insert. Plain `ABORT`, so a duplicate shape fails the restore. */
    @Insert
    fun insertAll(rules: List<UserRejectRule>)

    @Query(RULE_LOOKUP_SQL)
    fun idFor(pkg: String, skeleton: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOrIgnore(rule: UserRejectRule): Long

    /** The capture "never a transaction" was tapped on, or null once it has left the list. */
    @Query(
        """
        SELECT c.id, c.source_package, c.posted_at, c.title, c.text, c.big_text, NULL AS label
        FROM raw_capture c
        WHERE c.id = :id AND c.parse_status = :status AND c.user_reject_rule_id IS NULL
        """,
    )
    fun unreadById(id: Long, status: ParseStatus = ParseStatus.UNMATCHED): UnreadCapture?

    /** The unread captures of [pkg], for [neverATransaction]'s skeleton comparison. */
    @Query(
        """
        SELECT c.id, c.source_package, c.posted_at, c.title, c.text, c.big_text, NULL AS label
        FROM raw_capture c
        WHERE c.parse_status = :status AND c.source_package = :pkg AND c.user_reject_rule_id IS NULL
        """,
    )
    fun unreadOf(pkg: String, status: ParseStatus = ParseStatus.UNMATCHED): List<UnreadCapture>

    @Query(
        """
        UPDATE raw_capture SET user_reject_rule_id = :ruleId
        WHERE id IN (:ids) AND parse_status = :status AND user_reject_rule_id IS NULL
        """,
    )
    fun hide(ids: List<Long>, ruleId: Long, status: ParseStatus = ParseStatus.UNMATCHED): Int

    /**
     * Spec 5.7's "never a transaction" on [captureId]: a rule of its skeleton,
     * and every unread capture of the same package and skeleton hidden by it,
     * this one among them. Returns how many left the list -- 0 when the
     * capture already had (a pack upgrade matched it, or a second tap).
     *
     * One transaction, as is stage two's [RawCaptureDao.markUnmatched], so a
     * capture stage two marks unread while this runs is either seen here or
     * finds the rule there; Room 2.8's single connection serialises the two.
     *
     * Hides only `UNMATCHED` rows, and touches no other column: a rule decides
     * what the unread list shows and never whether a transaction exists.
     */
    @Transaction
    fun neverATransaction(captureId: Long, now: Long): Int {
        val capture = unreadById(captureId) ?: return 0
        val skeleton = capture.skeleton()
        val ruleId = idFor(capture.sourcePackage, skeleton) ?: insertOrIgnore(
            UserRejectRule(
                sourcePackage = capture.sourcePackage,
                skeleton = skeleton,
                sampleText = capture.shownText(),
                createdAt = now,
            ),
        )
        val same = unreadOf(capture.sourcePackage).filter { it.skeleton() == skeleton }.map { it.id }
        // SQLite's default bound-parameter ceiling is 999; one shape of one
        // app's messages past that is a list nobody would have read anyway.
        return same.chunked(500).sumOf { hide(it, ruleId) }
    }

    @Query("UPDATE raw_capture SET user_reject_rule_id = NULL WHERE user_reject_rule_id = :ruleId")
    fun unhide(ruleId: Long): Int

    @Query("DELETE FROM user_reject_rule WHERE id = :ruleId")
    fun deleteRow(ruleId: Long): Int

    /**
     * Forget rule [ruleId]: its captures come back to the unread list (spec
     * 5.7), whatever their status, so none is left naming a rule that is gone.
     * A full scan of `raw_capture`, since the column has no index; it runs on
     * a tap, not per capture.
     */
    @Transaction
    fun delete(ruleId: Long): Int {
        unhide(ruleId)
        return deleteRow(ruleId)
    }

    /** The SQL `QueryPlanTest` plans, from here rather than a copy. */
    companion object {
        const val UNREAD_PAGE_SQL = """
        SELECT c.id, c.source_package, c.posted_at, c.title, c.text, c.big_text, s.label
        FROM raw_capture c LEFT JOIN capture_source s ON s.pkg = c.source_package
        WHERE c.parse_status = :status AND c.user_reject_rule_id IS NULL
          AND (c.posted_at < :beforePostedAt OR (c.posted_at = :beforePostedAt AND c.id < :beforeId))
        ORDER BY c.posted_at DESC, c.id DESC
        LIMIT :limit
        """

        const val UNREAD_COUNT_SQL =
            "SELECT COUNT(*) FROM raw_capture WHERE parse_status = :status AND user_reject_rule_id IS NULL"

        const val RULES_SQL = """
        SELECT r.id, r.source_package, r.sample_text, r.created_at, s.label,
          (SELECT COUNT(*) FROM raw_capture c
           WHERE c.parse_status = :status AND c.user_reject_rule_id = r.id) AS hidden
        FROM user_reject_rule r LEFT JOIN capture_source s ON s.pkg = r.source_package
        ORDER BY r.created_at DESC, r.id DESC
        """

        /** Spec 15.1's reject rule lookup, which stage two runs per unmatched capture. */
        const val RULE_LOOKUP_SQL =
            "SELECT id FROM user_reject_rule WHERE source_package = :pkg AND skeleton = :skeleton"
    }
}

/** One card of the unread list. */
data class UnreadCapture(
    val id: Long,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    @ColumnInfo(name = "posted_at") val postedAt: Long,
    val title: String?,
    val text: String?,
    @ColumnInfo(name = "big_text") val bigText: String?,
    /** `capture_source.label`, or null for an app no longer on the list. */
    val label: String?,
) {
    fun skeleton(): String = Skeleton.ofCapture(title, text, bigText)

    /** The body a template reads, spec 5.2's `concat` less its title: what the card quotes. */
    val body: String? get() = bigText ?: text

    /** The title and [body], each on its own line: a rule's sample, word for word. */
    fun shownText(): String = listOfNotNull(title, body).joinToString("\n")
}

/** One rule in the settings list. */
data class RejectRuleRow(
    val id: Long,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    @ColumnInfo(name = "sample_text") val sampleText: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val label: String?,
    /** `UNMATCHED` captures it hides now. */
    val hidden: Int,
)
