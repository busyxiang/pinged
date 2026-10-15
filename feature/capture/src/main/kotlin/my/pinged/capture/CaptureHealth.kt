package my.pinged.capture

import my.pinged.data.CaptureDays
import my.pinged.data.Databases
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One DataStore for the whole module, declared once.
 *
 * `preferencesDataStore` is a property delegate that owns the file it names,
 * and a second delegate over the same file name in the same process throws
 * ("There are multiple DataStores active for the same file"). So
 * [SourceCounters] and [Reparse] share this one rather than declaring their own.
 */
internal val Context.captureStore by preferencesDataStore("capture_health")

/**
 * DataStore, not Room, and throttled.
 *
 * A phone posts 100-300 notifications a day and Room's invalidation tracker is
 * table-granular, so a heartbeat row would re-emit every `Flow` observing that
 * table on every notification on the device (spec 10.2).
 */
internal object CaptureHealth {
    private val LAST_SEEN = longPreferencesKey("last_any_notification_at")
    /**
     * Set when the database cannot be opened because its key is gone.
     *
     * In DataStore rather than Room for the obvious reason: it is the state
     * where Room is exactly what is unavailable. This is the only durable
     * place left to write it.
     */
    private val STORAGE_UNAVAILABLE = androidx.datastore.preferences.core.booleanPreferencesKey(
        "storage_unavailable",
    )
    internal const val THROTTLE_MILLIS = 5 * 60 * 1000L

    @Volatile private var lastWrittenAt = 0L


    /**
     * Three states, not two, and the third is why.
     *
     * This is a write-throttle over a *durable* flag, so `false` cannot mean both
     * "known clear" and "not looked yet". It used to: a fresh process started at
     * `false`, so [clearStorageUnavailable] short-circuited before it could ever
     * remove a key some previous process had written. A transient Keystore fault
     * plus a process death -- the premise of this whole app -- left "CANNOT OPEN
     * YOUR DATA" on screen for the life of the install, first in `MainActivity`'s
     * `when` and therefore masking the real banners, with no way to dismiss it.
     *
     * `null` means this process has not written either way, so the first call of
     * each kind always reaches DataStore. One edit per process, which is what the
     * throttle was for.
     */
    @Volatile private var storageUnavailable: Boolean? = null

    /** See [writeStorageUnavailable]; [wipe] takes it too. */
    private val flagWrites = Mutex()

    /**
     * Liveness for any notification from any app, so a dead listener is
     * detectable regardless of whether the user spent money (spec 10.2).
     *
     * The throttle window is held in memory, not read back from DataStore:
     * reading first would cost the read this is trying to avoid, and the only
     * consequence of losing the window on process death is one extra write.
     */
    suspend fun recordSeen(context: Context, now: Long) {
        if (now - lastWrittenAt < THROTTLE_MILLIS) return
        lastWrittenAt = now
        context.captureStore.edit { it[LAST_SEEN] = now }
    }

    suspend fun lastSeenAt(context: Context): Long =
        context.captureStore.data.first()[LAST_SEEN] ?: 0L

    /**
     * Forget every in-process memo, as a fresh process would.
     *
     * Two callers. [wipe] needs it because clearing the durable store while
     * this process still holds `storageUnavailable = false` leaves the two
     * disagreeing until the process dies. Tests need it because the
     * throttles above are process-scoped by design, so the behaviour that
     * matters most -- what a *new* process does when it meets a flag an older
     * one wrote -- is otherwise unreachable from a suite that runs in one
     * process.
     */
    internal fun forgetProcessMemo() {
        lastWrittenAt = 0L
        storageUnavailable = null
        // The day memos moved to `:core:data`, beside the table they cache.
        CaptureDays.forgetProcessMemo()
    }


    /**
     * Record that the database could not be opened because its key is missing.
     *
     * Nothing caught [my.pinged.data.DatabaseKeyUnavailableException] anywhere. The
     * listener's CoroutineExceptionHandler swallowed it, so capture logged one line
     * per notification and dropped everything -- while [recordSeen], which runs
     * *before* the first database call, kept the heartbeat fresh. `looksDead`
     * stayed false and the app reported healthy capture, indefinitely, while
     * storing nothing.
     */
    suspend fun recordStorageUnavailable(context: Context) = writeStorageUnavailable(context, true)

    /**
     * Cleared once the database opens again, so the banner does not stick.
     *
     * Reached on every successful open, so the throttle matters -- but it
     * throttles on [storageUnavailable] being *known* clear, never on it being
     * unset. See that field for what the difference cost.
     */
    suspend fun clearStorageUnavailable(context: Context) = writeStorageUnavailable(context, false)

    /**
     * The flag's one writer, under [flagWrites], with the memo set only once
     * the store holds [value].
     *
     * `CaptureStorage.guarded` raises and clears from IO threads at once
     * whenever one caller is refused while another opens; a run of
     * `StorageBannerFollowsTheLedgerTest` crosses them about once. Without
     * the lock the last memo write and the last edit can be different
     * callers' -- `StorageFlagCrossingTest` measured 240-265 and 18-22 of
     * 1000 crossings -- and every later call of the same kind then
     * short-circuits on a memo the store disagrees with, for the life of the
     * process: the flag down with the key gone, or up over a ledger that
     * opens (issue #179).
     *
     * The memo after the edit, not before, so a caller whose write
     * short-circuits returns only once the write it relied on is in the
     * store: `MainActivity`'s sample reads the store straight after its own
     * probe, and must not read a raise still in flight as none.
     */
    private suspend fun writeStorageUnavailable(context: Context, value: Boolean) {
        if (storageUnavailable == value) return
        flagWrites.withLock {
            if (storageUnavailable == value) return
            context.captureStore.edit { if (value) it[STORAGE_UNAVAILABLE] = true else it.remove(STORAGE_UNAVAILABLE) }
            storageUnavailable = value
        }
    }

    /**
     * [CaptureCaches.clear]: the whole store emptied and every memo
     * forgotten, under [flagWrites]. Outside it, a raise whose edit lands
     * just before the wipe sets its memo just after the forget, and the flag
     * reads down with the memo saying up. No test reaches that window: 600
     * crossings of a raise and a wipe without the lock all came out agreeing.
     */
    internal suspend fun wipe(context: Context) = flagWrites.withLock {
        context.captureStore.edit { it.clear() }
        forgetProcessMemo()
    }

    suspend fun storageUnavailable(context: Context): Boolean =
        context.captureStore.data.first()[STORAGE_UNAVAILABLE] ?: false

    /** See [ListenerStatus.storageUnavailableChanges]. */
    fun storageUnavailableChanges(context: Context): Flow<Boolean> =
        context.captureStore.data
            .map { it[STORAGE_UNAVAILABLE] ?: false }
            .distinctUntilChanged()
            .drop(1)
}
