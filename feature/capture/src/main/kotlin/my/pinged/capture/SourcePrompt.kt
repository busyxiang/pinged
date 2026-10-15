package my.pinged.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * Spec 9.6's just-in-time prompt: a suggested bank or e-wallet posted while the
 * user has it switched off, so Pinged asks, once, whether to capture it.
 *
 * Called from stage one past the allow-list gate's "no", and keyed on the package
 * identifier alone. Nothing from the notification reaches here, so the prompt
 * cannot repeat a word of what the disabled app said.
 *
 * **"Once" is a DataStore mark, not a `capture_source` column.** A disabled
 * package has no row to hang it on -- discovery writes only a count -- and adding
 * one would mean a whole-row write on the table where that destroyed consent. The
 * mark is one boolean per pack package, beside the seen counts that already name
 * the same packages, and like them it records no time.
 *
 * Delete everything and a restore clear this store with the counts
 * (`CaptureCaches.clear`), so each suggested package may prompt once more after
 * either. That is consistent rather than a lapse: both replace the allow-list the
 * prompt was about.
 */
object SourcePrompt {

    /**
     * The app's one notification channel. Spec 10.2's daily capture check posts
     * here too (#131), so it is named for capture rather than for this prompt.
     */
    const val CHANNEL_ID = "capture"

    /**
     * The extra a tap carries and its one value, read by `MainActivity` to open
     * Sources. The same extra as `AddPaymentShortcut`'s, with its own value: one
     * key says where a launch should open, so two routes cannot both be asked for.
     */
    const val EXTRA_OPEN = "my.pinged.extra.OPEN"
    const val OPEN_SOURCES = "capture_sources"

    private const val PREFIX = "prompted:"

    private fun key(pkg: String) = booleanPreferencesKey("$PREFIX$pkg")

    /**
     * Prompts for [pkg] if it is in the pack and has not been prompted for before.
     *
     * The mark is spent only once the app is allowed to post. A user who has not
     * granted notifications yet keeps the one prompt for the first notification
     * after they do, rather than losing it to a post the system dropped.
     *
     * Mark first, then post: a process killed between the two loses this one
     * prompt, where the other order would repeat it. The mark is set inside one
     * [edit], so two notifications cannot both pass it.
     *
     * @return true if a prompt was posted.
     */
    suspend fun offer(context: Context, pkg: String): Boolean {
        // Every notification the gate turns away comes here, so the first in each
        // listener process loads the pack: 39ms cold and 12ms warm on the Pixel 10a
        // emulator (API 37), once per process, on the capture thread. The pack
        // rather than a second list, for the reason [Graph.parsePack] gives.
        val label = Graph.parsePack().packages.firstOrNull { it.pkg == pkg }?.label ?: return false
        // A read before anything costs a binder call: a bank already asked about
        // keeps posting, and each of its notifications comes through here.
        if (context.captureStore.data.first()[key(pkg)] == true) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        // Before the channel exists `mayPost` reads it as not silenced, so the
        // binder call that creates it is paid only by a prompt that will post.
        if (!mayPost(context, manager)) return false
        ensureChannel(context, manager)

        var first = false
        context.captureStore.edit { prefs ->
            first = prefs[key(pkg)] != true
            prefs[key(pkg)] = true
        }
        if (!first) return false

        manager.notify(pkg, NOTIFICATION_ID, prompt(context, pkg, label))
        return true
    }

    /** Whether [intent] is a tap on the prompt. */
    fun opensSources(intent: Intent?): Boolean = intent?.getStringExtra(EXTRA_OPEN) == OPEN_SOURCES

    /** Tests only: every package becomes promptable again. */
    internal suspend fun forget(context: Context) {
        context.captureStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }.forEach { prefs.remove(it) }
        }
    }

    /**
     * From API 33 the permission is the switch; before it there is none and the
     * user's only off switch is the app's notification setting. A channel the user
     * has silenced to `NONE` is also an answer, and posting into it would spend the
     * mark on nothing.
     */
    internal fun mayPost(context: Context, manager: NotificationManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (!manager.areNotificationsEnabled()) return false
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Idempotent: creating an existing channel changes only its name. */
    internal fun ensureChannel(context: Context, manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.capture_channel_description) },
        )
    }

    private fun prompt(context: Context, pkg: String, label: String): Notification {
        // The launcher's own intent, which names MainActivity explicitly: this
        // module cannot, and an implicit MAIN/LAUNCHER intent does not resolve for
        // startActivity, which also demands CATEGORY_DEFAULT. CLEAR_TASK because
        // MainActivity routes an intent only in onCreate. Null only in a library's
        // test APK, which has no launcher; the prompt is then posted untappable.
        val tap = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            PendingIntent.getActivity(
                context,
                pkg.hashCode(),
                launch
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(EXTRA_OPEN, OPEN_SOURCES),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val text = context.getString(R.string.source_prompt_text, label)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.source_prompt_title, label))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
    }

    /**
     * One per package, told apart by the tag; the id only has to be this
     * prompt's own. Written `9_6` for spec 9.6, so it reads as where it is from.
     */
    private const val NOTIFICATION_ID = 9_6
}
