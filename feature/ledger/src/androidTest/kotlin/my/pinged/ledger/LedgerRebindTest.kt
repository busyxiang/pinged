package my.pinged.ledger

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.ledger.home.LedgerScreen
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.settings.SettingsViewModel
import my.pinged.ledger.settings.Transfers
import my.pinged.ledger.settings.TransferJob
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.PingedTheme
import my.pinged.ledger.transfer.exportBytes
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ledger after spec 11.3's delete and spec 11.2's restore, both of which
 * close the shared `PingedDatabase` and replace it underneath a live
 * [LedgerViewModel].
 *
 * **What these catch is a feed bound to the closed instance.** Its cached
 * pages go on drawing rows the database no longer holds, `retry()` has
 * nothing failed to retry, and Room's invalidation tracker on the new
 * instance never reaches a `PagingSource` built on the old one -- so a row
 * inserted afterwards never appears either.
 *
 * All but one leave the screen composed and resumed throughout, because the
 * rebind must not wait for a resume the user never makes. The exception takes
 * the screen out of composition across the restore, which is where
 * `MainActivity`'s back stack leaves it while settings is on top.
 *
 * Driven through the real [SettingsViewModel], so the reset is the one the
 * app performs, in the order it performs it.
 */
@RunWith(AndroidJUnit4::class)
class LedgerRebindTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application

    @After fun leaveAnOpenableDatabase() {
        context.discardTheDatabase()
        // One process runs every class, and a restore's outcome is kept
        // until a screen acknowledges it -- a later class's settings screen
        // would open under its sheet.
        Transfers.forgetOutcome()
    }

    @Test fun aRestoreRedrawsTheLedgerOnScreenAndKeepsItLive() {
        val backup = aBackupHolding(RESTORED, amountSen = 1_234L, categoryName = RESTORED_CATEGORY)
        val viewModel = aLedgerShowingOneRow()
        compose.setContent {
            PingedTheme { LedgerScreen(viewModel = viewModel, onOpenSettings = {}) }
        }
        awaitTheOldRow()

        restore(backup)

        assertTrue(
            "The restore finished and the ledger on screen still draws " +
                "${count(MERCHANT)} row(s) of the ledger it replaced and " +
                "${count(RESTORED)} of the restored one, while the database " +
                "holds ${Databases.txnDao(context).countAll()} row(s). The feed " +
                "is still bound to the database the restore closed",
            waitFor { count(RESTORED) == 1 && count(MERCHANT) == 0 },
        )
        assertTrue(
            "The restored row is drawn under a month total that is not its " +
                "own: the aggregates were not re-read after the reset. " +
                "RM50.00 drawn ${count("RM50.00")} time(s)",
            waitFor { count("RM12.34") == 1 },
        )
        assertTrue(
            "The restored row is drawn under the category name the replaced " +
                "database held, not \"$RESTORED_CATEGORY\" from the backup: the " +
                "category catalogue was read from the closed database and kept",
            waitFor { count(RESTORED_CATEGORY, substring = true) > 0 },
        )

        insertNow(AFTERWARDS, 999L)
        assertTrue(
            "A row inserted into the restored database never reached the " +
                "screen. The feed is not observing the database that is open",
            waitFor { count(AFTERWARDS) == 1 },
        )
    }

    @Test fun aDeleteEmptiesTheLedgerOnScreenAndKeepsItLive() {
        val viewModel = aLedgerShowingOneRow()
        compose.setContent {
            PingedTheme { LedgerScreen(viewModel = viewModel, onOpenSettings = {}) }
        }
        awaitTheOldRow()

        val settings = SettingsViewModel(app)
        runBlocking { settings.deleteEverything().join() }
        assertTrue(
            "The delete itself failed, so nothing below is about the ledger: " +
                "${settings.state.value.job}",
            settings.state.value.job !is TransferJob.Failed,
        )

        assertTrue(
            "Delete everything finished and the ledger on screen still draws " +
                "${count(MERCHANT)} deleted row(s), while the database holds " +
                "${Databases.txnDao(context).countAll()}. The feed is still " +
                "bound to the database the delete closed",
            waitFor { count(EMPTY) == 1 && count(MERCHANT) == 0 },
        )
        assertTrue(
            "The feed is empty and the holder still carries the deleted " +
                "ledger's month totals, ${viewModel.read.value.summary?.totals}: " +
                "the aggregates were not re-read after the reset",
            waitFor { viewModel.read.value.summary?.totals?.isEmpty() == true },
        )

        insertNow(AFTERWARDS, 999L)
        assertTrue(
            "A row inserted into the new database never reached the screen. " +
                "The feed is not observing the database that is open",
            waitFor { count(AFTERWARDS) == 1 },
        )
    }

    @Test fun aRestoreWhileTheLedgerIsAwayIsDrawnWhenItComesBack() {
        val backup = aBackupHolding(RESTORED, amountSen = 1_234L, categoryName = RESTORED_CATEGORY)
        val viewModel = aLedgerShowingOneRow()
        var shown by mutableStateOf(true)
        compose.setContent {
            PingedTheme { if (shown) LedgerScreen(viewModel = viewModel, onOpenSettings = {}) }
        }
        awaitTheOldRow()

        // The holder outlives its screen, as it does under settings on the
        // back stack.
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        restore(backup)
        compose.runOnIdle { shown = true }

        assertTrue(
            "The ledger came back after a restore it was away for and drew " +
                "${count(MERCHANT)} row(s) of the ledger that restore replaced " +
                "and ${count(RESTORED)} of the restored one",
            waitFor { count(RESTORED) == 1 && count(MERCHANT) == 0 },
        )
        assertTrue(
            "The restored row is drawn under the category name the replaced " +
                "database held, not \"$RESTORED_CATEGORY\" from the backup",
            waitFor { count(RESTORED_CATEGORY, substring = true) > 0 },
        )
        insertNow(AFTERWARDS, 999L)
        assertTrue(
            "A row inserted into the restored database never reached the screen",
            waitFor { count(AFTERWARDS) == 1 },
        )
    }

    /**
     * The feed's first open finishes after a reset it straddled: the DAO it
     * got belongs to the instance that reset closed. Bound, it would be bound
     * for good -- the reset has already been announced, so nothing tells the
     * holder again -- and the screen would read CANNOT READ YOUR DATA over a
     * database that opens.
     */
    @Test fun anOpenThatStraddlesAResetDoesNotBindTheClosedDatabase() {
        context.freshLedger()
        val viewModel = LedgerViewModel(app)
        val opened = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        viewModel.afterOpen = {
            if (opened.complete(Unit)) release.await()
        }
        compose.setContent {
            PingedTheme { LedgerScreen(viewModel = viewModel, onOpenSettings = {}) }
        }
        runBlocking { withTimeout(TIMEOUT) { opened.await() } }

        Databases.reset()
        insertNow(AFTERWARDS, 999L)
        release.complete(Unit)

        assertTrue(
            "The feed bound the database a reset closed while it was opening " +
                "it, and draws ${count(AFTERWARDS)} of the row in the database " +
                "that replaced it (unreadable copy drawn " +
                "${count(CANNOT_READ_YOUR_DATA)} time(s))",
            waitFor { count(AFTERWARDS) == 1 },
        )
    }

    /**
     * An export of a ledger holding one row, [merchant], filed under a
     * category renamed to [categoryName] -- renamed so that a catalogue read
     * from the database the restore replaced is visible on screen.
     */
    private fun aBackupHolding(merchant: String, amountSen: Long, categoryName: String): ByteArray {
        context.freshLedger()
        val category = aNamedCategory()
        Databases.shared(context).openHelper.writableDatabase.execSQL(
            "UPDATE category SET name = ? WHERE id = ?",
            arrayOf<Any>(categoryName, category),
        )
        insertNow(merchant, amountSen, category)
        return exportBytes(Databases.shared(context))
    }

    /** The holder, over a fresh ledger of one [MERCHANT] row for RM50.00. */
    private fun aLedgerShowingOneRow(): LedgerViewModel {
        context.freshLedger()
        insertNow(MERCHANT, 5_000L)
        return LedgerViewModel(app)
    }

    private fun awaitTheOldRow() = assertTrue(
        "The fixture never reached the screen, so nothing below is about " +
            "what a reset does to it",
        waitFor { count(MERCHANT) == 1 && count("RM50.00") == 1 },
    )

    private fun restore(backup: ByteArray) {
        val settings = SettingsViewModel(app)
        runBlocking { settings.restoreFrom { ByteArrayInputStream(backup) }.join() }
        assertTrue(
            "The restore itself failed, so nothing below is about the ledger: " +
                "${settings.state.value.job}",
            settings.state.value.job is TransferJob.Restored,
        )
    }

    /**
     * Not Uncategorized, whose name the row drops, so the category's name is
     * on screen to be read.
     */
    private fun aNamedCategory(): Long {
        val categories = Databases.categoryDao(context)
        val uncategorized = categories.uncategorizedIdOrNull()
        return categories.all().first { it.id != uncategorized }.id
    }

    private fun insertNow(merchant: String, amountSen: Long, category: Long = aNamedCategory()) {
        Databases.txnDao(context).insert(
            ledgerTxn(
                amountSen = amountSen,
                occurredAt = System.currentTimeMillis(),
                categoryId = category,
            ).copy(merchantDisplay = merchant),
        )
    }

    private fun count(text: String, substring: Boolean = false): Int =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

    private fun waitFor(condition: () -> Boolean): Boolean =
        runCatching { compose.waitUntil(TIMEOUT) { condition() } }.isSuccess

    private companion object {
        const val RESTORED = "Kedai Restored"
        const val RESTORED_CATEGORY = "Restored Category"
        const val AFTERWARDS = "Gerai Afterwards"
        const val EMPTY = "NOTHING CAPTURED YET"
    }
}
