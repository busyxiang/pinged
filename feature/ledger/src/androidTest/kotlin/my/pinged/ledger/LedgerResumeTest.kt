package my.pinged.ledger

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.Databases
import my.pinged.ledger.home.LedgerScreen
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ledger.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A payment captured while the ledger was in the background, on the foreground
 * the user comes back to.
 *
 * The listener runs while no Activity does -- that is the design -- so this is
 * the ordinary case, not an edge one. **The subject is the aggregate, not the
 * row.** Room invalidates the `PagingSource` on the insert, so the row appears
 * whatever the screen does about lifecycles; the day subtotal and the month
 * total come from three separate queries that only `refresh()` issues. A screen
 * reading them from a `LaunchedEffect(viewModel)` reads them exactly once for
 * the life of the holder -- and `MainActivity` scopes the holder to its
 * `NavEntry`, so it survives backgrounding -- which draws the new row above a
 * total that does not count it.
 *
 * `createAndroidComposeRule`, not `createComposeRule`, for the reason
 * `SourcesResumeTest` records: only a real `ActivityScenario` transition drives
 * the `Lifecycle` the effect observes. It does not reproduce the process being
 * killed while away, which recreates the holder and reads correctly whatever
 * the effect is keyed on.
 */
@RunWith(AndroidJUnit4::class)
class LedgerResumeTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * [aLedgerThatCouldNotBeReadIsReadOnTheNextForeground] overwrites the
     * file's first page; see [discardTheDatabase] for what the rest of this
     * APK depends on afterwards.
     */
    @After fun leaveAnOpenableDatabase() = context.discardTheDatabase()

    @Test fun aPaymentCapturedWhileTheScreenWasAwayIsCountedOnResume() {
        val dao = context.freshLedger()
        val category = Databases.categoryDao(context).all().first().id

        val viewModel = LedgerViewModel(context.applicationContext as Application)
        compose.setContent {
            PingedTheme { LedgerScreen(viewModel = viewModel, onOpenSources = {}) }
        }
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithTextSafely("NOTHING CAPTURED YET") > 0
        }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        val now = System.currentTimeMillis()
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = now, categoryId = category))

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        val counted = runCatching {
            compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("RM50.00") > 0 }
        }.isSuccess
        assertEquals(
            "The payment is in the database and the month total does not count " +
                "it, so this foreground performed no aggregate read. The row " +
                "arrives on its own -- the PagingSource is invalidated by the " +
                "insert -- which is what makes the missing total look like an " +
                "arithmetic bug rather than a read that never happened",
            true,
            counted,
        )
    }

    /**
     * A storage failure that has passed does not outlive the holder.
     *
     * §11.1's *permanent* case behaves correctly either way -- everything
     * stays broken, which is the truth. This is the transient member of the
     * same family, the one `CaptureStorage.guarded`'s own documentation names:
     * a full disk, an IO error. `refresh()` retries the open on every
     * foreground and `MainActivity.onResume` re-probes for the banner, so both
     * of those recover on their own; a feed opened once for the life of the
     * holder does not, and `loadState.refresh` then stays an error with the
     * body reading [CANNOT_READ_YOUR_DATA] under a banner that has cleared.
     *
     * The failure is produced permanently and then repaired, because the point
     * is not how the disk filled up -- it is that nothing on this screen asks
     * Paging to try again unless the resume effect does.
     */
    @Test fun aLedgerThatCouldNotBeReadIsReadOnTheNextForeground() {
        context.corruptTheDatabase()

        val viewModel = LedgerViewModel(context.applicationContext as Application)
        compose.setContent {
            PingedTheme { LedgerScreen(viewModel = viewModel, onOpenSources = {}) }
        }
        // Wrapped for the same reason the recovery wait below is, and it is
        // the precondition rather than the subject: a bare `waitUntil` fails
        // as an unattributed timeout, and if the corrupted first page never
        // reaches the screen there is no failure for the resume to recover
        // from and everything after this point passes vacuously.
        val unreadable = runCatching {
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTextSafely(CANNOT_READ_YOUR_DATA) > 0
            }
        }.isSuccess
        assertEquals(
            "The ledger read a database whose first page was overwritten with " +
                "0x5A without saying so, so this test has no storage failure " +
                "to recover from and proves nothing about the resume",
            true,
            unreadable,
        )

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        // The failure passes. A replaced file is the only repair available
        // here; what it stands for is a disk that stopped being full.
        val dao = context.freshLedger()
        val category = Databases.categoryDao(context).all().first().id
        val now = System.currentTimeMillis()
        dao.insert(ledgerTxn(amountSen = 7_700L, occurredAt = now, categoryId = category))

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        val recovered = runCatching {
            compose.waitUntil(TIMEOUT) { compose.onAllNodesWithTextSafely("77.00") > 0 }
        }.isSuccess
        assertEquals(
            "The ledger still cannot be read on a foreground where the " +
                "database opens. refresh() and MainActivity's probe both " +
                "recovered, so the banner above this body says capture is " +
                "fine while the body says the data cannot be read",
            true,
            recovered,
        )
        assertEquals(
            "The unreadable copy is still on screen beside the rows it says " +
                "could not be read",
            0,
            compose.onAllNodesWithTextSafely(CANNOT_READ_YOUR_DATA),
        )
    }
}
