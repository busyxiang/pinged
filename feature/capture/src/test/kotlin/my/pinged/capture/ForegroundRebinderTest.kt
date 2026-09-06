package my.pinged.capture

import android.app.Activity
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Spec 10.1's third rebind path fires once per app foreground, not once per
 * screen.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundRebinderTest {

    private var foregrounds = 0
    private val rebinder = ForegroundRebinder { foregrounds++ }

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).create().get()

    @Test
    fun `the first activity started is an app foreground`() {
        rebinder.onActivityStarted(activity())

        assertEquals(1, foregrounds)
    }

    @Test
    fun `moving between screens is not leaving and re-entering the app`() {
        val first = activity()
        val second = activity()

        // The real ordering: the incoming screen starts before the outgoing one
        // stops, so a naive per-activity hook would fire twice here.
        rebinder.onActivityStarted(first)
        rebinder.onActivityStarted(second)
        rebinder.onActivityStopped(first)

        assertEquals(1, foregrounds)
    }

    @Test
    fun `a rotation is not an app foreground`() {
        val before = activity()
        rebinder.onActivityStarted(before)

        val after = activity()
        rebinder.onActivitySaveInstanceState(before, Bundle())
        rebinder.onActivityStopped(before)
        rebinder.onActivityDestroyed(before)
        rebinder.onActivityStarted(after)

        // Recreation stops the old instance before starting the new one, so the
        // count does drop to zero -- and it has to be allowed to fire again,
        // because the same sequence is also what a real return to the app looks
        // like. Two foregrounds here is the honest answer and costs one
        // requestRebind, which is idempotent.
        assertEquals(2, foregrounds)
    }

    @Test
    fun `returning to the app fires again`() {
        val screen = activity()

        rebinder.onActivityStarted(screen)
        rebinder.onActivityStopped(screen)
        rebinder.onActivityStarted(screen)

        assertEquals(2, foregrounds)
    }

    @Test
    fun `an unbalanced stop does not drive the count negative`() {
        // A stop with no matching start reaches this object whenever it is
        // registered while an Activity is already running -- which is what
        // happens if a future task installs it from anywhere but
        // Application.onCreate. Without the floor, the next real foreground
        // would be swallowed.
        val screen = activity()

        rebinder.onActivityStopped(screen)
        rebinder.onActivityStarted(screen)

        assertEquals(1, foregrounds)
    }
}
