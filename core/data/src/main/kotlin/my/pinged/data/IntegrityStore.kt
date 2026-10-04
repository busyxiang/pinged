package my.pinged.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Its own store, not a corner of `transfer` or `capture_health`: both features
 * write this one -- `:feature:capture`'s weekly check and `:feature:ledger`'s
 * **Check now** -- and neither of those stores is visible from the other
 * module.
 */
internal val Context.integrityStore by preferencesDataStore("integrity")

/**
 * What the last `PRAGMA integrity_check` said about this database, and when.
 *
 * Here rather than in either feature because the verdict is a fact about the
 * database this module owns, and both features can see it from here. One
 * reader, the settings screen; two writers, that screen and `ParseWorker`.
 *
 * [lastCheckAt] defaults to `0L` for never, which the screen reads as overdue
 * -- the safe direction. Nothing here travels in a backup: `Backup`'s KDoc is
 * explicit that nothing from DataStore is in the file.
 */
object IntegrityStore {
    private const val TAG = "PingedIntegrity"
    private val LAST_CHECK_AT = longPreferencesKey("last_check_at")

    /**
     * Set by a check that found problems, cleared by one that did not.
     *
     * Durable because the check does not run at open (design 2.1), so this is
     * the screen's only way to know between checks. Absent means "no bad news",
     * which is not "verified", and the screen says so.
     */
    private val DAMAGED = booleanPreferencesKey("damaged")

    /** One edit for both facts, so a verdict can never disagree with its time. */
    suspend fun record(context: Context, at: Long, ok: Boolean) {
        context.integrityStore.edit {
            it[LAST_CHECK_AT] = at
            if (ok) it.remove(DAMAGED) else it[DAMAGED] = true
        }
    }

    /**
     * Damage met by something other than a check: a read that threw code 11,
     * or a connection answering code 26 because an earlier read did.
     *
     * **The verdict only, not [LAST_CHECK_AT].** Nothing checked the file, so
     * the settings row keeps the time of the last check that did, and the
     * weekly one stays due when it was; a clean one clears this as it would
     * its own.
     *
     * **Best effort: a write DataStore refuses is logged, not thrown.** Every
     * caller records this beside something worth more than the verdict -- a
     * capture its retry may yet store, a stage-two pass, a failed export's
     * salvage wording, a settings read that must not crash the screen -- and
     * none of them has anything better to do with the `IOException`.
     */
    suspend fun recordDamage(context: Context) {
        try {
            context.integrityStore.edit { it[DAMAGED] = true }
        } catch (thrown: IOException) {
            Log.w(TAG, "Could not record the damage", thrown)
        }
    }

    suspend fun lastCheckAt(context: Context): Long =
        context.integrityStore.data.first()[LAST_CHECK_AT] ?: 0L

    suspend fun damaged(context: Context): Boolean =
        context.integrityStore.data.first()[DAMAGED] ?: false

    /**
     * [damaged] now and at every change, for `MainActivity`'s backup nudge:
     * a check or a read that meets damage while the home screen is up
     * resumes nothing, so a sample taken on resume would go on offering an
     * export the settings screen has withdrawn.
     */
    fun damagedUpdates(context: Context): Flow<Boolean> =
        context.integrityStore.data.map { it[DAMAGED] ?: false }.distinctUntilChanged()

    /**
     * Tests, and [Wipe.everything]: the record describes one database file, so
     * a delete that left it behind would have the screen reporting a check --
     * or damage -- in a ledger that no longer exists.
     */
    suspend fun forget(context: Context) {
        context.integrityStore.edit { it.clear() }
    }
}
