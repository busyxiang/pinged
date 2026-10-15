package my.pinged.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.Databases
import my.pinged.data.entity.CaptureSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 10.3's `expected_monthly_count`, filled from `raw_capture` against the
 * real encrypted database.
 */
@RunWith(AndroidJUnit4::class)
class SourceGapsDbTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sources = Databases.captureSourceDao(context)
    private val captures = Databases.rawCaptureDao(context)

    private val day = 24 * 60 * 60 * 1000L
    private val now = System.currentTimeMillis()

    private val enrolled = mutableListOf<String>()

    /** Unique per run: `raw_capture` keeps every earlier run's rows. */
    private fun pkg(name: String) = "com.example.gaps.$name${System.nanoTime()}".also { enrolled += it }

    /** A quiet source left enabled would be named by every later daily check. */
    @After
    fun stopWatching() {
        enrolled.forEach { sources.setEnabled(it, false) }
    }

    private fun enrol(pkg: String, enabled: Boolean, lastAt: Long, expected: Int? = null) {
        sources.insertIfNew(CaptureSource(pkg = pkg, label = pkg, firstSeenAt = now - 400 * day))
        sources.setEnabled(pkg, enabled)
        sources.setLastNotificationAt(pkg, lastAt)
        sources.setExpectedMonthlyCount(pkg, expected)
    }

    private fun captured(pkg: String, postedAt: Long) {
        ParseFixtures.insertCapture(
            context,
            text = "Gap test",
            pkg = pkg,
            sbnKey = "gaps|$pkg|$postedAt",
            postedAt = postedAt,
        )
    }

    @Test
    fun anActiveSourceExpectsWhatItSentInTheThirtyDaysToItsLastNotification() {
        val bank = pkg("active")
        // Ten days silent: a window ending now would lose the oldest two below.
        val last = now - 10 * day
        enrol(bank, enabled = true, lastAt = last)
        repeat(10) { captured(bank, last - it * 60 * 60 * 1000L * 60) }
        // Outside the window: older than thirty days before the last one.
        repeat(3) { captured(bank, last - 31 * day - it * day) }

        SourceGaps.refresh(sources, captures, now)

        assertEquals(10, sources.byPackage(bank)!!.expectedMonthlyCount)
    }

    @Test
    fun aQuietSourceKeepsTheExpectationItWentQuietWith() {
        val bank = pkg("quiet")
        val last = now - 20 * day
        enrol(bank, enabled = true, lastAt = last, expected = 40)
        // Two in the window: recomputed, the silence would erase its own evidence.
        repeat(2) { captured(bank, last - it * day) }

        SourceGaps.refresh(sources, captures, now)

        assertEquals(40, sources.byPackage(bank)!!.expectedMonthlyCount)
        assertTrue(SourceGaps.quiet(sources.byPackage(bank)!!, now) != null)
    }

    /**
     * A source already silent when this check first runs -- an upgrade, or a
     * phone whose daily check never ran -- has no expectation to keep, so it
     * gets one, measured up to where it went quiet.
     */
    @Test
    fun aSourceQuietBeforeItWasEverMeasuredIsMeasuredOnce() {
        val bank = pkg("unmeasured")
        val last = now - 20 * day
        enrol(bank, enabled = true, lastAt = last)
        repeat(12) { captured(bank, last - it * day) }

        SourceGaps.refresh(sources, captures, now)

        assertEquals(12, sources.byPackage(bank)!!.expectedMonthlyCount)
        assertTrue(SourceGaps.quiet(sources.byPackage(bank)!!, now) != null)
    }

    @Test
    fun aDisabledSourceIsNotCounted() {
        val bank = pkg("disabled")
        enrol(bank, enabled = false, lastAt = now - day)
        repeat(10) { captured(bank, now - day - it * day) }

        SourceGaps.refresh(sources, captures, now)

        assertNull(sources.byPackage(bank)!!.expectedMonthlyCount)
    }
}
