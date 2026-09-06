package my.pinged.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grant and the binding are independent (spec 10.1), and the report has to
 * be able to say so.
 */
class CaptureReportTest {

    private val hour = 60 * 60 * 1000L

    private fun report(granted: Boolean, staleFor: Long) = CaptureReport(
        granted = granted,
        lastNotificationAt = if (staleFor == Long.MAX_VALUE) 0L else 5_000_000L,
        staleForMillis = staleFor,
    )

    @Test
    fun `granted and recently fed is the healthy state`() {
        val healthy = report(granted = true, staleFor = 2 * hour)

        assertFalse(healthy.looksDead)
        assertFalse(healthy.needsUserAction)
    }

    @Test
    fun `granted with nothing arriving is the OEM-kill signature`() {
        // The combination the whole type exists for. Collapsed into one
        // boolean, this state is indistinguishable from health -- which is how a
        // user ends up trusting a fabricated total for weeks.
        val killed = report(granted = true, staleFor = 30 * hour)

        assertTrue(killed.looksDead)
        assertTrue(killed.needsUserAction)
    }

    @Test
    fun `no grant is not a dead listener, and is still the user's move`() {
        // looksDead is specifically "granted but silent". Without the grant
        // there is nothing to have died, but there is equally nothing this
        // process can do about it (spec 10.5), so it still needs saying.
        val ungranted = report(granted = false, staleFor = 30 * hour)

        assertFalse(ungranted.looksDead)
        assertTrue(ungranted.needsUserAction)
    }

    @Test
    fun `never having seen a notification is not having seen one at the epoch`() {
        val fresh = CaptureReport(
            granted = true,
            lastNotificationAt = 0L,
            staleForMillis = Long.MAX_VALUE,
        )

        assertTrue(fresh.looksDead)
    }

    @Test
    fun `the fields the banner needs are on the report, separately`() {
        // The plan's own assertion, kept because it is the one that fails if
        // somebody later folds these into a single enum and loses the ability
        // to say "granted, but nothing is arriving".
        val names = CaptureReport::class.java.declaredFields.map { it.name }

        assertTrue(
            "CaptureReport lost a field the banner needs: $names",
            names.containsAll(listOf("granted", "lastNotificationAt")),
        )
    }
}
