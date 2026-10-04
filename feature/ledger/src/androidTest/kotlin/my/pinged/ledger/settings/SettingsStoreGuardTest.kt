package my.pinged.ledger.settings

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.ledger.transfer.TransferStore
import my.pinged.ledger.transfer.bulkCapture
import my.pinged.ledger.transfer.useDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What settings' read does with a preference it cannot read.
 *
 * Settings is where the user goes when something is wrong, and its reads run
 * on `viewModelScope` with no handler: a throw from one crashes the screen on
 * every arrival. So the subject is that nothing escapes, and that a value
 * nobody could read is drawn as nothing rather than as a default that makes
 * a claim.
 *
 * **The failures are handed in** through [SettingsViewModel.stored], for
 * `ExportNudgeGuardTest`'s reason: DataStore caches its first successful
 * read, so a file corrupted from here would never be read again.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreGuardTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application

    @Before fun startClean() = runBlocking {
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
        TransferStore.forget(app)
        IntegrityStore.forget(app)
        DatabaseFactory.build(app).useDb { it.rawCaptureDao().insert(bulkCapture(0)) }
        TransferStore.recordExport(app, at = EXPORTED_AT)
        IntegrityStore.record(app, at = CHECKED_AT, ok = true)
    }

    @Test fun anUnreadableVerdictIsNotACrashAndClaimsNothing() = runBlocking {
        val model = SettingsViewModel(app)
        model.stored = object : StoredSettings by StoredSettings.OnDisk {
            override suspend fun damaged(context: Context): Boolean = throw unreadable()
            override suspend fun lastCheckAt(context: Context): Long = throw unreadable()
        }

        val outcome = runCatching { model.refresh() }

        assertNull(
            "An unreadable integrity store threw out of settings' read. Nothing above it " +
                "catches this, so the screen crashes every time it opens",
            outcome.exceptionOrNull(),
        )
        val state = model.state.value
        assertEquals(
            "An unreadable verdict filed as something a check found, with nothing behind it",
            Storage.HEALTHY,
            state.storage,
        )
        assertNull(
            "The delete sheet itemises counts from a database whose verdict could not be read",
            state.wipeCounts,
        )
        assertNull("The check row claims a time nobody could read", state.lastCheckAt)
        assertEquals("The last export, in a store that did read, was lost too", EXPORTED_AT, state.lastExportAt)
    }

    @Test fun anUnreadableLastExportIsNotACrashAndIsDrawnAsNothing() = runBlocking {
        val model = SettingsViewModel(app)
        model.stored = object : StoredSettings by StoredSettings.OnDisk {
            override suspend fun lastExportAt(context: Context): Long = throw unreadable()
        }

        val outcome = runCatching { model.refresh() }

        assertNull(
            "An unreadable transfer store threw out of settings' read. Nothing above it " +
                "catches this, so the screen crashes every time it opens",
            outcome.exceptionOrNull(),
        )
        val state = model.state.value
        assertNull("The export row claims a time nobody could read", state.lastExportAt)
        assertNotNull("A readable verdict no longer licenses the counts", state.wipeCounts)
        assertEquals("The last check, in a store that did read, was lost too", CHECKED_AT, state.lastCheckAt)
    }

    /** What DataStore raises for a truncated file (`CorruptionException`) or a disk that will not read. */
    private fun unreadable() = IOException("simulated unreadable preferences file")

    private companion object {
        const val EXPORTED_AT = 1_000L
        const val CHECKED_AT = 2_000L
    }
}
