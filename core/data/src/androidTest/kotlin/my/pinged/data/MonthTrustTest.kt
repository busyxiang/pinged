package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §8: "A gap in capture is never drawn as a zero."
 *
 * The month total is greyed and labelled do-not-trust when the month holds days
 * capture was not alive for. Without this the summary understates a month the
 * app spent dead, the number looks right, and nothing lets the user suspect it.
 *
 * A day with no `capture_day` row at all is an uncaptured day. That is why this
 * counts the days that *were* bound and leaves the comparison against the
 * month's elapsed length to the caller: SQLite has no calendar to left-join
 * against, so "how many days are missing" is not a question this query can ask.
 */
@RunWith(AndroidJUnit4::class)
class MonthTrustTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private fun bind(yyyymmdd: Int, bound: Boolean = true) =
        db.captureDayDao().recordListenerBound(LocalDate(yyyymmdd), bound)

    @Test fun everyBoundDayInARunIsCounted() {
        (1..5).forEach { bind(20260900 + it) }

        assertEquals(
            "A day the listener was bound on was not counted. Every one of " +
                "them missing greys a month total the app could have stood " +
                "behind, and a warning that fires on good months is one the " +
                "user learns to ignore",
            5, db.captureDayDao().boundDayCount(LocalDate(20260901), LocalDate(20260930)),
        )
    }

    @Test fun aDayWithNoRowIsNotCounted() {
        bind(20260901)
        bind(20260903)

        assertEquals(
            "A day the app never ran on was counted as captured, which is exactly " +
                "the gap §8 says must not read as a zero",
            2, db.captureDayDao().boundDayCount(LocalDate(20260901), LocalDate(20260930)),
        )
    }

    @Test fun aDayRecordedAsUnboundIsNotCounted() {
        bind(20260901)
        bind(20260902, bound = false)

        assertEquals(
            "A day recorded as unbound was counted as captured. That is the " +
                "one day the app knows it was not watching, and counting it " +
                "draws the gap as a number (§8)",
            1, db.captureDayDao().boundDayCount(LocalDate(20260901), LocalDate(20260930)),
        )
    }

    @Test fun daysOutsideTheRangeDoNotCount() {
        bind(20260831)
        bind(20260901)
        bind(20261001)

        assertEquals(
            "A day from an adjacent month was counted, so a month borrows its " +
                "neighbours' coverage and can be trusted on days it did not have",
            1, db.captureDayDao().boundDayCount(LocalDate(20260901), LocalDate(20260930)),
        )
    }
}
