package my.pinged.capture

import android.content.ComponentName
import android.content.Context

/**
 * The frozen component name of the listener.
 *
 * The notification-access grant is stored by the system against this flattened
 * `ComponentName`, so renaming or moving [PingedNotificationListener] voids the
 * grant silently -- no callback, no visible event, capture simply stops.
 *
 * Named by string rather than `::class.java` deliberately: a rename a
 * refactoring tool would happily propagate has to break the build here instead.
 * `PingedComponentsTest` checks it against the real class.
 */
object PingedComponents {
    const val LISTENER_CLASS_NAME = "my.pinged.capture.PingedNotificationListener"

    fun listener(context: Context): ComponentName =
        ComponentName(context.packageName, LISTENER_CLASS_NAME)
}
