package my.pinged.capture

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Spec 10.1's third rebind path: ask again every time the app comes to the
 * foreground, while the grant is present.
 *
 * An [Application.ActivityLifecycleCallbacks] rather than a call in the single
 * Activity's `onStart`, so every Activity this app grows gets it without having
 * to remember -- the safer default for a call whose absence is invisible.
 *
 * A `started` count rather than a per-Activity hook, because "foreground" means
 * the app, not a screen: a rotation is stop-then-start and must not read as
 * leaving and re-entering.
 *
 * `lifecycle-process` and `ProcessLifecycleOwner` would do the same job with a
 * debounce, and are another dependency for a counter that fits in this file.
 */
class ForegroundRebinder internal constructor(
    private val onForeground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {

    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) onForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        /**
         * Called from `PingedApp.onCreate`. The application context is used
         * rather than the Activity's, because the rebind outlives the screen
         * that triggered it.
         */
        fun install(application: Application) {
            application.registerActivityLifecycleCallbacks(
                ForegroundRebinder { ListenerStatus.onAppForeground(application) },
            )
        }
    }
}
