package my.pinged.capture

import android.app.Notification
import android.service.notification.StatusBarNotification
import my.pinged.parse.TextNormalizer

/**
 * Everything stage one takes off a [StatusBarNotification], and nothing else.
 *
 * Spec section 3: extract, filter, hand off. No matching, no normalization
 * beyond the hash input, no database work here.
 */
internal data class NotificationFields(
    val sourcePackage: String,
    val postedAt: Long,
    val whenMillis: Long?,
    val sbnKey: String,
    val notifId: Int,
    val notifTag: String?,
    val userHandle: Int,
    val channelId: String?,
    val flags: Int,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
) {

    /**
     * The text half of `content_hash`. `bigText` wins over `text` because it
     * is the superset when both are present, and `subText` is left out: it
     * carries an account label rather than the event, and folding it in would
     * give the same payment two identities depending on whether the bank chose
     * to include it.
     */
    val normalizedForHash: String
        get() = TextNormalizer.forCompare(listOfNotNull(title, bigText ?: text).joinToString(" "))

    companion object {
        fun from(sbn: StatusBarNotification): NotificationFields? {
            val notification = sbn.notification ?: return null
            val extras = notification.extras ?: return null

            // getCharSequence, never getString. `Bundle.getString` casts
            // internally and swallows the failure, so it returns null for a
            // SpannableString -- and banks and wallets very commonly bold the
            // amount, which makes the value a Spannable. This single line
            // decides whether they are captured at all (spec 3).
            fun str(key: String): String? {
                val raw = extras.getCharSequence(key)?.toString() ?: return null
                // Emptiness is judged after folding Unicode spaces and
                // invisibles, because Kotlin's isBlank() and trim() both go
                // through Char.isWhitespace(), which is false for U+00A0 and
                // every other non-breaking space. The value stored is the raw
                // one, trimmed: raw_capture is the audit trail (spec 4), and
                // stage two is where normalization belongs.
                if (TextNormalizer.forMatch(raw).isEmpty()) return null
                return raw.trim()
            }

            return NotificationFields(
                sourcePackage = sbn.packageName,
                // sbn.postTime, never notification.when. `when` is
                // app-controlled and regularly zero, and a zero there dates a
                // transaction to 1 January 1970, where it vanishes from every
                // month view and every chart while still counting as captured
                // (spec 4).
                postedAt = sbn.postTime,
                whenMillis = notification.`when`.takeIf { it > 0L },
                sbnKey = sbn.key,
                notifId = sbn.id,
                notifTag = sbn.tag,
                // UserHandle.hashCode() is the user identifier. The exact
                // accessor, getUserId(), is deprecated in the platform stubs,
                // and the value matters: a work profile posts under the same
                // package name as the personal one, so without the user handle
                // in the row and in content_hash, cross-profile posts collapse
                // into each other (spec 3).
                userHandle = sbn.user.hashCode(),
                channelId = notification.channelId,
                flags = notification.flags,
                title = str(Notification.EXTRA_TITLE),
                text = str(Notification.EXTRA_TEXT),
                bigText = str(Notification.EXTRA_BIG_TEXT),
                subText = str(Notification.EXTRA_SUB_TEXT),
            )
        }
    }
}
