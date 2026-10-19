package my.pinged.capture

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log

/**
 * Spec 10.2's daily check, posted on the channel spec 9.6's prompt already owns
 * ([SourcePrompt.CHANNEL_ID]): one channel, one name, one answer to whether the
 * app may post, so the two cannot disagree about any of them.
 */
object CaptureAlerts {
    /** One slot: a newer alert replaces the older one rather than stacking. */
    internal const val HEALTH_ID = 10_2

    private const val TAG = "PingedAlerts"

    /** [SourcePrompt.mayPost]: the permission, the app switch, and the channel. */
    fun canPost(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return SourcePrompt.mayPost(context, manager)
    }

    /**
     * Creates the channel ahead of any post, for a caller about to open this
     * app's notification settings: until something has posted there is no
     * channel, and that page then has nothing on it to switch (#217).
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        SourcePrompt.ensureChannel(context, manager)
    }

    internal fun post(context: Context, alert: HealthAlert.Alert) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        SourcePrompt.ensureChannel(context, manager)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, HEALTH_ID, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = Notification.Builder(context, SourcePrompt.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(Notification.BigTextStyle().bigText(alert.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // A revoked permission between `canPost` and here throws on some API
        // levels; a worker that dies on it would retry an alert nobody can see.
        runCatching { manager.notify(HEALTH_ID, notification) }
            .onFailure { Log.w(TAG, "Could not post a capture alert", it) }
    }

    internal fun withdraw(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(HEALTH_ID)
    }
}
