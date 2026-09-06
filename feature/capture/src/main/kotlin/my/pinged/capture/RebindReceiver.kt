package my.pinged.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The first of spec 10.1's three rebind paths: reinstall and reboot.
 *
 * Replacing the APK unbinds the listener, and where nothing rebinds it capture
 * dies on every build installed -- a symptom indistinguishable from a parser
 * that stopped matching.
 *
 * **Spec 10.1 says nothing rebinds until a reboot or a permission toggle, and
 * on current AOSP that is no longer true.** Measured on this project's emulator
 * (API 37, 2026-09-05): `NotificationManagerService` rebound the listener
 * itself 143ms before this receiver ran, and `requestRebind` was answered with
 * "is already bound". Kept anyway -- the Xiaomi, Oppo, Vivo and Realme ROMs
 * this section exists for do not behave like AOSP, the original claim holds
 * down to `minSdk` 27, and a redundant rebind costs one no-op binder call.
 *
 * `exported="false"` is correct even though the sender is the system: both
 * actions are protected broadcasts only the system may send, and the system is
 * exempt from the exported check.
 *
 * Not `directBootAware`. `BOOT_COMPLETED` is delivered after the first unlock,
 * which is what this wants: before then neither the Keystore key nor
 * credential-encrypted storage exists (spec 10.5), so a pre-unlock rebind would
 * bind a listener whose every insert would fail to open the database.
 *
 * It cannot cover spec 10.5's force-stop state, and nothing can: a stopped app
 * receives no manifest broadcasts at all. That is why
 * [CaptureReport.needsUserAction] exists.
 */
class RebindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED,
            -> {
                Log.i(TAG, "Rebinding after ${intent.action}")
                ListenerStatus.requestRebind(context)
                // A reinstall or a reboot can leave rows at `NEW` that no later
                // notification would schedule a run for -- stage two's queue is
                // the table, not a process-local queue, so work survives the
                // restart but nothing asks for it. This asks.
                runCatching { ParseWorker.enqueue(context) }
                    .onFailure { Log.w(TAG, "Could not schedule stage two", it) }
            }

            // Anything else was not asked for. A receiver that acted on every
            // intent it was handed would act on an explicit one from any
            // component in this app, which is a wider contract than the
            // manifest advertises.
            else -> Log.w(TAG, "Ignoring unexpected action ${intent.action}")
        }
    }

    private companion object {
        private const val TAG = "PingedRebind"
    }
}
