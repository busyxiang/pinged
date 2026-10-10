package my.pinged.ledger

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.capture.CaptureStorage
import my.pinged.capture.ListenerStatus
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.ledger.home.LedgerScreen
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.settings.Transfers
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The feed on a ledger with one damaged page; `DamagedLedgerTest` has the rest. */
@RunWith(AndroidJUnit4::class)
class DamagedFeedTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application

    @Before fun startClean() = runBlocking<Unit> {
        Transfers.forgetOutcome()
        IntegrityStore.forget(context)
        context.discardTheDatabase()
    }

    @After fun leaveAnOpenableDatabase() = runBlocking<Unit> {
        context.discardTheDatabase()
        IntegrityStore.forget(context)
        Transfers.forgetOutcome()
    }

    /**
     * **A connection replaced for damage is a replacement, and the feed
     * follows it.** The feed holds a `PagingSource` of the instance it opened;
     * closed underneath it, that source answers "connection is closed" for
     * good, so the feed has to hear of the replacement as it hears of a
     * restore's.
     */
    @Test(timeout = 60_000)
    fun theFeedFollowsAConnectionReplacedForDamage() {
        context.damagedLedger()
        Databases.txnDao(context).insert(ledgerTxn(amountSen = 5_000L, occurredAt = System.currentTimeMillis()).copy(merchantDisplay = BEFORE))
        val viewModel = LedgerViewModel(app)
        compose.setContent { PingedTheme { LedgerScreen(viewModel = viewModel, onOpenSettings = {}) } }
        assertTrue("the fixture row never reached the screen", waitFor { count(BEFORE) == 1 })
        context.poisonTheSharedInstance()

        // What the next notification does: a guarded block meets code 26.
        val probed = runCatching {
            runBlocking { CaptureStorage.guarded(context, "a capture, for the test", { false }) { Databases.captureSourceDao(context).all(); true } }
        }
        assertEquals("the guarded read did not get through: $probed", true, probed.getOrNull())
        Databases.txnDao(context).insert(ledgerTxn(amountSen = 7_000L, occurredAt = System.currentTimeMillis()).copy(merchantDisplay = AFTER))

        assertTrue(
            "the feed stayed on the connection replaced for damage and does not draw the row written since",
            waitFor { count(AFTER) == 1 },
        )
    }

    /**
     * **Replacing a connection for damage re-reads nothing.** The screen
     * re-reads its month when the database is rewritten; were it to re-read
     * on every replacement, a month whose own pages are damaged would read,
     * meet the damage, replace the connection, and read again, for as long
     * as the screen was up. Measured over a quiet two seconds once the
     * screen has settled: the connection is replaced no more.
     *
     * The damage is a `txn` leaf of this month's, which the month's figures
     * read, and a refusal there is the screen's to draw: the capture flag
     * stays down.
     */
    @Test(timeout = 60_000)
    fun aMonthOnDamagedPagesDoesNotReadItselfInALoop() = runBlocking<Unit> {
        context.damagedMonth()
        val viewModel = LedgerViewModel(app)
        // A view set on the activity, and frames advanced by hand, rather than
        // the rule's `setContent` and `waitForIdle`: both wait for an idle
        // that a screen reading itself in a loop never reaches, and would fail
        // on whatever the loop threw first rather than on the loop. Without
        // frames nothing recomposes, and a loop through recomposition cannot
        // happen at all.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            compose.activity.setContentView(
                ComposeView(compose.activity).apply {
                    setContent { PingedTheme { LedgerScreen(viewModel = viewModel, onOpenSettings = {}) } }
                },
            )
        }
        runFrames(millis = 1_500)

        val settled = Databases.generation.value
        runFrames(millis = 2_000)
        val after = Databases.generation.value

        assertEquals("the screen went on replacing the connection it read through", settled, after)
        assertEquals(
            "damage under the month's figures put the capture banner up",
            false,
            ListenerStatus.report(context).storageUnavailable,
        )
    }

    /** Frames for [millis] of wall time, each given time for the IO work it starts. */
    private fun runFrames(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(16)
        }
    }

    private fun count(text: String): Int = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun waitFor(condition: () -> Boolean): Boolean =
        runCatching { compose.waitUntil(10_000) { condition() } }.isSuccess

    private companion object {
        const val BEFORE = "Kedai Before"
        const val AFTER = "Gerai After"
    }
}
