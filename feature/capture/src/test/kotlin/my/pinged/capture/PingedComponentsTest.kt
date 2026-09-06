package my.pinged.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The frozen name, asserted rather than trusted.
 *
 * The notification-access grant is stored against the flattened
 * `ComponentName`, so a rename voids it with no callback and no visible event.
 * This test is what turns "do not rename this" from a comment into a build
 * failure.
 */
@RunWith(RobolectricTestRunner::class)
class PingedComponentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the frozen class name is the listener's actual class name`() = assertEquals(
        PingedNotificationListener::class.java.name,
        PingedComponents.LISTENER_CLASS_NAME,
    )

    @Test
    fun `the frozen class name is what the grant is stored against`() = assertEquals(
        "my.pinged.capture.PingedNotificationListener",
        PingedComponents.LISTENER_CLASS_NAME,
    )

    @Test
    fun `the component is built against the host package, not this module`() {
        val component = PingedComponents.listener(context)

        assertEquals(context.packageName, component.packageName)
        assertEquals(PingedComponents.LISTENER_CLASS_NAME, component.className)
    }
}
