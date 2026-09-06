package my.pinged.capture

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import my.pinged.data.LocalDate
import my.pinged.data.CaptureDays
import my.pinged.data.Databases
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.CaptureSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 10.3's per-source liveness against the real encrypted database, where
 * the columns the write must not touch actually exist.
 *
 * `SourceLivenessTest` proves which DAO method is called. This proves what the
 * row looks like afterwards, which is the form the promise takes for the user:
 * enabling a bank stays enabled.
 */
@RunWith(AndroidJUnit4::class)
class SourceLivenessDbTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sources = Databases.captureSourceDao(context)

    private val bank = "com.example.livenessbank"
    private val quiet = "com.example.livenessdisabled"

    private val firstSeenAt = 1_600_000_000_000L

    @After
    fun resetTestSeam() {
        CaptureIngest.captureOwnPackage = false
    }

    /**
     * A source in the state the user leaves it in after the allow-list screen:
     * enabled, marked authoritative, with a discovery timestamp.
     *
     * `insertIfNew` then the targeted setters, because `capture_source`
     * deliberately has no whole-row upsert -- the same reason this test exists.
     */
    private fun enrol(pkg: String, enabled: Boolean, authoritative: Boolean): CaptureSource {
        sources.insertIfNew(
            CaptureSource(pkg = pkg, label = "Liveness $pkg", firstSeenAt = firstSeenAt),
        )
        sources.setEnabled(pkg, enabled)
        sources.setAuthoritative(pkg, authoritative)
        sources.setLastNotificationAt(pkg, 0L)
        return sources.byPackage(pkg)!!
    }

    private fun ingest(pkg: String, postTime: Long, arrival: Arrival = Arrival.POSTED) =
        runBlocking {
            CaptureIngest.ingest(
                context = context,
                sbn = CaptureFixtures.posted(
                    CaptureFixtures.notification(
                        context,
                        text = "Payment of RM1.00 to LIVENESS TEST successful",
                    ),
                    pkg,
                    id = postTime.toInt(),
                    postTime = postTime,
                ),
                arrival = arrival,
                now = postTime + 5L,
            )
        }

    @Test
    fun aLivenessWriteLeavesConsentAndDiscoveryUntouched() {
        val before = enrol(bank, enabled = true, authoritative = true)
        val postedAt = firstSeenAt + 90 * 24 * 60 * 60 * 1000L

        ingest(bank, postTime = postedAt)

        val after = sources.byPackage(bank)!!
        assertEquals("Liveness is the only column this path knows about", postedAt, after.lastNotificationAt)
        assertTrue(
            "The user's allow-list consent was revoked by a liveness write. " +
                "capture_source has no upsert precisely so this cannot happen: " +
                "REPLACE is a delete-and-insert, CaptureSource.enabled defaults " +
                "to false, and spec 11.1's default-deny gate then discards this " +
                "bank's notifications before any write -- so nothing is captured, " +
                "nothing is queued, and Android will not replay the past.",
            after.enabled,
        )
        assertTrue(
            "is_authoritative decides which side of a duplicate pair wins " +
                "(spec 7.2) and a liveness write knows nothing about it",
            after.isAuthoritative,
        )
        assertEquals(
            "first_seen_at is what spec 9.6's 30-day discovery drop is measured " +
                "from; resetting it here would keep a package alive forever",
            firstSeenAt,
            after.firstSeenAt,
        )
        assertEquals("The label is not this path's to rewrite", before.label, after.label)
        assertEquals(before.pkg, after.pkg)
    }

    @Test
    fun theThrottleActuallyThrottles() {
        enrol(bank, enabled = true, authoritative = true)
        val first = firstSeenAt + 90 * 24 * 60 * 60 * 1000L

        ingest(bank, postTime = first)
        assertEquals(first, sources.byPackage(bank)!!.lastNotificationAt)

        // One minute later, well inside the window. A phone posts 100-300
        // notifications a day, capture_source is observed by the allow-list
        // screen, and Room's invalidation tracker is table-granular, so an
        // unthrottled write here re-emits that screen's Flow on every
        // notification from every enabled bank (spec 10.2's reasoning).
        ingest(bank, postTime = first + 60_000L)

        assertEquals(
            "A second notification a minute later must not produce a second write",
            first,
            sources.byPackage(bank)!!.lastNotificationAt,
        )
    }

    @Test
    fun aNotificationPastTheWindowIsRecorded() {
        enrol(bank, enabled = true, authoritative = true)
        val first = firstSeenAt + 90 * 24 * 60 * 60 * 1000L
        ingest(bank, postTime = first)

        val later = first + SourceLiveness.THROTTLE_MILLIS
        ingest(bank, postTime = later)

        assertEquals(later, sources.byPackage(bank)!!.lastNotificationAt)
    }

    @Test
    fun theThrottleWindowIsReadFromTheRowAndNotFromMemory() {
        // The difference matters because rebinding is routine: a window held in
        // a process-local field resets on every rebind, and this app's whole
        // reliability section is about how often it rebinds. Simulated here by
        // writing the row directly and then ingesting, which is a state no
        // in-memory window could have known about.
        enrol(bank, enabled = true, authoritative = true)
        val stamped = firstSeenAt + 90 * 24 * 60 * 60 * 1000L
        sources.setLastNotificationAt(bank, stamped)

        ingest(bank, postTime = stamped + 60_000L)

        assertEquals(stamped, sources.byPackage(bank)!!.lastNotificationAt)
    }

    @Test
    fun aCatchUpOfAnOlderNotificationDoesNotMoveLivenessBackwards() {
        enrol(bank, enabled = true, authoritative = true)
        val recent = firstSeenAt + 90 * 24 * 60 * 60 * 1000L
        ingest(bank, postTime = recent)

        // onListenerConnected re-reads every still-live notification on every
        // connect. A bank banner that has sat in the shade for three days is
        // ordinary traffic, and stamping its post time over a newer one would
        // manufacture the exact silence spec 10.3 exists to notice.
        ingest(
            bank,
            postTime = recent - 3 * 24 * 60 * 60 * 1000L,
            arrival = Arrival.CATCHUP,
        )

        assertEquals(recent, sources.byPackage(bank)!!.lastNotificationAt)
    }

    @Test
    fun aDisabledSourceNeverGetsALastSeenTimestamp() {
        // Spec 9.6 is explicit and this is the line it draws. For a package the
        // user has not enabled, only a count is kept and never a last-seen
        // timestamp: a durable record of when the user talks to whom is a
        // different object from a count, and sleep, work patterns and absences
        // read off it directly. So the liveness write has to sit *after* the
        // allow-list gate, not before it.
        enrol(quiet, enabled = false, authoritative = false)
        sources.setLastNotificationAt(quiet, 0L)

        val row = ingest(quiet, postTime = firstSeenAt + 90 * 24 * 60 * 60 * 1000L)

        assertNull("A disabled package must not be captured at all", row)
        assertEquals(
            "A disabled package must not acquire a last-seen timestamp (spec 9.6)",
            0L,
            sources.byPackage(quiet)!!.lastNotificationAt,
        )
        assertTrue(
            "Only a count, which is what discovery is allowed to keep",
            runBlocking { SourceCounters.seenCount(context, quiet) } > 0,
        )
    }

    @Test
    fun aNotificationWithNoReadableTextStillCountsAsTheSourceSpeaking() {
        // A notification drawn entirely with custom RemoteViews reaches stage
        // one with every text field null. It is still captured -- stage two
        // classifies it NO_EXTRAS -- and, more to the point here, the bank did
        // speak. Liveness is about the source being heard from, not about
        // whether anything was parseable.
        enrol(bank, enabled = true, authoritative = true)
        val postedAt = firstSeenAt + 90 * 24 * 60 * 60 * 1000L

        runBlocking {
            CaptureIngest.ingest(
                context = context,
                sbn = CaptureFixtures.posted(
                    CaptureFixtures.notification(context),
                    bank,
                    id = 7,
                    postTime = postedAt,
                ),
                arrival = Arrival.POSTED,
                now = postedAt,
            )
        }

        assertEquals(postedAt, sources.byPackage(bank)!!.lastNotificationAt)
    }
    /**
     * A day the listener was bound and the phone was quiet has to be representable,
     * and until `markListenerBound` existed it was not.
     *
     * Every `capture_day` row was created by the notification writer with
     * `listener_bound` hardcoded `true`, so both columns were constant and a row's
     * existence meant only "some app posted that day" -- while spec 4 says the
     * table exists so the grid can tell "you spent nothing" from "Pinged was not
     * watching".
     *
     * The discriminating shape is `listener_bound` true with
     * `saw_any_notification` false. No other writer can produce it.
     *
     * **Not covered:** that `onListenerConnected` calls it. That needs a real bind
     * with zero notifications arriving, and the emulator is shared -- any app's
     * notification sets `saw_any_notification`. The wiring is one call.
     */
    @Test fun aBoundListenerOnAQuietDayIsDistinguishableFromADeadOne() {
        val quietDay = LocalDate(20_010_203)
        val dayDao = Databases.captureDayDao(context)
        assertNull("precondition: nothing has recorded this day", dayDao.byDate(quietDay))

        runBlocking {
            CaptureHealth.forgetProcessMemo()
            CaptureDays.markListenerBound(quietDay) { dayDao }
        }

        val row = dayDao.byDate(quietDay)
        assertNotNull("A bound listener recorded nothing, so the day reads as dead", row)
        assertTrue("the day must record that capture was alive", row!!.listenerBound)
        assertFalse(
            "This writer has seen no notification; claiming otherwise would make the " +
                "grid say money was captured on a day nothing arrived",
            row.sawAnyNotification,
        )
    }

}
