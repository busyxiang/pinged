package my.pinged.capture

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.entity.ParseStatus

/**
 * Spec 5.5. Gives captures an older pack could not read back to stage two,
 * once per pack version.
 *
 * Without this a rule is only ever applied to what arrives after it ships. The
 * app captured payments it could not parse for two releases, and the notifications
 * were still on the table in full -- readable, and never re-read -- which on a
 * real device reads as an app that captures everything and shows an empty ledger.
 */
internal object Reparse {
    /**
     * The statuses that mean "could not read", and the whole safety argument.
     *
     * `MATCHED` is absent on purpose: that capture already wrote a `txn`, and
     * re-reading it is how spending gets counted twice. `DUPLICATE_OF` and
     * `UPDATE_OF` are resolved decisions and `REJECTED` is a deliberate one, so
     * none of them is a capture the app failed to understand.
     *
     * `GAVE_UP` belongs here rather than with the settled outcomes: a pattern
     * that ran out of wall clock is not evidence that no rule matches.
     */
    val REVISITABLE = listOf(ParseStatus.UNMATCHED, ParseStatus.NO_EXTRAS, ParseStatus.GAVE_UP)

    /**
     * Rows per run. A pack upgrade can face a table of thousands -- most
     * notifications on a phone are promotions, and every one of them is
     * `UNMATCHED` -- and stage two still has to drain what this queues, in the
     * same run, under its own cap. The worker retries until the sweep is done.
     */
    const val MAX_ROWS_PER_RUN = 500

    /**
     * The last pack version this device has swept, in DataStore rather than in
     * Room.
     *
     * Not a new column, because spec's schema v1 is frozen and this is not
     * capture data -- and not derived from the table either. "Is any row stale"
     * is `parse_status IN (...) AND pack_version < ?`, which no index serves:
     * `raw_capture(parse_status, id)` gets to the statuses and then reads every
     * row of them to test the version. Asking that on every notification, for
     * an answer that is no except just after an upgrade, is the cost this key
     * exists to avoid.
     */
    private val SWEPT_PACK_VERSION = intPreferencesKey("reparsed_pack_version")

    /**
     * Requeues stale captures if this pack version has not been swept yet.
     *
     * @return rows moved to `NEW`. [MAX_ROWS_PER_RUN] means there may be more,
     *   and the caller should ask again.
     */
    suspend fun sweep(
        context: Context,
        captures: RawCaptureDao,
        packVersion: Int,
        limit: Int = MAX_ROWS_PER_RUN,
    ): Int {
        val swept = context.captureStore.data.first()[SWEPT_PACK_VERSION] ?: 0
        if (swept >= packVersion) return 0

        val moved = captures.requeueStale(REVISITABLE, packVersion, limit)

        // Marked only when this run drained the backlog. A short batch is the
        // only evidence there is nothing left -- a full one says the opposite,
        // and recording the version there would strand whatever did not fit.
        if (moved < limit) {
            context.captureStore.edit { it[SWEPT_PACK_VERSION] = packVersion }
        }
        return moved
    }

    /**
     * Tests only: forget that any version has been swept.
     *
     * The mark is durable and this suite shares one app, so without it the
     * first test to record a version would silently disable the sweep for
     * every test after it -- each one passing because nothing ran.
     */
    @VisibleForTesting
    internal suspend fun forgetSweptVersion(context: Context) {
        context.captureStore.edit { it.remove(SWEPT_PACK_VERSION) }
    }
}
