package my.pinged.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import my.pinged.parse.Direction
import my.pinged.parse.TaughtTemplate

/**
 * Spec 4 and 5.8: a rule the user taught by tapping a message's amount and
 * merchant. Stage two consults it for a capture of [sourcePackage] only once
 * every pack template has failed, so it can never shadow a rule that ships.
 *
 * Nothing references it. A transaction it wrote names it only in
 * `raw_capture.matched_rule_id`, as text ([TaughtTemplate.ruleId]), so deleting
 * one leaves every transaction it produced as it was.
 */
@Entity(
    tableName = "user_template_rule",
    // Unique, because a pattern is the whole message shape: a second rule of
    // the same one could read nothing the first does not.
    indices = [Index(value = ["source_package", "pattern"], unique = true)],
)
data class UserTemplateRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    /** [TaughtTemplate.derive]'s, stored as derived, so a later derivation changes no saved rule. */
    val pattern: String,
    /** The user's answer to "money out, or in?", never inferred from the text (spec 5.4). */
    val direction: Direction,
    /** False reads amount only, and spec 7.1's missing-merchant reason holds what it reads. */
    @ColumnInfo(name = "has_merchant_group") val hasMerchantGroup: Boolean,
    /** Transactions it has written; below [TaughtTemplate.REVIEWED_MATCHES] each waits for review. */
    @ColumnInfo(name = "match_count") val matchCount: Int = 0,
    /** The notification it was taught on, word for word, for the settings list. */
    @ColumnInfo(name = "sample_text") val sampleText: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    fun stored() = TaughtTemplate.Stored(id, sourcePackage, pattern, direction, matchCount)
}
