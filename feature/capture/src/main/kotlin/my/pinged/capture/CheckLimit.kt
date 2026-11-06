package my.pinged.capture

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.first

/**
 * Its own file, not [captureStore]: `CaptureCaches.clear` empties that one on a
 * restore, and a restore keeps this phone's check limit (#362).
 */
internal val Context.checkLimitStore by preferencesDataStore("check_limit")

/**
 * Spec 7.1's check limit: the amount above which a captured payment waits for
 * review instead of counting (CONTEXT.md). One for every capture source.
 *
 * **It decides only what happens to a payment when it arrives.** Saving one
 * writes this store and nothing else: no row awaiting review is released and
 * no counted row is held, whichever way it moves. Stage two's gate,
 * Corrections' re-read and the review inbox each read it when they run.
 *
 * **Device-local** (#362): the JSON export does not carry it and a restore
 * leaves it, but spec 11.3's delete everything resets it ([reset]).
 *
 * **Fixed steps, and no "Never"**: the limit is the only guard between a pack
 * misreading RM32.00 as RM3,200.00 and the totals.
 */
object CheckLimit {
    private const val TAG = "PingedCheckLimit"

    /** RM100 to RM5,000, in sen: the only values [set] takes. */
    val STEPS_SEN: List<Long> = listOf(10_000L, 20_000L, 50_000L, 100_000L, 200_000L, 500_000L)

    /** RM500, until the user picks another step, and again after a delete. */
    const val DEFAULT_SEN: Long = REVIEW_THRESHOLD_SEN

    internal val KEY = longPreferencesKey("check_limit_sen")

    /**
     * The limit now, in sen: [DEFAULT_SEN] for none saved, for a value that is
     * not a step, and for a store that will not read.
     *
     * **A read that fails answers the default, logged, rather than throwing**:
     * stage two retrying on a truncated file would stop every capture, and
     * RM500 is what a fresh install gates by.
     */
    suspend fun sen(context: Context): Long {
        val stored = try {
            context.checkLimitStore.data.first()[KEY]
        } catch (unread: IOException) {
            Log.w(TAG, "The check limit could not be read; RM500 stands in", unread)
            null
        }
        return stored?.takeIf { it in STEPS_SEN } ?: DEFAULT_SEN
    }

    /** Saves [sen], which must be one of [STEPS_SEN]. Writes no ledger row. */
    suspend fun set(context: Context, sen: Long) {
        require(sen in STEPS_SEN) { "$sen sen is not a check limit step" }
        context.checkLimitStore.edit { it[KEY] = sen }
    }

    /** Spec 11.3: back to [DEFAULT_SEN]. A restore does not call this. */
    suspend fun reset(context: Context) {
        context.checkLimitStore.edit { it.remove(KEY) }
    }
}
