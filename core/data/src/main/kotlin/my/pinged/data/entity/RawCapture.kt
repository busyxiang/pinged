package my.pinged.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Spec 4.
 *
 * `GAVE_UP` is not in spec 4's list and is deliberate.
 * [my.pinged.parse.MatchOutcome.GaveUp] means a pattern exhausted its time
 * budget, and on a device that budget is wall clock -- a descheduled worker
 * produces it during an ordinary match. Recorded as `UNMATCHED` it would be
 * indistinguishable from "no rule matches this text", which is the signal spec
 * 5.6's authoring loop reads and spec 5.5 re-parses against.
 */
enum class ParseStatus {
    NEW, MATCHED, UNMATCHED, REJECTED, DUPLICATE_OF, UPDATE_OF, NO_EXTRAS, GAVE_UP,
}

/**
 * Which delivery path produced a capture (spec 4, spec 7.2). `POSTED` is
 * `onNotificationPosted`; `CATCHUP` is spec 10.1's `getActiveNotifications()`
 * sweep on `onListenerConnected`.
 *
 * **Nothing branches on it any more** -- duplicate layer 1 rule 1 used to, and
 * [my.pinged.data.slotRefreshOutcome] records why that lost money. Still
 * recorded at capture time, because nothing can reconstruct it afterwards.
 */
enum class Arrival { POSTED, CATCHUP }

@Entity(
    tableName = "raw_capture",
    indices = [
        // (content_hash, posted_at), not a bare content_hash. Duplicate layer 1
        // rule 2 runs per capture and is `content_hash = ? AND posted_at
        // BETWEEN ? AND ?`; the review measured ~5.6 rows per hash with a max
        // of 19, so a bare content_hash index makes SQLite fetch and discard
        // up to 19 whole rows per capture to apply a predicate the index could
        // have applied itself.
        Index(value = ["content_hash", "posted_at"]),
        // Duplicate layer 1 rule 1, which is `sbn_key = ? AND content_hash = ?
        // AND id < ?`. The equality is on sbn_key and the rest is filtered off
        // the rowid, so a leading sbn_key index serves it.
        Index("sbn_key"),
        // Stage two's work queue and the unread list, both
        // `parse_status = ? ORDER BY posted_at ASC`.
        Index(value = ["parse_status", "posted_at"]),
        // Spec 15.5's keyset re-parse cursor, `parse_status = ? AND id > ?
        // ORDER BY id ASC`. Measured on emulator-5554: without it that query
        // uses the (parse_status, posted_at) index and then adds
        // `USE TEMP B-TREE FOR ORDER BY`, so the cursor sorts its whole
        // candidate set on every chunk -- which defeats the point of paging by
        // keyset rather than by OFFSET.
        Index(value = ["parse_status", "id"]),
    ],
)
data class RawCapture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    /** sbn.postTime. Never notification.when — see spec section 4. */
    @ColumnInfo(name = "posted_at") val postedAt: Long,
    /** notification.when, kept but trusted only when non-zero. */
    @ColumnInfo(name = "when_millis") val whenMillis: Long?,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "sbn_key") val sbnKey: String,
    @ColumnInfo(name = "notif_id") val notifId: Int,
    @ColumnInfo(name = "notif_tag") val notifTag: String?,
    @ColumnInfo(name = "user_handle") val userHandle: Int,
    @ColumnInfo(name = "channel_id") val channelId: String?,
    val flags: Int,
    /**
     * No Kotlin default, deliberately. Spec 4: nothing can reconstruct the
     * arrival path afterwards, so the writer must state it. A default would let
     * a future caller silently record every catch-up capture as `POSTED`, and
     * the column would then be a record of nothing.
     */
    val arrival: Arrival,
    val title: String?,
    val text: String?,
    @ColumnInfo(name = "big_text") val bigText: String?,
    @ColumnInfo(name = "sub_text") val subText: String?,
    @ColumnInfo(name = "extras_json") val extrasJson: String?,
    /** sha256(package, user, normalized text). No timestamp — see spec 7.2. */
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "parse_status") val parseStatus: ParseStatus = ParseStatus.NEW,
    @ColumnInfo(name = "rejected_by_rule_id") val rejectedByRuleId: String? = null,
    @ColumnInfo(name = "matched_rule_id") val matchedRuleId: String? = null,
    @ColumnInfo(name = "reject_collision_id") val rejectCollisionId: String? = null,
    @ColumnInfo(name = "pack_version") val packVersion: Int = 0,
    @ColumnInfo(name = "duplicate_of_id") val duplicateOfId: Long? = null,
    @ColumnInfo(name = "user_reject_rule_id") val userRejectRuleId: Long? = null,
)
