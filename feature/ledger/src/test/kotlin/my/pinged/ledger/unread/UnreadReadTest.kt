package my.pinged.ledger.unread

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.read.test.ScriptedRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the unread list asks of `:core:read` (#300, #296): its question is how
 * many cards to show, so a read of any trigger is at least as long as what is
 * on screen, and a page more is one read in the turn rather than two at once.
 * Through `ScriptedRunner` over an in-memory ledger, which holds each call
 * until the test lets it go. The holder's scope is `viewModelScope`, whose
 * `Main.immediate` is Robolectric's main thread, the test's own.
 */
@RunWith(RobolectricTestRunner::class)
class UnreadReadTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)
    private val model = UnreadViewModel(app, runner)

    /** [count] unmatched captures, each its own shape, newest last. */
    private fun seed(count: Int) {
        runBlocking {
            runner.read("The test cannot be seeded") { db ->
                for (i in 1..count) db.rawCaptureDao().insert(unmatched(i))
            }
        }
    }

    private fun shown(): Int = model.read.state.value.answer?.items?.size ?: 0

    @Test fun aReadAfterAPageMoreIsAsLongAsWhatIsShowingAndDoesNotShortenTheList() {
        seed(60)
        model.read.refresh()
        assertEquals("The first read was not a page", 50, shown())

        model.loadMore()
        assertEquals("A page more did not append", 60, shown())
        // A resume, or a restore: the triggers every screen has.
        model.read.refresh()

        assertEquals("A re-read after a page more shortened the list to a page", 60, shown())
    }

    @Test fun aPageMoreAskedForTwiceBeforeItLandsIsOnePage() {
        seed(120)
        model.read.refresh()
        runner.holding = true
        val before = runner.calls.size

        model.loadMore()
        model.loadMore()

        // The turn would hold a second read behind the first, so the call count
        // cannot tell: the question can, for a second ask moves it past the page
        // the first was for, which then lands nothing.
        assertEquals("A second page more moved the question past one page", 100, model.read.question.value)
        runner.calls[before].release()
        assertEquals("The page more asked for did not land", 100, shown())
    }

    @Test fun noPageMoreIsAskedForOnceTheLastReadCameBackShort() {
        seed(10)
        model.read.refresh()
        val before = runner.calls.size

        model.loadMore()

        assertFalse("A short list is claimed to have more", model.read.state.value.answer!!.more)
        assertEquals("A page more was read past the end of the list", before, runner.calls.size)
        assertTrue(model.read.state.value.answered?.question == model.read.question.value)
    }

    private fun unmatched(index: Int) = RawCapture(
        sourcePackage = "my.com.tngdigital.ewallet",
        postedAt = 1_600_000_000_000L + index,
        whenMillis = null,
        capturedAt = 1_600_000_000_000L + index,
        sbnKey = "0|my.com.tngdigital.ewallet|$index|null|0",
        notifId = index,
        notifTag = null,
        userHandle = 0,
        channelId = "txn",
        flags = 0,
        arrival = Arrival.POSTED,
        title = "Touch 'n Go eWallet",
        text = "Payment of RM1.00 to merchant number $index successful",
        bigText = null,
        subText = null,
        extrasJson = null,
        contentHash = "unread-$index",
        parseStatus = ParseStatus.UNMATCHED,
    )
}
