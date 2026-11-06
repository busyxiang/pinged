package my.pinged.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Spec 4 and 7.3: a standing answer, the user's "always leave out" given once
 * on a transfer card. Stage two consults it for a capture it reads for the
 * first time, and a match commits the transaction excluded, exactly as Leave
 * out would have written it.
 *
 * The key is the three columns, compared exactly:
 *
 * - [matchedRuleId] as `raw_capture.matched_rule_id` stores it. A rule's `-v2`
 *   is another id, and its captures go back to review.
 * - [sourcePackage], the app.
 * - [merchantKey], `txn.merchant_key` as the rule read it, or null for none,
 *   which matches only none. Not the merchant's identity: a merge neither
 *   moves nor copies an answer.
 *
 * **The unique index does not cover a null [merchantKey]**: SQLite treats
 * NULLs as distinct in one. `StandingAnswerDao.answer` checks with `IS`
 * before it inserts, and `ImportJson` refuses a file holding two of one key.
 *
 * Nothing references it, and no `txn` row records that one wrote it:
 * deleting one changes no row.
 */
@Entity(
    tableName = "standing_answer",
    indices = [Index(value = ["matched_rule_id", "source_package", "merchant_key"], unique = true)],
)
data class StandingAnswer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "matched_rule_id") val matchedRuleId: String,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    @ColumnInfo(name = "merchant_key") val merchantKey: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
