package my.pinged.ledger.transfer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import my.pinged.capture.CaptureCaches

/**
 * Its own file, not `capture_health`: that one is `internal` to
 * `:feature:capture` and is cleared wholesale when the database goes.
 */
internal val Context.transferStore by preferencesDataStore("transfer")

/**
 * When the user last took a copy of their data.
 *
 * A ledger concern, and only that. The integrity verdict is
 * `my.pinged.data.IntegrityStore` rather than a second key here, because
 * `ParseWorker` writes it too and cannot see this module.
 *
 * Defaults to `0L` for never, which the nudge (spec section 8) reads as
 * "overdue" -- the safe direction. A restore leaves it at never, because
 * `Backup`'s KDoc is explicit that nothing from DataStore travels in the file.
 */
object TransferStore {
    private val LAST_EXPORT_AT = longPreferencesKey("last_export_at")

    suspend fun recordExport(context: Context, at: Long) {
        context.transferStore.edit { it[LAST_EXPORT_AT] = at }
    }

    suspend fun lastExportAt(context: Context): Long =
        context.transferStore.data.first()[LAST_EXPORT_AT] ?: 0L

    private val NUDGE_DISMISSED_AT = longPreferencesKey("nudge_dismissed_at")

    /**
     * When the user last dismissed the backup nudge, which silences it for a
     * while without claiming anything is saved.
     *
     * Here rather than its own store because [forget] has to clear it with
     * the export: a delete or a restore leaves a ledger the dismissal was not
     * about, and the nudge should judge that one afresh.
     */
    suspend fun recordNudgeDismissed(context: Context, at: Long) {
        context.transferStore.edit { it[NUDGE_DISMISSED_AT] = at }
    }

    /** `0L` for never. */
    suspend fun nudgeDismissedAt(context: Context): Long =
        context.transferStore.data.first()[NUDGE_DISMISSED_AT] ?: 0L

    /**
     * Every change [recordExport] or [forget] makes to [lastExportAt] in this
     * process, and nothing on subscription.
     *
     * For a reader that is on screen while the export happens. `MainActivity`'s
     * backup nudge is the case -- its own button leads to the export that
     * settles it, and that export takes place inside one foreground, so a
     * reader that only sampled at `onResume` would go on saying nothing is
     * saved for the rest of the session.
     *
     * DataStore emits on a write rather than on a poll, and only for a write
     * that happened: an export that failed never reaches [recordExport], so
     * nothing here emits and the nudge stays up.
     *
     * **The stored value is dropped here rather than by the reader**, so that
     * [forgotten]'s tick can never be the emission a reader's own `drop(1)`
     * swallows. What is left is changes, which is what the name says.
     */
    fun lastExportAtChanges(context: Context): Flow<Long> = merge(
        context.transferStore.data
            .map { it[LAST_EXPORT_AT] ?: 0L }
            .distinctUntilChanged()
            .drop(1),
        forgotten,
    )

    /**
     * [forget]'s emission, because DataStore does not make one for it.
     *
     * **Measured on emulator-5554, DataStore 1.2.1.** A collector on
     * `transferStore.data` sees one emission, `{}`; a [forget] over that
     * already-empty store adds nothing; a [recordExport] then emits
     * `{last_export_at=...}` and the [forget] after it emits `{}` again.
     * `SingleProcessDataStore.transformAndWrite` compares the transformed
     * `Preferences` with the current one and neither writes nor emits when they
     * are equal, so a clear of nothing is invisible -- and `distinctUntilChanged`
     * above would collapse it in any case, both values being `0L`.
     *
     * A never-exported install is where that bites. `Restore.replaceEverything`
     * forgets an export that was never recorded, in the same call that takes the
     * ledger from nothing to lose to a file's worth of rows, and nothing resumes
     * `MainActivity` between the picker returning and the import finishing. So
     * without this the home screen shows no backup nudge over a restored ledger
     * that has no copy anywhere, for the rest of that foreground.
     *
     * `tryEmit`, so a slow collector cannot make [forget] suspend inside a
     * restore; a tick dropped for a full buffer costs nothing, because two
     * forgets leave the same state to read.
     */
    private val forgotten = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    /**
     * Tests, and both wipes through [forgetWipedLedger]: spec 11.3's delete
     * leaves an app that has never exported anything, and spec 11.2's restore
     * a ledger with no copy on this phone's record, so a timestamp left
     * behind would silence the nudge over data nothing has backed up.
     */
    suspend fun forget(context: Context) {
        context.transferStore.edit { it.clear() }
        forgotten.tryEmit(0L)
    }
}

/**
 * What the process remembers about a ledger `Wipe.everything` has just
 * removed: capture's caches, and when it was last exported. Run straight
 * after the wipe by spec 11.2's restore and 11.3's delete, each of which
 * handles a failure here in its own way.
 */
internal suspend fun forgetWipedLedger(context: Context) {
    CaptureCaches.clear(context)
    TransferStore.forget(context)
}
