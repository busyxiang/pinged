package my.pinged.capture

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification

/**
 * Shared setup for this module's JVM/Robolectric tests.
 *
 * One fixtures file per test source set is the repository convention, and this
 * source set was the only one with more than one test class and no such file,
 * so `NotificationFieldsTest` had re-implemented `CaptureFixtures`'s
 * `StatusBarNotification` builder.
 *
 * It cannot use `CaptureFixtures` itself -- that lives in `androidTest` and
 * this is a JVM source set. What was avoidable is a *third* copy.
 */
internal object UnitFixtures {
    /**
     * A `StatusBarNotification`, built through the 10-argument constructor.
     *
     * Positional and awkward on purpose -- there is no builder for this type.
     * That is exactly why it belongs in one place: two copies of a positional
     * ten-argument call will disagree the first time one is updated.
     */
    fun sbn(
        notification: Notification,
        pkg: String = "com.example",
        id: Int = 1,
        tag: String? = "tag",
        postTime: Long = 1_000L,
    ): StatusBarNotification = StatusBarNotification(
        pkg,
        pkg,
        id,
        tag,
        /* uid = */ 0,
        /* initialPid = */ 0,
        /* score = */ 0,
        notification,
        Process.myUserHandle(),
        postTime,
    )

    fun builder(context: Context, channel: String = "c") = Notification.Builder(context, channel)
}
