package my.pinged

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.first

/** Its own store: nothing else in `:app` keeps one, and no feature reads this. */
private val Context.firstRunStore by preferencesDataStore("first_run")

/**
 * Whether first run is over (#156): set the first time the notification-access
 * grant is seen, and never cleared by the app.
 *
 * A flag rather than "no grant and nothing ever heard", which would need no
 * store: that condition holds on every test device and a CI emulator, and only
 * a stored flag can be set by `PingedTestRunner` before the suite starts.
 *
 * An install that holds the grant records it at its first [onboards] and
 * never sees onboarding. A grant revoked after that is the banner's to report,
 * not first run's.
 */
internal object FirstRun {
    private val DONE = booleanPreferencesKey("done")

    suspend fun done(context: Context): Boolean = context.firstRunStore.data.first()[DONE] ?: false

    suspend fun recordDone(context: Context) {
        context.firstRunStore.edit { it[DONE] = true }
    }

    /** Tests: first run again, as on a fresh install. */
    suspend fun forget(context: Context) {
        context.firstRunStore.edit { it.clear() }
    }
}

/**
 * Whether the window shows onboarding instead of the app: first run not over
 * and no grant, on an install that has never been updated. A grant ends first
 * run, and is recorded here so it stays over once the grant goes.
 *
 * **An updated install is past first run whatever the flag says.** One
 * updated without the grant has a ledger behind the banner, and onboarding has
 * no way past it -- on API 33+ a sideloaded build's toggle can be greyed out
 * (spec 10.5), which would hide that ledger, its export and its restore until
 * the user found Allow restricted settings alone.
 *
 * **False when the store will not read or write**, the direction that shows the
 * app: its "CAPTURE OFF" banner says what onboarding would, while a store that
 * failed the other way would hold the user on a screen with nothing else on it.
 * Never throws: the only caller is `AppStatus`'s sample, on `viewModelScope`
 * with no `CoroutineExceptionHandler`.
 *
 * [done], [recordDone] and [updated] are parameters for that guard's test, as
 * `ListenerStatus.report`'s are.
 */
internal suspend fun onboards(
    context: Context,
    granted: Boolean,
    done: suspend () -> Boolean = { FirstRun.done(context) },
    recordDone: suspend () -> Unit = { FirstRun.recordDone(context) },
    updated: () -> Boolean = { installWasUpdated(context) },
): Boolean = try {
    when {
        done() -> false
        updated() -> { recordDone(); false }
        granted -> { recordDone(); false }
        else -> true
    }
} catch (thrown: IOException) {
    Log.w(TAG, "Could not read or record first run", thrown)
    false
}

/**
 * Whether this install has been updated since it was installed: equal times
 * are a fresh install, and an `adb install` after an uninstall is one.
 * False if the package manager will not say, which leaves the flag to decide.
 *
 * A `var` for `OnboardingTest`: a connected
 * run installs over the last build, so on a dev emulator every install is an
 * update and first run could never be reached.
 */
@VisibleForTesting @Volatile internal var installWasUpdated: (Context) -> Boolean = { context ->
    wasUpdated(context)
}

private fun wasUpdated(context: Context): Boolean = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.lastUpdateTime != info.firstInstallTime
}.getOrDefault(false)
