package my.pinged.capture

import android.content.Context
import android.util.Log
import my.pinged.data.DatabaseUnavailableException
import my.pinged.data.Databases

/**
 * The one place that says what happens when the database cannot be opened.
 *
 * Spec 11.1's device-transfer state -- an intact database whose
 * Keystore-wrapped key is gone -- reaches every caller of `Databases`, because
 * `DatabaseFactory.build` is eager and throws from the first access. This
 * catches [DatabaseUnavailableException], the whole family: a corrupt file, a
 * full disk and an IO error all mean there is nowhere to put a capture, and
 * catching only the key case left a corrupt database with no banner at all.
 *
 * Nothing caught it before, and each of the three entry points failed
 * differently: the listener logged once per notification and dropped
 * everything, the worker retried ~131 times a day forever, and the sources
 * screen killed the process on every launch. So the policy is stated once here
 * -- record it, and hand the caller something it can render.
 */
object CaptureStorage {
    private const val TAG = "CaptureStorage"

    /**
     * Run [block], or [unavailable] if the database cannot be opened. A success
     * clears the flag, so a restored key makes the banner go away.
     *
     * **The open happens here, not in [block], and that is the correctness
     * property.** Clearing the flag on a block that returned without throwing is
     * only honest if the block actually tried the database -- and blocks that skip
     * it are normal: `CaptureDays`' two writers both return early once the day is
     * recorded, which is every notification but the first and every bind but the
     * first, each day.
     *
     * Found once before and fixed in one place -- `CaptureIngest` gained an
     * explicit `Databases.shared(context)` inside its guard -- while the other
     * three call sites did not, so `onListenerConnected` could clear the banner on
     * its second bind of the day.
     *
     * Opening is memoized by [Databases], so after the first call this is a
     * volatile read.
     */
    suspend fun <T> guarded(
        context: Context,
        what: String,
        unavailable: () -> T,
        block: suspend () -> T,
    ): T = try {
        Databases.shared(context)
        val result = block()
        CaptureHealth.clearStorageUnavailable(context)
        result
    } catch (unavailable: DatabaseUnavailableException) {
        Log.e(TAG, "$what: " + unavailable.message, unavailable)
        CaptureHealth.recordStorageUnavailable(context)
        unavailable()
    }

}
