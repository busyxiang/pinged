package my.pinged.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What spec 10.2's daily check tells a user who is not opening the app, and
 * when it says nothing.
 */
class HealthAlertTest {
    private val day = 24 * 60 * 60 * 1000L

    private val healthy = CaptureReport(granted = true, lastNotificationAt = 5_000_000L, staleForMillis = 60_000L)
    private val tng = SourceGaps.Quiet(pkg = "my.com.tngdigital.ewallet", label = "TNG eWallet", days = 15, expected = 40)
    private val mae = SourceGaps.Quiet(pkg = "com.maybank2u.life", label = "MAE", days = 20, expected = 12)

    @Test fun healthyWithNoQuietSourceSaysNothing() {
        assertNull(HealthAlert.decide(healthy, emptyList()))
    }

    @Test fun oneQuietSourceIsNamedWithItsHabit() {
        val alert = HealthAlert.decide(healthy, listOf(tng))!!
        assertEquals("No TNG eWallet notifications in 15 days", alert.title)
        assertEquals(
            "There were 40 in the month before. Its notifications may have been turned off.",
            alert.body,
        )
    }

    @Test fun severalQuietSourcesAreCountedAndListed() {
        val alert = HealthAlert.decide(healthy, listOf(tng, mae))!!
        assertEquals("2 sources have gone quiet", alert.title)
        assertEquals("TNG eWallet, MAE. Their notifications may have been turned off.", alert.body)
    }

    @Test fun aStoppedListenerOutranksAQuietSource() {
        val stopped = healthy.copy(staleForMillis = 2 * day)
        assertEquals("Pinged has heard nothing in 2 days", HealthAlert.decide(stopped, listOf(tng))!!.title)
    }

    @Test fun aRevokedGrantOutranksAStoppedListener() {
        val revoked = healthy.copy(granted = false, staleForMillis = 2 * day)
        assertEquals("Pinged cannot see notifications", HealthAlert.decide(revoked, emptyList())!!.title)
    }

    @Test fun storageOutranksEverything() {
        val storage = healthy.copy(granted = false, storageUnavailable = true)
        assertEquals("Pinged cannot open its data", HealthAlert.decide(storage, listOf(tng))!!.title)
    }

    @Test fun aFreshInstallThatHasHeardNothingIsNotAlarmed() {
        val fresh = CaptureReport(granted = true, lastNotificationAt = 0L, staleForMillis = Long.MAX_VALUE)
        assertNull(HealthAlert.decide(fresh, emptyList()))
    }

    private val unreadable = CaptureReport(
        granted = true,
        lastNotificationAt = 0L,
        staleForMillis = Long.MAX_VALUE,
        healthUnreadable = true,
    )

    @Test fun anUnreadableHeartbeatClaimsNothingAboutTheListener() {
        assertNull(HealthAlert.decide(unreadable, emptyList()))
    }

    /** The per-source check reads `capture_source`, not the heartbeat's file. */
    @Test fun aQuietSourceIsStillNamedWhenTheHeartbeatSaysNothing() {
        assertEquals("No TNG eWallet notifications in 15 days", HealthAlert.decide(unreadable, listOf(tng))!!.title)
        val fresh = CaptureReport(granted = true, lastNotificationAt = 0L, staleForMillis = Long.MAX_VALUE)
        assertEquals("No TNG eWallet notifications in 15 days", HealthAlert.decide(fresh, listOf(tng))!!.title)
    }

    @Test fun theKeyIgnoresHowLongSoADailyRunDoesNotRepeatItself() {
        val stopped = healthy.copy(staleForMillis = 2 * day)
        assertEquals(
            HealthAlert.decide(stopped, emptyList())!!.key,
            HealthAlert.decide(stopped.copy(staleForMillis = 3 * day), emptyList())!!.key,
        )
        assertEquals(
            HealthAlert.decide(healthy, listOf(tng))!!.key,
            HealthAlert.decide(healthy, listOf(tng.copy(days = 16)))!!.key,
        )
    }

    @Test fun aNewQuietSourceIsNewNews() {
        assertEquals(
            false,
            HealthAlert.decide(healthy, listOf(tng))!!.key == HealthAlert.decide(healthy, listOf(tng, mae))!!.key,
        )
    }
}
