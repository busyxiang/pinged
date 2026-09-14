package my.pinged.capture

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.pinged.data.DatabaseUnavailableException
import my.pinged.data.Databases

/**
 * The one place that says what happens when the database cannot be opened.
 *
 * Spec 11.1's device-transfer state -- an intact database whose
 * Keystore-wrapped key is gone -- reaches every caller of `Databases`, because
 * `DatabaseFactory.build` is eager and throws from the first access. This
 * catches [DatabaseUnavailableException], the whole family: a corrupt file, a
 * full disk and an IO error all mean there is nowhere to put a capture. The
 * policy is stated once here -- record it, and hand the caller something it
 * can render.
 */
object CaptureStorage {
    private const val TAG = "CaptureStorage"

    /**
     * Run [block], or [unavailable] if the database cannot be opened. A success
     * clears the flag, so a restored key makes the banner go away.
     *
     * **The open happens here, not in [block].** Clearing the flag is only
     * honest if the database was actually tried, and blocks that skip it are
     * normal: `CaptureDays`' two writers both return early once the day is
     * recorded, which is every notification but the first each day.
     *
     * **[Dispatchers.IO] covers the whole body, [block] included, and that is
     * the point rather than a detail of the open.** The open is blocking --
     * `DatabaseFactory.build` is eager and runs a `SELECT COUNT(*)` -- and the
     * callers include `MainActivity`, whose `lifecycleScope` is the main
     * thread and carries no `CoroutineExceptionHandler`. But every block that
     * goes on to query is blocking too, and Room's `assertNotMainThread`
     * throws a plain `IllegalStateException`: outside
     * [DatabaseUnavailableException], so the catch below cannot see it, and on
     * `viewModelScope`'s bare `SupervisorJob` that kills the process.
     * `GuardedDispatchTest` is the falsifying test that the hop belongs here;
     * `MainThreadRefreshTest` and `LaunchTest` pin the callers.
     *
     * Memoized by [Databases], so after the first call this is a volatile read.
     */
    suspend fun <T> guarded(
        context: Context,
        what: String,
        unavailable: () -> T,
        block: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        try {
            Databases.shared(context)
            val result = block()
            CaptureHealth.clearStorageUnavailable(context)
            result
        } catch (thrown: DatabaseUnavailableException) {
            Log.e(TAG, "$what: " + thrown.message, thrown)
            CaptureHealth.recordStorageUnavailable(context)
            unavailable()
        }
    }
}
