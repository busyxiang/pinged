package my.pinged.ledger

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import my.pinged.data.Databases
import my.pinged.ledger.categories.CategoriesScreen
import my.pinged.ledger.categories.CategoriesViewModel
import my.pinged.ledger.corrections.CorrectionsScreen
import my.pinged.ledger.corrections.CorrectionsViewModel
import my.pinged.ledger.learned.LearnedScreen
import my.pinged.ledger.learned.LearnedViewModel
import my.pinged.ledger.review.ReviewScreen
import my.pinged.ledger.review.ReviewViewModel
import my.pinged.ledger.unread.UnreadScreen
import my.pinged.ledger.unread.UnreadViewModel
import my.pinged.read.test.ScriptedRunner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A restore or a delete-everything that lands while a screen is showing makes
 * it read again, with no resume (#300, #296 decision 7): `Databases.reset`
 * moves `rewrites`, the one signal both of them end in, and the screen's own
 * `rememberReadState` is what hears it. Before #300 Learned, Unread,
 * Categories and Corrections read on resume only, and showed rules, captures
 * and categories of the ledger that was replaced until the next one.
 *
 * Each screen is the real composable over its real holder, through
 * `ScriptedRunner`, which counts what the holder asked of the ledger. The
 * compose rule's activity is resumed, so the first composition is a resume
 * and is the one read counted before the rewrite.
 */
@RunWith(RobolectricTestRunner::class)
class RewriteRereadTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)

    /** Rewrite the ledger as a restore or a delete-everything does, and let what that triggers run. */
    private fun replaceTheLedger() {
        Databases.reset()
        compose.waitForIdle()
    }

    /** The reads the holder asked of [runner] under [what]: not its writes, which a screen never starts by itself. */
    private fun reads(what: String): Int = runner.calls.count { !it.write && it.what == what }

    @Test fun thingsYouTaughtReadsAgainWhenTheLedgerIsReplaced() {
        val viewModel = LearnedViewModel(app, runner)
        compose.setContent { LearnedScreen(viewModel, onBack = {}) }
        compose.waitForIdle()
        assertEquals("Learned was not read once when shown", 1, reads(LEARNED))

        replaceTheLedger()

        assertEquals("Learned did not read again, or read twice, when the ledger was replaced", 2, reads(LEARNED))
    }

    @Test fun couldNotReadReadsAgainWhenTheLedgerIsReplaced() {
        val viewModel = UnreadViewModel(app, runner)
        compose.setContent { UnreadScreen(viewModel, onBack = {}, onTeach = {}) }
        compose.waitForIdle()
        assertEquals("Unread was not read once when shown", 1, reads(UNREAD))

        replaceTheLedger()

        assertEquals("Unread did not read again, or read twice, when the ledger was replaced", 2, reads(UNREAD))
    }

    @Test fun categoriesReadsAgainWhenTheLedgerIsReplaced() {
        val viewModel = CategoriesViewModel(app, runner)
        compose.setContent { CategoriesScreen(viewModel, onBack = {}) }
        compose.waitForIdle()
        assertEquals("Categories was not read once when shown", 1, reads(CATEGORIES))

        replaceTheLedger()

        assertEquals("Categories did not read again, or read twice, when the ledger was replaced", 2, reads(CATEGORIES))
    }

    @Test fun correctionsReadsAgainWhenTheLedgerIsReplaced() {
        val viewModel = CorrectionsViewModel(app, runner, Dispatchers.Unconfined)
        compose.setContent { CorrectionsScreen(viewModel, onBack = {}) }
        compose.waitForIdle()
        assertEquals("Corrections was not read once when shown", 1, reads(CORRECTIONS))

        replaceTheLedger()

        assertEquals("Corrections did not read again, or read twice, when the ledger was replaced", 2, reads(CORRECTIONS))
    }

    @Test fun theReviewInboxReadsAgainWhenTheLedgerIsReplaced() {
        val viewModel = ReviewViewModel(app, runner)
        compose.setContent { ReviewScreen(viewModel, onBack = {}) }
        compose.waitForIdle()
        assertEquals("The inbox was not read once when shown", 1, reads(INBOX))

        replaceTheLedger()

        assertEquals("The inbox did not read again, or read twice, when the ledger was replaced", 2, reads(INBOX))
    }

    private companion object {
        const val LEARNED = "Learned merchants cannot be read"
        const val UNREAD = "The notifications Pinged could not read cannot be read"
        const val CATEGORIES = "Categories cannot be read"
        const val CORRECTIONS = "Corrections cannot be read"
        const val INBOX = "The review inbox cannot be read"
    }
}
