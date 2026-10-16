package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 5.6's unread list and 5.7's "never a transaction", against the real
 * encrypted database.
 */
@RunWith(AndroidJUnit4::class)
class UserRejectRuleTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private val rejects get() = db.userRejectRuleDao()
    private val captures get() = db.rawCaptureDao()

    private var next = 0

    private fun capture(
        text: String,
        pkg: String = MAE,
        postedAt: Long = 1_000L + next,
        status: ParseStatus = ParseStatus.UNMATCHED,
    ): Long {
        next++
        return captures.insert(sampleCapture(hash = "h$next", postedAt = postedAt, pkg = pkg, text = text, status = status))
    }

    private fun unread() = rejects.unreadPage(Long.MAX_VALUE, Long.MAX_VALUE, 100).map { it.id }

    @Test fun theUnreadListIsUnmatchedCapturesNewestFirst() {
        val older = capture("Your FD of RM5,000.00 has matured.", postedAt = 1_000L)
        val newer = capture("You spent RM23.50 this week.", postedAt = 2_000L)
        capture("Payment of RM1.00 to A successful", status = ParseStatus.MATCHED)
        capture("RM1.00 promo", status = ParseStatus.REJECTED)

        assertEquals(listOf(newer, older), unread())
        assertEquals(2, rejects.unreadCount())
    }

    @Test fun theListPagesByKeysetWithoutRepeatingOrSkipping() {
        val ids = (1..5).map { capture("Notice $it", postedAt = 1_000L) }
        val first = rejects.unreadPage(Long.MAX_VALUE, Long.MAX_VALUE, 2)
        val last = first.last()
        val rest = rejects.unreadPage(last.postedAt, last.id, 10)
        assertEquals(ids.reversed(), (first + rest).map { it.id })
    }

    /** Spec 5.7's worked example: the amount changes, the shape does not. */
    @Test fun neverATransactionHidesEveryCaptureOfTheSameShapeFromTheSameApp() {
        val tapped = capture("Your FD of RM5,000.00 has matured. View details in MAE.")
        val sameShape = capture("Your FD of RM12,345.67 has matured. View details in MAE.")
        val otherShape = capture("Your FD of RM5,000.00 has been renewed. View details in MAE.")
        val otherApp = capture("Your FD of RM5,000.00 has matured. View details in MAE.", pkg = OTHER)

        assertEquals(2, rejects.neverATransaction(tapped, now = 9_000L))

        assertEquals(listOf(otherApp, otherShape), unread())
        val rule = rejects.rules().single()
        assertEquals(MAE, rule.sourcePackage)
        assertEquals(2, rule.hidden)
        assertEquals("Touch 'n Go eWallet\nYour FD of RM5,000.00 has matured. View details in MAE.", rule.sampleText)
        assertEquals(ParseStatus.UNMATCHED, captures.byId(sameShape).parseStatus)
    }

    /** Stage two's half: a later capture of a dismissed shape is born hidden. */
    @Test fun aLaterCaptureOfADismissedShapeIsMarkedHiddenByStageTwo() {
        rejects.neverATransaction(capture("Your FD of RM5,000.00 has matured."), now = 9_000L)
        val later = captures.insert(sampleCapture(hash = "later", pkg = MAE, text = "Your FD of RM80.00 has matured."))
        val elsewhere = captures.insert(sampleCapture(hash = "elsewhere", pkg = OTHER, text = "Your FD of RM80.00 has matured."))

        markUnmatched(later)
        markUnmatched(elsewhere)

        assertEquals(listOf(elsewhere), unread())
        assertEquals(2, rejects.rules().single().hidden)
    }

    /** Spec 5.7: deleting a rule returns its captures to the list. */
    @Test fun deletingARuleReturnsItsCaptures() {
        val a = capture("Statement ready for 0000.")
        val b = capture("Statement ready for 1111.")
        rejects.neverATransaction(a, now = 9_000L)
        assertEquals(emptyList<Long>(), unread())

        rejects.delete(rejects.rules().single().id)

        assertEquals(listOf(b, a), unread())
        assertEquals(0, rejects.countAll())
        assertEquals(null, captures.byId(a).userRejectRuleId)
    }

    /**
     * A requeued hidden capture -- spec 5.5's re-parse after a pack upgrade --
     * that is still unmatched stays hidden, and one the new pack matches
     * leaves the list for the ledger, the rule notwithstanding.
     */
    @Test fun aRequeuedHiddenCaptureKeepsItsRuleWhetherOrNotItMatches() {
        val stillUnread = capture("Your FD of RM5,000.00 has matured.")
        val nowMatched = capture("Your FD of RM6,000.00 has matured.")
        rejects.neverATransaction(stillUnread, now = 9_000L)
        captures.requeueStale(ParseStatus.REVISITABLE, packVersion = 99, limit = 10, to = ParseStatus.NEW)

        markUnmatched(stillUnread)
        captures.markOutcome(nowMatched, ParseStatus.MATCHED, ruleId = "mae.fd")

        assertEquals(emptyList<Long>(), unread())
        assertEquals(1, rejects.rules().single().hidden)
    }

    @Test fun aSecondTapOnAHiddenCaptureChangesNothing() {
        val a = capture("Statement ready.")
        rejects.neverATransaction(a, now = 9_000L)
        assertEquals(0, rejects.neverATransaction(a, now = 10_000L))
        assertEquals(1, rejects.countAll())
    }

    private fun markUnmatched(id: Long) {
        val c: RawCapture = captures.byId(id)
        captures.markUnmatched(c, my.pinged.parse.Skeleton.ofCapture(c.title, c.text, c.bigText), null, 1)
    }

    private companion object {
        const val MAE = "com.maybank2u.life"
        const val OTHER = "my.com.cimb.clicks"
    }
}
