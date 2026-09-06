package my.pinged

import android.app.Application
import androidx.work.Configuration
import my.pinged.capture.ForegroundRebinder

/**
 * Application entry point.
 *
 * Two things are wired here: WorkManager's configuration, because the
 * manifest removes `androidx.work.WorkManagerInitializer`, and spec 10.1's
 * third rebind path. Nothing else. `Databases` and `Graph` are reached
 * statically by the listener, which the system constructs and which therefore
 * has no hook an injector could be handed to.
 */
class PingedApp : Application(), Configuration.Provider {
    /**
     * Supplied here because the manifest removes
     * `androidx.work.WorkManagerInitializer`; see the note there for why.
     *
     * With the initializer gone, WorkManager initializes on the first
     * `getInstance` instead of during `onCreate`, and it reads this. Removing
     * the initializer *without* this would leave `getInstance` throwing.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // Every app foreground asks the system to bind the listener again. The
        // receiver covers reinstall and reboot and onListenerDisconnected
        // covers a surviving process; this covers the case none of them can,
        // which is a requestRebind that was made and simply did not take. There
        // is no callback for that, so the remedy is to ask again at the one
        // moment the user is present to notice if it still has not worked.
        ForegroundRebinder.install(this)
    }
}
