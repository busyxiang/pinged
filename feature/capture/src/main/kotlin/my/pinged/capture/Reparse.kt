package my.pinged.capture

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.entity.ParseStatus

/**
 * Spec 5.5's first two modes. Gives captures an older pack could not read back
 * to stage two, once per pack version.
 *
 * Without it a rule only ever applies to what arrives after it ships. The app
 * captured payments it could not parse for two releases and held them in full,
 * readable and never re-read.
 *
 * **Mode three is not here.** Spec 5.5 also re-runs `MATCHED` captures and
 * offers the differences for review, which is how a rule that recorded
 * RM1,234.00 as RM1.23 gets repaired. This sweep only ever creates
 * transactions, never revisits one.
 */
internal object Reparse {
    /**
     * Rows per run. The statement runs inside a Room transaction, and Room 2.8
     * hands SQLCipher a single connection, so this is the window during which
     * the listener's own inserts are serialised behind it -- the reason the
     * batch is bounded at all. Stage two then drains what this queues in the
     * same run, under its own larger cap.
     */
    const val MAX_REQUEUE_PER_RUN = 500

    /**
     * The last pack version swept, in DataStore rather than Room.
     *
     * Not derived from the table. Measured against the schema's own indices at
     * 8,000 rows: the predicate takes `SCAN raw_capture` -- a full scan, no
     * index, `UNMATCHED` being too much of the table to be selective -- 1.2 ms
     * on a desktop, and every 4 KB page is AES-decrypted on a device. Asking
     * that per notification, for an answer that is no except just after an
     * upgrade, is what this avoids.
     *
     * **Known gap: it does not survive a device transfer.** Spec 11.1's export
     * carries `pack_version` on every capture but nothing from DataStore, so a
     * restored backlog arrives stamped at old versions while a fresh install's
     * mark reaches the current version on its own first sweep, and the imported
     * captures are never re-read. The fix belongs in the import path, which is
     * where the rest of a restore's cache invalidation will live.
     */
    private val SWEPT_PACK_VERSION = intPreferencesKey("reparsed_pack_version")

    /**
     * What one sweep did. [more] means the batch filled, so there is backlog
     * left and the caller should come back -- stated here rather than left for
     * the caller to re-derive from [moved] and the limit it passed.
     */
    data class Swept(val moved: Int, val more: Boolean)

    /** Requeues stale captures if this pack version has not been swept yet. */
    suspend fun sweep(
        context: Context,
        captures: RawCaptureDao,
        packVersion: Int,
        limit: Int = MAX_REQUEUE_PER_RUN,
    ): Swept {
        val swept = context.captureStore.data.first()[SWEPT_PACK_VERSION] ?: 0
        if (swept >= packVersion) return Swept(moved = 0, more = false)

        val moved = captures.requeueStale(
            from = ParseStatus.REVISITABLE,
            packVersion = packVersion,
            limit = limit,
            to = ParseStatus.NEW,
        )

        // A short batch is the only evidence there is nothing left. Recording
        // the version after a full one would strand whatever did not fit.
        val more = moved >= limit
        if (!more) {
            context.captureStore.edit { it[SWEPT_PACK_VERSION] = packVersion }
        }
        return Swept(moved, more)
    }

    /**
     * Tests only: forget that any version has been swept. The mark is durable
     * and the suite shares one app, so without this the first test to record a
     * version disables the sweep for every test after it.
     */
    @VisibleForTesting
    internal suspend fun forgetSweptVersion(context: Context) {
        context.captureStore.edit { it.remove(SWEPT_PACK_VERSION) }
    }
}
