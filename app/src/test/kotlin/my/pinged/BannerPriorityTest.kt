package my.pinged

import my.pinged.capture.CaptureReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The banner strip shows one thing at a time, so its order is a decision, and
 * this is the test that can see it.
 *
 * Every case pairs its report with `exportOverdue = true`, because an overdue
 * export is the state that is true *at the same time* as each of the others --
 * a phone whose listener has been dead for a month has not exported for a
 * month either. The nudge speaking over any of them would replace "CAPTURE
 * STOPPED" with "NO BACKUP" for a user whose capture is broken right now.
 */
class BannerPriorityTest {

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

    @Test fun unreadableStorageOutranksTheNudge() {
        assertEquals(
            "The database cannot be opened and the user was told about a " +
                "backup instead. Nothing is being recorded and the banner " +
                "does not say so",
            BannerKind.STORAGE_UNAVAILABLE,
            bannerFor(healthy.copy(storageUnavailable = true), exportOverdue = true),
        )
    }

    @Test fun anUngrantedListenerOutranksTheNudge() {
        assertEquals(
            "Notification access was never granted and the banner asked for " +
                "an export instead of the grant, which is the one tap that " +
                "starts capture at all",
            BannerKind.NOT_GRANTED,
            bannerFor(healthy.copy(granted = false), exportOverdue = true),
        )
    }

    @Test fun aDeadListenerOutranksTheNudge() {
        assertEquals(
            "Nothing has reached Pinged for over a day and the banner said " +
                "\"NO BACKUP\". The user reads that capture is fine and only " +
                "the backup is missing, when spending is going unrecorded now",
            BannerKind.STOPPED,
            bannerFor(
                healthy.copy(staleForMillis = 2 * 24 * 60 * 60 * 1000L),
                exportOverdue = true,
            ),
        )
    }

    /**
     * Both orderings at once, because [freshInstall] is `looksDead` as well as
     * never-seen: the nudge below it, and "CAPTURE STOPPED" above it. A phone
     * granted access five minutes ago with nothing yet arrived would otherwise
     * read that its own phone shut Pinged down and that spending since then was
     * not recorded -- three claims about a past that does not exist.
     */
    @Test fun aFreshInstallOutranksTheNudgeAndTheDeadListenerWording() {
        assertEquals(
            "A grant with nothing ever seen was told its capture has stopped, " +
                "or shown the backup nudge, rather than the fresh-install " +
                "wording",
            BannerKind.NOTHING_SEEN,
            bannerFor(freshInstall, exportOverdue = true),
        )
    }

    /**
     * Three orderings at once, and each is a different sentence the user would
     * otherwise read off a file that was never opened: the fresh-install
     * wording, the dead-listener wording, and the nudge. All three are claims
     * about a heartbeat, and the heartbeat is precisely what could not be read.
     */
    @Test fun anUnreadableHealthStoreOutranksEveryClaimMadeFromIt() {
        assertEquals(
            "`capture_health` would not read and the banner spoke as if it " +
                "had: a phone that has captured for months was told it is a " +
                "new install, or that its listener died, or that the only " +
                "thing wrong is a missing backup",
            BannerKind.HEALTH_UNREADABLE,
            bannerFor(healthUnreadable, exportOverdue = true),
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
            bannerFor(healthUnreadable.copy(granted = false), exportOverdue = true),
        )
    }

    @Test fun aWorkingCaptureWithAnOverdueExportGetsTheNudge() {
        assertEquals(
            "Nothing is wrong with capture and the export is overdue, which " +
                "is the only state the nudge has to itself. It said nothing",
            BannerKind.NUDGE_EXPORT,
            bannerFor(healthy, exportOverdue = true),
        )
    }

    @Test fun aWorkingCaptureWithARecentExportGetsNoBanner() {
        assertNull(
            "A banner was drawn over a working app with a recent export",
            bannerFor(healthy, exportOverdue = false),
        )
    }
}
