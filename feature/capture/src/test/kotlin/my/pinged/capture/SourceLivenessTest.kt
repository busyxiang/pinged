package my.pinged.capture

import my.pinged.data.dao.CaptureSourceDao
import my.pinged.data.entity.CaptureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The throttle and the shape of the write, with no database in the way.
 *
 * `SourceLivenessDbTest` proves the same rules against the real encrypted
 * database, where the columns actually exist. This file proves the arithmetic
 * and, more usefully, *which DAO method is called* -- a recording fake answers
 * that exactly, where a database can only be asked what changed afterwards.
 */
class SourceLivenessTest {

    /**
     * Every method of [CaptureSourceDao], each recording its own name.
     *
     * The point of the recording is the negative assertion. Spec 10.3's write
     * knows a package and a timestamp; the columns it does not know about
     * include `enabled`, which is the user's allow-list consent. Asserting that
     * `setLastNotificationAt` is the only mutation is stronger than asserting
     * that `enabled` still reads true, because it fails on a write that happens
     * to set consent back to the value it already had.
     */
    private class RecordingDao(private var row: CaptureSource?) : CaptureSourceDao {
        val calls = mutableListOf<String>()

        override fun insertIfNew(source: CaptureSource): Long {
            calls += "insertIfNew"
            return 1L
        }

        override fun setEnabled(pkg: String, enabled: Boolean): Int {
            calls += "setEnabled"
            row = row?.copy(enabled = enabled)
            return 1
        }

        override fun setAuthoritative(pkg: String, authoritative: Boolean): Int {
            calls += "setAuthoritative"
            row = row?.copy(isAuthoritative = authoritative)
            return 1
        }

        override fun setLabel(pkg: String, label: String): Int {
            calls += "setLabel"
            row = row?.copy(label = label)
            return 1
        }

        override fun setLastNotificationAt(pkg: String, at: Long): Int {
            calls += "setLastNotificationAt($pkg, $at)"
            row = row?.copy(lastNotificationAt = at)
            return 1
        }

        override fun setExpectedMonthlyCount(pkg: String, count: Int?): Int {
            calls += "setExpectedMonthlyCount"
            row = row?.copy(expectedMonthlyCount = count)
            return 1
        }

        /**
         * The two methods the restore path uses. Recorded like the rest, and
         * for a sharper reason than completeness: if a liveness write ever
         * reached for `insertForImport`, it would be the whole-row write the
         * note on `insertIfNew` exists to forbid, and `deleteAllForImport`
         * would take the user's allow-list with it. Either would show up here
         * as a call this file's assertions do not expect.
         */
        override fun insertForImport(source: CaptureSource): Long {
            calls += "insertForImport"
            row = source
            return 1L
        }

        override fun insertAllForImport(sources: List<CaptureSource>) {
            calls += "insertAllForImport"
            row = sources.lastOrNull() ?: row
        }

        override fun deleteAllForImport(): Int {
            calls += "deleteAllForImport"
            val had = if (row == null) 0 else 1
            row = null
            return had
        }

        override fun countAll(): Int = if (row == null) 0 else 1
        override fun enabledCount(): Int = listOfNotNull(row).count { it.enabled }
        override fun enabled(): List<CaptureSource> = listOfNotNull(row).filter { it.enabled }
        override fun byPackage(pkg: String): CaptureSource? = row?.takeIf { it.pkg == pkg }
        override fun all(): List<CaptureSource> = listOfNotNull(row)
    }

    private val bank = "com.maybank2u.life"

    private fun source(lastNotificationAt: Long?) = CaptureSource(
        pkg = bank,
        label = "Maybank",
        enabled = true,
        isAuthoritative = true,
        firstSeenAt = 1_000L,
        lastNotificationAt = lastNotificationAt,
        expectedMonthlyCount = 40,
    )

    @Test
    fun `a source that has never spoken is recorded`() {
        val dao = RecordingDao(source(lastNotificationAt = null))

        assertTrue(SourceLiveness.record(dao, source(null), at = 5_000_000L))
        assertEquals(listOf("setLastNotificationAt($bank, 5000000)"), dao.calls)
    }

    @Test
    fun `a second notification inside the window is not written`() {
        val first = 5_000_000L
        val dao = RecordingDao(source(lastNotificationAt = first))

        // One minute later. A phone posts 100-300 notifications a day and
        // capture_source is observed by the allow-list screen, so an
        // unthrottled write would re-emit that screen's Flow on every one of
        // them from every enabled bank (spec 10.2's reasoning, applied to the
        // per-source column).
        val again = SourceLiveness.record(
            dao,
            source(lastNotificationAt = first),
            at = first + 60_000L,
        )

        assertFalse("A write inside the throttle window bought nothing", again)
        assertEquals(emptyList<String>(), dao.calls)
    }

    @Test
    fun `a notification after the window is written`() {
        val first = 5_000_000L
        val later = first + SourceLiveness.THROTTLE_MILLIS
        val dao = RecordingDao(source(lastNotificationAt = first))

        assertTrue(SourceLiveness.record(dao, source(lastNotificationAt = first), at = later))
        assertEquals(listOf("setLastNotificationAt($bank, $later)"), dao.calls)
    }

    @Test
    fun `liveness never moves backwards`() {
        // onListenerConnected re-reads every still-live notification on every
        // connect, and rebinding is routine, so a catch-up delivery of a
        // three-day-old banner is normal traffic and not an edge case. Stamping
        // its post time over a newer one would manufacture the exact silence
        // spec 10.3 exists to notice.
        val recent = 5_000_000L
        val threeDaysEarlier = recent - 3 * 24 * 60 * 60 * 1000L
        val dao = RecordingDao(source(lastNotificationAt = recent))

        assertFalse(
            SourceLiveness.record(
                dao,
                source(lastNotificationAt = recent),
                at = threeDaysEarlier,
            ),
        )
        assertEquals(emptyList<String>(), dao.calls)
    }

    @Test
    fun `the only mutation is the liveness column`() {
        val dao = RecordingDao(source(lastNotificationAt = null))

        SourceLiveness.record(dao, source(null), at = 5_000_000L)

        val forbidden = listOf("insertIfNew", "setEnabled", "setAuthoritative", "setLabel")
        val touched = dao.calls.map { it.substringBefore('(') }
        assertEquals(
            "Liveness must write one column. `enabled` is the user's allow-list " +
                "consent and `capture_source` has no upsert precisely so that a " +
                "partial write cannot reach it. Calls were: ${dao.calls}",
            emptyList<String>(),
            touched.filter { it in forbidden },
        )
        assertEquals(listOf("setLastNotificationAt"), touched)
    }

    @Test
    fun `the per-source window matches the global heartbeat`() {
        // Two windows answering the same question at different granularities. A
        // per-source window narrower than the global one would be an odd claim
        // to have to defend, and drift between the two would be invisible.
        assertEquals(CaptureHealth.THROTTLE_MILLIS, SourceLiveness.THROTTLE_MILLIS)
    }
}
