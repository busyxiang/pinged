package my.pinged.data

import my.pinged.data.LocalDate
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.CaptureSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two tables where a whole-row `@Insert(REPLACE)` upsert destroyed
 * information no later write can reconstruct.
 *
 * REPLACE is not an update: SQLite implements it as a delete followed by an
 * insert, so every column the incoming object does not carry reverts to that
 * object's value -- and `CaptureSource.enabled` defaults to `false`. The
 * default-deny gate then discarded that source's notifications *before* any
 * write, so there was no capture left to re-parse once the user noticed weeks
 * later that one bank had gone quiet.
 */
@RunWith(AndroidJUnit4::class)
class AllowListConsentTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() {
        db = freshDatabase()
    }

    @After fun tearDown() = db.close()

    // ---- capture_source -------------------------------------------------

    /**
     * The liveness write of spec 10.3, which knows a package and a timestamp
     * and nothing else. Under REPLACE it had to supply a whole `CaptureSource`
     * to say "I saw a notification from this package", and whatever it guessed
     * for `enabled` became the truth.
     */
    @Test fun aLivenessUpdateCannotSwitchASourceOff() {
        val dao = db.captureSourceDao()
        dao.insertIfNew(
            CaptureSource(pkg = "com.maybank2u.life", label = "Maybank", firstSeenAt = 100L),
        )
        dao.setEnabled("com.maybank2u.life", true)
        dao.setAuthoritative("com.maybank2u.life", true)

        dao.setLastNotificationAt("com.maybank2u.life", 900L)

        val row = dao.byPackage("com.maybank2u.life")!!
        assertEquals(
            "A liveness write disabled a source the user had allowed",
            true,
            row.enabled,
        )
        assertEquals(true, row.isAuthoritative)
        assertEquals(900L, row.lastNotificationAt)
        assertEquals("Maybank", row.label)
        assertEquals(100L, row.firstSeenAt)
        assertEquals(
            listOf("com.maybank2u.life"),
            dao.enabled().map { it.pkg },
        )
    }

    /**
     * Re-discovery, which is the other partial write: the discovery screen
     * (spec 9.6) re-sees a package it already knows and re-reads its label.
     * The label may legitimately change after an app update; the consent must
     * not move with it.
     */
    @Test fun relabellingASourceCannotSwitchItOff() {
        val dao = db.captureSourceDao()
        dao.insertIfNew(CaptureSource(pkg = "com.tng", label = "TNG", firstSeenAt = 1L))
        dao.setEnabled("com.tng", true)

        dao.setLabel("com.tng", "Touch 'n Go eWallet")

        assertEquals(true, dao.byPackage("com.tng")?.enabled)
        assertEquals("Touch 'n Go eWallet", dao.byPackage("com.tng")?.label)
        assertEquals(1, dao.countAll())
    }

    @Test fun eachSetterTouchesOnlyItsOwnColumn() {
        val dao = db.captureSourceDao()
        dao.insertIfNew(CaptureSource(pkg = "com.x", label = "X", firstSeenAt = 5L))
        dao.setEnabled("com.x", true)
        dao.setAuthoritative("com.x", true)
        dao.setLastNotificationAt("com.x", 7L)
        dao.setExpectedMonthlyCount("com.x", 42)

        // Each of the four again, one at a time, with the others unchanged.
        dao.setExpectedMonthlyCount("com.x", 43)
        dao.byPackage("com.x")!!.let {
            assertEquals(true, it.enabled)
            assertEquals(true, it.isAuthoritative)
            assertEquals(7L, it.lastNotificationAt)
            assertEquals(43, it.expectedMonthlyCount)
            assertEquals("X", it.label)
            assertEquals(5L, it.firstSeenAt)
        }
    }

    /** A setter for a package that is not there is a zero-row update. */
    @Test fun settersOnAnUnknownPackageChangeNothing() {
        val dao = db.captureSourceDao()
        assertEquals(0, dao.setEnabled("com.nope", true))
        assertEquals(0, dao.setLastNotificationAt("com.nope", 1L))
        assertEquals(0, dao.countAll())
    }

    /** The gate itself, which is what the consent above buys. */
    @Test fun disablingASourceRemovesItFromTheAllowList() {
        val dao = db.captureSourceDao()
        dao.insertIfNew(enabledSource("com.on"))
        dao.insertIfNew(enabledSource("com.off"))
        dao.setEnabled("com.off", false)
        assertEquals(listOf("com.on"), dao.enabled().map { it.pkg })
    }

    // ---- capture_day ----------------------------------------------------

    /**
     * `saw_any_notification` is an accumulation across a whole day, and its two
     * writers do not know each other's facts: the notification path knows a
     * notification came and nothing about the binding, the rebind path the reverse.
     * Under REPLACE, whichever wrote second erased the other's answer.
     *
     * That produces a false statement rather than a blank screen: the day renders
     * as a hatched cell and counts toward "N days not captured", so the app reports
     * -- in the one place spec 4 built to be honest about its own coverage -- that
     * it was blind on a day it was watching.
     */
    @Test fun aListenerBoundWriteCannotClearSawAnyNotification() {
        val dao = db.captureDayDao()
        dao.recordNotificationSeen(LocalDate(20260904))
        assertEquals(true, dao.byDate(LocalDate(20260904))?.sawAnyNotification)

        // The heartbeat writes what it observed about the binding, and knows
        // nothing about notifications.
        dao.recordListenerBound(LocalDate(20260904), true)

        assertEquals(
            "A binding observation erased the day's notification record",
            true,
            dao.byDate(LocalDate(20260904))?.sawAnyNotification,
        )
        assertEquals(true, dao.byDate(LocalDate(20260904))?.listenerBound)
    }

    /** ...and the same in the other order and with a false binding. */
    @Test fun aLostBindingLaterInTheDayDoesNotUnseeTheMorningsNotifications() {
        val dao = db.captureDayDao()
        dao.recordListenerBound(LocalDate(20260905), true)
        dao.recordNotificationSeen(LocalDate(20260905))

        dao.recordListenerBound(LocalDate(20260905), false)

        val row = dao.byDate(LocalDate(20260905))!!
        assertEquals("The day was watching and captured something", true, row.sawAnyNotification)
        assertEquals("The binding is a state and does move", false, row.listenerBound)
    }

    @Test fun repeatedNotificationsInADayStayOneRow() {
        val dao = db.captureDayDao()
        repeat(5) { dao.recordNotificationSeen(LocalDate(20260906)) }
        assertEquals(true, dao.byDate(LocalDate(20260906))?.sawAnyNotification)
        assertEquals(true, dao.byDate(LocalDate(20260906))?.listenerBound)
        assertEquals(1, db.captureDayDao().countAll())
    }

    /**
     * A day that bound but saw nothing is the honest zero: spec 4 exists to
     * tell that apart from "you spent nothing", so `false` has to be
     * recordable and must not be an artefact of a missing row.
     */
    @Test fun aBoundDayWithNoNotificationsIsRecordedAsSuch() {
        val dao = db.captureDayDao()
        dao.recordListenerBound(LocalDate(20260907), true)
        val row = dao.byDate(LocalDate(20260907))!!
        assertEquals(true, row.listenerBound)
        assertEquals(false, row.sawAnyNotification)
    }

    @Test fun daysAreKeyedByLocalDateAndDoNotBleedIntoEachOther() {
        val dao = db.captureDayDao()
        dao.recordNotificationSeen(LocalDate(20260908))
        dao.recordListenerBound(LocalDate(20260909), false)
        assertEquals(true, dao.byDate(LocalDate(20260908))?.sawAnyNotification)
        assertEquals(false, dao.byDate(LocalDate(20260909))?.sawAnyNotification)
        assertEquals(2, db.captureDayDao().countAll())
    }

}
