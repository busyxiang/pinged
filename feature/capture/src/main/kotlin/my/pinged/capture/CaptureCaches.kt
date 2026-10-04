package my.pinged.capture

import android.content.Context
import androidx.datastore.preferences.core.edit

/**
 * Everything this module remembers outside the database, dropped in one call.
 *
 * Two callers, both of which invalidate the database underneath it: spec 11.3's
 * delete and spec 11.2's restore. `Reparse`'s KDoc names the second as where
 * "the rest of a restore's cache invalidation will live".
 */
object CaptureCaches {
    /**
     * The whole store, not named keys, because a named list is a list to
     * forget to add to -- and the failure that causes, the swept-pack mark
     * outliving the database it was tracking so a restored backlog never gets
     * re-read, is silent.
     *
     * Honest for both callers because each destroys the whole ledger first:
     * [SourceCounters]'s per-package seen counts and [CaptureHealth]'s
     * heartbeat describe the device, not the database, so wiping them here
     * costs something real -- they reset and rebuild only as notifications
     * arrive again. Spec 9.6's allow-list is unaffected: it travels in the
     * backup itself, so no choice the user made is lost.
     */
    suspend fun clear(context: Context) {
        context.captureStore.edit { it.clear() }
        CaptureHealth.forgetProcessMemo()
    }
}
