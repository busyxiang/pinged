package my.pinged

import my.pinged.capture.CaptureReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The banner strip shows one thing at a time, so its order is a decision, and
 * this is the test that can see it. And Spending's backup reminder shows only
 * where the strip shows nothing, which is the other half of this file.
 *
 * Every reminder case under a capture banner pairs its report with an overdue
 * export, because an overdue export is the state that is true *at the same
 * time* as each of the others -- a phone whose listener has been dead for a
 * month has not exported for a month either. The reminder beside any of them
 * would have the user read "NO BACKUP" as the thing wrong, for a user whose
 * capture is broken right now.
 */
class BannerPriorityTest {
    private val now = 1_700_000_000_000L

    /**
     * A notification seen this instant: `ListenerStatus.report` computes
     * `staleForMillis` as `now - lastNotificationAt`, so a zero beside a
     * non-zero timestamp is the report taken the moment one arrived.
     */
    private val healthy = CaptureReport(
        granted = true,
        lastNotificationAt = 1_700_000_000_000L,
        staleForMillis = 0L,
    )

    /**
     * **`Long.MAX_VALUE`, and it is the whole of what this fixture pins.**
     * `ListenerStatus.report` sets `staleForMillis` to `MAX_VALUE` whenever
     * `lastNotificationAt` is zero, because never seen is not "seen at the
     * epoch" -- so on a real phone nothing-ever-seen always carries
     * `looksDead` with it once the grant is present, and the two branches
     * `bannerFor` puts in that order are in permanent conflict. A fixture
     * leaving `staleForMillis` at zero is a report `ListenerStatus` cannot
     * emit: `looksDead` is false, only one branch matches, and the order the
     * test is named for is never exercised.
     */
    private val freshInstall = healthy.copy(
        lastNotificationAt = 0L,
        staleForMillis = Long.MAX_VALUE,
    )

    /**
     * **The fields are the never-seen encoding and that is not incidental.**
     * `ListenerStatus.report` fabricates exactly this when `capture_health`
     * will not read -- `lastNotificationAt` zero, `staleForMillis`
     * `MAX_VALUE` -- so a fixture that set `healthUnreadable` beside a fresh
     * heartbeat would be a report nothing can emit, and the two branches it
     * really collides with, `neverSeen` and `looksDead`, would both be false
     * and never exercised. Here both are true, which is what makes the order
     * below the thing under test.
     */
    private val healthUnreadable = healthy.copy(
        lastNotificationAt = 0L,
        staleForMillis = Long.MAX_VALUE,
        healthUnreadable = true,
    )

    /** Seen once, two days ago, and nothing since. */
    private val dead = healthy.copy(staleForMillis = 2 * 24 * 60 * 60 * 1000L)

    /** Each report that puts a capture banner up, by the banner it puts up. */
    private val captureBanners = mapOf(
        BannerKind.STORAGE_UNAVAILABLE to healthy.copy(storageUnavailable = true),
        BannerKind.NOT_GRANTED to healthy.copy(granted = false),
        BannerKind.HEALTH_UNREADABLE to healthUnreadable,
        BannerKind.NOTHING_SEEN to freshInstall,
        BannerKind.STOPPED to dead,
    )

    /**
     * The ordering this fixture exists for: [freshInstall] is `looksDead` as
     * well as never-seen, and "CAPTURE STOPPED" is the branch below. A phone
     * granted access five minutes ago with nothing yet arrived would otherwise
     * read that its own phone shut Pinged down and that spending since then was
     * not recorded -- three claims about a past that does not exist.
     */
    @Test fun aFreshInstallOutranksTheDeadListenerWording() {
        assertEquals(
            "A grant with nothing ever seen was told its capture has stopped, " +
                "rather than the fresh-install wording",
            BannerKind.NOTHING_SEEN,
            bannerFor(freshInstall),
        )
    }

    /**
     * Two orderings at once, and each is a different sentence the user would
     * otherwise read off a file that was never opened: the fresh-install
     * wording and the dead-listener wording. Both are claims about a
     * heartbeat, and the heartbeat is precisely what could not be read.
     */
    @Test fun anUnreadableHealthStoreOutranksEveryClaimMadeFromIt() {
        assertEquals(
            "`capture_health` would not read and the banner spoke as if it " +
                "had: a phone that has captured for months was told it is a " +
                "new install, or that its listener died",
            BannerKind.HEALTH_UNREADABLE,
            bannerFor(healthUnreadable),
        )
    }

    /**
     * The one thing still measured when the store will not read is the grant,
     * which does not come from the store -- and it is both more certain and
     * more actionable than "cannot tell", so it stays above.
     */
    @Test fun aRevokedGrantOutranksAnUnreadableHealthStore() {
        assertEquals(
            "Notification access is gone, which is known and has a button, " +
                "and the banner said only that Pinged cannot tell",
            BannerKind.NOT_GRANTED,
            bannerFor(healthUnreadable.copy(granted = false)),
        )
    }

    @Test fun aWorkingCaptureGetsNoBanner() {
        assertNull("A banner was drawn over a working capture", bannerFor(healthy))
    }

    /**
     * Over every kind, so a sixth capture state added to [BannerKind] without
     * a fixture here fails rather than going unchecked.
     */
    @Test fun theFixturesCoverEveryBanner() {
        assertEquals(BannerKind.entries.toSet(), captureBanners.keys)
        captureBanners.forEach { (kind, report) -> assertEquals(kind, bannerFor(report)) }
    }

    @Test fun theReminderNeverShowsUnderACaptureBanner() {
        captureBanners.forEach { (kind, report) ->
            assertFalse(
                "The backup reminder showed beside $kind with an overdue " +
                    "export. Capture is broken now, and the user reads \"NO " +
                    "BACKUP\" as the thing to fix",
                backupReminderShows(report, exportOverdue = true),
            )
        }
    }

    @Test fun aWorkingCaptureWithAnOverdueExportShowsTheReminder() {
        assertTrue(
            "Nothing is wrong with capture and the export is overdue, which " +
                "is the one state the reminder is for. It did not show",
            backupReminderShows(healthy, exportOverdue = exportIsOverdue(txns = 12, lastExportAt = 0L, now = now)),
        )
    }

    @Test fun aRecentExportHidesTheReminder() {
        assertFalse(
            "The reminder showed a second after an export",
            backupReminderShows(healthy, exportOverdue = exportIsOverdue(txns = 12, lastExportAt = now - 1_000, now = now)),
        )
    }

    @Test fun theSnoozeHidesTheReminder() {
        assertFalse(
            "The reminder showed a minute after \"Remind me in a week\"",
            backupReminderShows(
                healthy,
                exportOverdue = exportIsOverdue(txns = 12, lastExportAt = 0L, now = now, nudgeDismissedAt = now - 60_000),
            ),
        )
    }

    /** Before the first sample, nothing is known about capture, so nothing is said. */
    @Test fun noReportYetShowsNoReminder() {
        assertFalse(
            "The reminder showed before capture was first sampled, when a capture banner may yet be due",
            backupReminderShows(null, exportOverdue = true),
        )
    }
}
