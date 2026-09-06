package my.pinged.capture

import my.pinged.data.Databases
import android.app.Notification
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.LocalDates
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage one against the real database, driven directly rather than through a
 * system bind.
 *
 * `ListenerTest` covers the delivery path; this covers the decisions, which is
 * where the invariants live. Both are needed: a bind proves the wiring, and
 * only this can post as a package that is not the test APK.
 */
@RunWith(AndroidJUnit4::class)
class IngestTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao = Databases.rawCaptureDao(context)

    private val enabledPackage = "com.example.enabledbank"
    private val disabledPackage = "com.example.disabledbank"
    private val unknownPackage = "com.example.neverseenbank"

    @After
    fun resetTestSeam() {
        CaptureIngest.captureOwnPackage = false
    }

    private fun ingest(
        pkg: String,
        notification: Notification,
        arrival: Arrival = Arrival.POSTED,
        id: Int = 1,
        tag: String? = null,
        postTime: Long = System.currentTimeMillis(),
        now: Long = System.currentTimeMillis(),
    ): Long? = runBlocking {
        CaptureIngest.ingest(
            context = context,
            sbn = CaptureFixtures.posted(notification, pkg, id, tag, postTime),
            arrival = arrival,
            now = now,
        )
    }

    @Test
    fun anEnabledPackageIsCapturedWithTheFieldsItWasPostedWith() {
        CaptureFixtures.allowList(context, enabledPackage, enabled = true)
        val notification = CaptureFixtures.notification(
            context,
            title = "Maybank",
            text = "short",
            bigText = "Payment of RM12.00 to TEST MERCHANT successful",
            subText = "Savings 1234",
        )
        val postedAt = 1_700_000_000_000L

        val id = ingest(
            enabledPackage,
            notification,
            id = 42,
            tag = "slot",
            postTime = postedAt,
            now = 1_700_000_000_500L,
        )

        assertNotNull("An enabled package must produce a row", id)
        val row = dao.byId(id!!)
        assertEquals(enabledPackage, row.sourcePackage)
        assertEquals(postedAt, row.postedAt)
        assertEquals(1_700_000_000_500L, row.capturedAt)
        assertEquals(42, row.notifId)
        assertEquals("slot", row.notifTag)
        assertEquals("Maybank", row.title)
        assertEquals("short", row.text)
        assertEquals("Payment of RM12.00 to TEST MERCHANT successful", row.bigText)
        assertEquals("Savings 1234", row.subText)
        assertEquals(Arrival.POSTED, row.arrival)
        // Stage one classifies nothing. The row is stage two's work queue.
        assertEquals(ParseStatus.NEW, row.parseStatus)
        assertNull(row.matchedRuleId)
        assertNull(row.extrasJson)
        assertEquals(
            ContentHash.of(
                enabledPackage,
                row.userHandle,
                "maybank payment of rm12.00 to test merchant successful",
            ),
            row.contentHash,
        )
    }

    /**
     * `Arrival.CATCHUP` is not bookkeeping: `getActiveNotifications()` returns
     * only posts that are still live, which is what spec 7.2's duplicate rule
     * 1 means by a refresh, and a catch-up capture is therefore exempt from
     * that rule's ten-minute window. Nothing can reconstruct the path
     * afterwards, so if it is not written here it is gone.
     */
    @Test
    fun aRebindCatchUpCaptureIsRecordedAsCatchup() {
        CaptureFixtures.allowList(context, enabledPackage, enabled = true)

        val id = ingest(
            enabledPackage,
            CaptureFixtures.notification(context, text = "Payment of RM1.00 successful"),
            arrival = Arrival.CATCHUP,
        )

        assertEquals(Arrival.CATCHUP, dao.byId(id!!).arrival)
    }

    /**
     * Spec 9.6 and spec 11.1, and the one a reviewer will not take on faith.
     *
     * The promise is not "no transaction" and not "no capture row": it is that
     * the app never persists content from an app the user did not enable. So
     * the assertion is over the whole database, not over one table.
     */
    @Test
    fun aNotificationFromADisabledPackageStoresNothing() {
        CaptureFixtures.allowList(context, disabledPackage, enabled = false)
        val marker = "ZZMARKERDISABLED" + System.nanoTime()
        val before = dao.countAll()

        val id = ingest(
            disabledPackage,
            CaptureFixtures.notification(
                context,
                title = "Bank $marker",
                text = "Payment of RM99.00 to $marker successful",
                bigText = "RM99.00 $marker",
                subText = marker,
            ),
        )

        assertNull("Nothing may be inserted for a disabled package", id)
        assertEquals(before, dao.countAll())
        assertEquals(
            "The text of a disabled package's notification reached the database",
            emptyList<String>(),
            CaptureFixtures.textInDatabase(context, marker),
        )
    }

    /** A package with no row at all is the same answer as a disabled one: no. */
    @Test
    fun aNotificationFromAnUnknownPackageStoresNothing() {
        assertNull(Databases.captureSourceDao(context).byPackage(unknownPackage))
        val marker = "ZZMARKERUNKNOWN" + System.nanoTime()
        val before = dao.countAll()

        val id = ingest(
            unknownPackage,
            CaptureFixtures.notification(context, text = "Payment of RM5.00 to $marker"),
        )

        assertNull(id)
        assertEquals(before, dao.countAll())
        assertEquals(emptyList<String>(), CaptureFixtures.textInDatabase(context, marker))
    }

    /** Metadata only: a count, and never a last-seen timestamp (spec 9.6). */
    @Test
    fun aDisabledPackageStillIncrementsItsSeenCount() = runBlocking {
        CaptureFixtures.allowList(context, disabledPackage, enabled = false)
        val before = SourceCounters.seenCount(context, disabledPackage)

        CaptureIngest.ingest(
            context,
            CaptureFixtures.posted(
                CaptureFixtures.notification(context, text = "x"),
                disabledPackage,
            ),
            Arrival.POSTED,
            System.currentTimeMillis(),
        )

        assertEquals(before + 1, SourceCounters.seenCount(context, disabledPackage))
    }

    /**
     * The count is notifications, not deliveries.
     *
     * `onListenerConnected` re-ingests `getActiveNotifications()`, which returns
     * what is still posted -- and a bank notification sits in the shade for hours,
     * so every rebind hands stage one the same notification again. Before the
     * catch-up gate this walked one package from 3 SEEN to 17 SEEN across a morning
     * of app relaunches with nothing new having arrived.
     */
    @Test
    fun aRebindCatchUpOfAStillLiveNotificationDoesNotCountItAgain() = runBlocking {
        CaptureFixtures.allowList(context, disabledPackage, enabled = false)
        // The gate's mark is one device-wide value in a DataStore this whole
        // instrumented run shares, so the post time has to be chosen relative
        // to whatever the mark already is rather than assumed to start empty.
        val postTime = maxOf(
            System.currentTimeMillis(),
            SourceCounters.newestCountedPostTime(context) + 1,
        )
        val stillLive = CaptureFixtures.posted(
            CaptureFixtures.notification(context, text = "Payment of RM12.00 received"),
            disabledPackage,
            id = 9,
            tag = "slot",
            postTime = postTime,
        )

        CaptureIngest.ingest(context, stillLive, Arrival.POSTED, postTime)
        val afterFirstPost = SourceCounters.seenCount(context, disabledPackage)

        // Four rebinds. The notification has not moved and nothing new arrived.
        repeat(4) { CaptureIngest.ingest(context, stillLive, Arrival.CATCHUP, postTime) }

        assertEquals(
            "Re-ingesting the same still-live notification counted it again: " +
                "the number beside NOT ONE WORD STORED is delivery events, " +
                "not notifications",
            afterFirstPost,
            SourceCounters.seenCount(context, disabledPackage),
        )
    }

    /**
     * The other half, and the reason the gate is a high-water mark rather than
     * "never count a catch-up".
     *
     * A notification posted while the listener was unbound reaches stage one
     * only through catch-up. It is strictly newer than anything counted so far,
     * because everything counted so far was counted in real time while bound,
     * so the mark lets it through.
     */
    @Test
    fun aCatchUpNotificationNewerThanAnythingCountedIsStillCounted() = runBlocking {
        CaptureFixtures.allowList(context, disabledPackage, enabled = false)
        val before = SourceCounters.seenCount(context, disabledPackage)
        val postTime = maxOf(
            System.currentTimeMillis(),
            SourceCounters.newestCountedPostTime(context) + 1,
        )

        CaptureIngest.ingest(
            context,
            CaptureFixtures.posted(
                CaptureFixtures.notification(context, text = "Arrived while unbound"),
                disabledPackage,
                id = 11,
                postTime = postTime,
            ),
            Arrival.CATCHUP,
            postTime,
        )

        assertEquals(
            "The catch-up gate swallowed a notification nothing had counted, " +
                "which is the missed-while-dead case rebinding exists for",
            before + 1,
            SourceCounters.seenCount(context, disabledPackage),
        )
    }

    /**
     * `getActiveNotifications()` promises no order, and the gate is a single
     * mark, so a batch handed over newest-first would move the mark past the
     * rest of its own batch.
     */
    @Test
    fun aCatchUpBatchIsOrderedOldestPostFirst() {
        val notification = CaptureFixtures.notification(context, text = "x")
        val batch = arrayOf(
            CaptureFixtures.posted(notification, disabledPackage, id = 3, postTime = 300L),
            CaptureFixtures.posted(notification, disabledPackage, id = 1, postTime = 100L),
            CaptureFixtures.posted(notification, disabledPackage, id = 2, postTime = 200L),
        )

        assertEquals(
            listOf(100L, 200L, 300L),
            CaptureIngest.catchUpOrder(batch).map { it.postTime },
        )
        assertEquals(emptyList<Long>(), CaptureIngest.catchUpOrder(null).map { it.postTime })
    }

    /**
     * Spec 9.6's number is drawn for enabled sources too, and it used to read
     * `0 SEEN` forever for one that was demonstrably capturing.
     *
     * The count is not a second record of anything here: every notification
     * this test ingests is stored below in `raw_capture`, with its full text.
     */
    @Test
    fun anEnabledPackageAlsoCountsWhatItHasSeen() = runBlocking {
        CaptureFixtures.allowList(context, enabledPackage, enabled = true)
        val before = SourceCounters.seenCount(context, enabledPackage)

        val id = CaptureIngest.ingest(
            context,
            CaptureFixtures.posted(
                CaptureFixtures.notification(context, text = "Payment of RM3.00 successful"),
                enabledPackage,
            ),
            Arrival.POSTED,
            System.currentTimeMillis(),
        )

        assertNotNull("This test is meaningless if the capture did not happen", id)
        assertEquals(
            "An enabled source reads 0 SEEN while it is capturing",
            before + 1,
            SourceCounters.seenCount(context, enabledPackage),
        )
    }

    /**
     * Our own notifications reach our own listener (spec 10.2), and the skip
     * comes before the allow-list gate -- so even with our own package
     * enabled, nothing is stored.
     */
    @Test
    fun ourOwnNotificationsAreSkippedEvenWhenOurPackageIsEnabled() {
        CaptureFixtures.allowList(context, context.packageName, enabled = true)
        val marker = "ZZMARKEROWN" + System.nanoTime()
        val before = dao.countAll()

        val id = ingest(
            context.packageName,
            CaptureFixtures.notification(context, text = "Payment of RM1.00 to $marker"),
        )

        assertNull(id)
        assertEquals(before, dao.countAll())
        assertEquals(emptyList<String>(), CaptureFixtures.textInDatabase(context, marker))
    }

    /**
     * A group summary carries aggregated or system-generated text, which is
     * either unread noise or a duplicate of its own child (spec 3).
     */
    @Test
    fun aGroupSummaryFromAnEnabledPackageIsSkipped() {
        CaptureFixtures.allowList(context, enabledPackage, enabled = true)
        val marker = "ZZMARKERSUMMARY" + System.nanoTime()
        val before = dao.countAll()

        val id = ingest(
            enabledPackage,
            CaptureFixtures.notification(
                context,
                text = "3 new messages $marker",
                flags = Notification.FLAG_GROUP_SUMMARY,
            ),
        )

        assertNull(id)
        assertEquals(before, dao.countAll())
        assertEquals(emptyList<String>(), CaptureFixtures.textInDatabase(context, marker))
    }

    /**
     * A notification drawn entirely with custom `RemoteViews` has no reachable
     * text, and the row still has to be written: `NO_EXTRAS` is a
     * `parse_status` (spec 4), so stage two can only record it about a capture
     * that exists. Dropping it here would also hide the source from the
     * capture-health screens entirely.
     */
    @Test
    fun aNotificationWithNoReachableTextStillProducesARow() {
        CaptureFixtures.allowList(context, enabledPackage, enabled = true)

        val id = ingest(enabledPackage, CaptureFixtures.notification(context))

        val row = dao.byId(id!!)
        assertNull(row.title)
        assertNull(row.text)
        assertNull(row.bigText)
        assertEquals(ParseStatus.NEW, row.parseStatus)
    }

    /**
     * Stage one does not deduplicate. Both layers of spec 7.2 are stage two's,
     * reading the table; a stage-one shortcut would drop the second of two
     * genuinely separate identical payments before anything could review it.
     */
    @Test
    fun twoIdenticalPostsBothProduceRows() {
        CaptureFixtures.allowList(context, enabledPackage, enabled = true)
        val notification = CaptureFixtures.notification(
            context,
            text = "Payment of RM10.00 to TNG RELOAD successful",
        )
        val before = dao.countAll()

        val first = ingest(enabledPackage, notification, id = 7, tag = "slot")
        val second = ingest(enabledPackage, notification, id = 7, tag = "slot")

        assertEquals(before + 2, dao.countAll())
        assertEquals(dao.byId(first!!).contentHash, dao.byId(second!!).contentHash)
        assertEquals(dao.byId(first).sbnKey, dao.byId(second).sbnKey)
    }

    /**
     * Spec 4's `capture_day`, which is what lets the rhythm grid tell "you
     * spent nothing" apart from "Pinged was not watching". Nothing can backfill
     * it, and it is written for a notification from any app -- so the disabled
     * package below is the honest case, not the enabled one.
     */
    @Test
    fun aNotificationFromAnyPackageMarksTheDayAsWatched() {
        CaptureFixtures.allowList(context, disabledPackage, enabled = false)
        val now = System.currentTimeMillis()

        ingest(disabledPackage, CaptureFixtures.notification(context, text = "x"), now = now)

        val day = Databases.captureDayDao(context).byDate(LocalDates.of(now))
        assertNotNull("capture_day has no row for today", day)
        assertTrue(day!!.sawAnyNotification)
        assertTrue(day.listenerBound)
    }

    /** The heartbeat is liveness for any app, so it too precedes the gate. */
    @Test
    fun aNotificationFromAnyPackageUpdatesTheHeartbeat() = runBlocking {
        CaptureFixtures.allowList(context, disabledPackage, enabled = false)
        val now = System.currentTimeMillis() + CaptureHealth.THROTTLE_MILLIS * 2

        CaptureIngest.ingest(
            context,
            CaptureFixtures.posted(
                CaptureFixtures.notification(context, text = "x"),
                disabledPackage,
            ),
            Arrival.POSTED,
            now,
        )

        assertEquals(now, CaptureHealth.lastSeenAt(context))
    }

    /**
     * The liveness writes above are allowed to precede the allow-list gate
     * only because they record no package and no content. `capture_source`
     * legitimately holds the identifier -- the user's own allow-list is what
     * that table is -- so the assertion is that nothing about the disabled
     * package reached `raw_capture`.
     */
    @Test
    fun nothingAboutADisabledPackageReachesRawCapture() = runBlocking {
        CaptureFixtures.allowList(context, disabledPackage, enabled = false)

        CaptureIngest.ingest(
            context,
            CaptureFixtures.posted(
                CaptureFixtures.notification(context, text = "x"),
                disabledPackage,
            ),
            Arrival.POSTED,
            System.currentTimeMillis(),
        )

        val hits = CaptureFixtures.textInDatabase(context, disabledPackage)
        assertFalse(hits.toString(), hits.any { it.startsWith("raw_capture.") })
    }
}
