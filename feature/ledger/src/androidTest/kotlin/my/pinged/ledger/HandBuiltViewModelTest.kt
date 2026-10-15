package my.pinged.ledger

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.ledger.entry.BackdatedEntryTest
import my.pinged.ledger.entry.EntryGateTest
import my.pinged.ledger.entry.EntryTodayTest
import my.pinged.ledger.entry.ManualEditViewModelTest
import my.pinged.ledger.entry.NearbyWarningViewModelTest
import my.pinged.ledger.entry.StaleCategoryTest
import my.pinged.ledger.settings.HostileApp
import my.pinged.ledger.settings.SettingsViewModel
import my.pinged.ledger.settings.Transfers
import my.pinged.ledger.transfer.useDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith

/**
 * **No class that builds a `ManualEntryViewModel` by hand leaves it running
 * (#181).**
 *
 * Nothing clears a view model built outside a `ViewModelStore`, and this one's
 * `ManualEntries` ranks the categories again whenever a restore or delete
 * ends, which opens `Databases.shared` and so re-creates a database a later
 * class has just deleted. `SettingsStateTest`'s assertion that a failed delete
 * left no file failed on CI that way, a few milliseconds after the delete.
 *
 * Each class below is run here, in this process as the suite runs it, and then
 * the ledger is deleted the way that test deletes it: with a failure after the
 * wipe, so the settings holder does not read it back itself. A class that
 * builds the view model by hand belongs in [CLASSES].
 *
 * **Falsified by removing the `viewModels` cancel from any one class's
 * `@After`**: its view model re-creates the file inside [REOPEN_MS], and the
 * assertion below fails.
 */
@RunWith(AndroidJUnit4::class)
class HandBuiltViewModelTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @After fun leaveNothingBehind() {
        Transfers.forgetOutcome()
        app.discardTheDatabase()
    }

    @Test fun noClassLeavesAViewModelThatReopensADeletedLedger() = runBlocking {
        val run = JUnitCore().run(*CLASSES)
        assertTrue("The classes did not run", run.runCount > 0)

        DatabaseFactory.build(app).useDb { }
        Databases.reset()
        SettingsViewModel(HostileApp(app).apply { refusing = true }).deleteEverything().join()
        delay(REOPEN_MS)

        assertEquals(
            "A view model one of these classes left running re-created the database after the delete",
            false,
            app.getDatabasePath(DatabaseFactory.NAME).exists(),
        )
    }

    private companion object {
        val CLASSES = arrayOf(
            BackdatedEntryTest::class.java,
            EntryGateTest::class.java,
            EntryTodayTest::class.java,
            ManualEditViewModelTest::class.java,
            NearbyWarningViewModelTest::class.java,
            StaleCategoryTest::class.java,
        )

        /**
         * A leaked view model opened the database 1-5ms after the delete
         * published its outcome, on CI and on a local emulator at CI's size;
         * 300ms caught it in 5 runs of 5 there and in 17 of 18 with a single
         * class.
         */
        const val REOPEN_MS = 300L
    }
}
