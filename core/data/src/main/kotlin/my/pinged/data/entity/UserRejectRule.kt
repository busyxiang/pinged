package my.pinged.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Spec 4 and 5.7: the user's "never a transaction" on an unread capture. It
 * hides an `UNMATCHED` capture of [sourcePackage] whose skeleton equals
 * [skeleton] from the unread list, and decides nothing else -- stage two
 * consults it only once every template and pack reject has failed.
 *
 * **No `hit_count`, unlike spec 4's table.** What the settings list shows is
 * how many captures a rule hides now, which `raw_capture.user_reject_rule_id`
 * already says; a stored counter would be a second answer that a capture
 * leaving `UNMATCHED` on a pack upgrade makes wrong.
 *
 * `raw_capture.user_reject_rule_id` points here with no foreign key: adding
 * one would rebuild `raw_capture` in the migration. `UserRejectRuleDao.delete`
 * clears it, and `ImportJson` refuses a file in which it points at nothing.
 */
@Entity(
    tableName = "user_reject_rule",
    // Spec 15.1's "reject rule lookup", which stage two runs for every
    // unmatched capture. Unique, because equality is the whole match: a second
    // rule of the same shape could hide nothing the first does not.
    indices = [Index(value = ["source_package", "skeleton"], unique = true)],
)
data class UserRejectRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    val skeleton: String,
    /** The notification the rule was made from, word for word, for review. */
    @ColumnInfo(name = "sample_text") val sampleText: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
