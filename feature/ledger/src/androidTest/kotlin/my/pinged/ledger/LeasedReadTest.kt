package my.pinged.ledger

import android.app.Application
import android.content.Context
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.pinged.capture.CaptureStorage
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.dao.FeedRow
import my.pinged.ledger.home.LeasedFeed
import my.pinged.ledger.settings.SettingsViewModel
import my.pinged.ledger.settings.StoredSettings
import my.pinged.ledger.settings.Transfers
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens' reads that run outside `CaptureStorage.guarded` keep the
 * instance they read through open while another caller replaces it for
 * damage. A replaced instance is closed once no read holds a lease, and one
 * closed under a read fails it with code 21 -- or, under a transaction, keeps
 * the file locked for the life of the process (`SharedInstanceUnderUseTest`
 * in `:feature:capture`).
 */
@RunWith(AndroidJUnit4::class)
class LeasedReadTest {
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
     * **A feed page read through an instance replaced during it finishes,
     * and leaves the file writable.** Room reads the feed's first page in a
     * transaction; the stand-in for it here does too, and the replacement
     * lands inside it, made and finished by another caller.
     */
    @Test(timeout = 60_000)
    fun aFeedPageWhoseInstanceIsReplacedDuringItFinishes() = runBlocking<Unit> {
        context.freshLedger()
        val room = object : PagingSource<Int, FeedRow>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FeedRow> {
                val db = Databases.shared(context)
                val count = db.runInTransaction<Int> {
                    runBlocking { context.replaceFromAnotherCaller() }
                    db.txnDao().countAll()
                }
                return LoadResult.Page(emptyList(), prevKey = null, nextKey = null, itemsBefore = 0, itemsAfter = count)
            }

            override fun getRefreshKey(state: PagingState<Int, FeedRow>): Int? = null
        }

        val loaded = runCatching {
            withContext(Dispatchers.IO) { LeasedFeed(room, Databases.shared(context).invalidationTracker).load(PagingSource.LoadParams.Refresh(null, 40, false)) }
        }

        assertWritable()
        assertNull("the page threw ${loaded.exceptionOrNull()}", loaded.exceptionOrNull())
        assertTrue("the page was not read: ${loaded.getOrNull()}", loaded.getOrNull() is PagingSource.LoadResult.Page)
    }

    /**
     * **Settings' counts read through an instance replaced after the screen
     * opened it.** Closed under them they threw code 21, which the counts
     * catch, and drew a healthy ledger with no counts beside it.
     */
    @Test(timeout = 60_000)
    fun settingsCountsReadThroughAnInstanceReplacedUnderThem() = runBlocking<Unit> {
        context.freshLedger()
        val settings = withContext(Dispatchers.Main) { SettingsViewModel(app) }
        settings.stored = object : StoredSettings {
            // Read after the screen has opened the database, before the counts.
            override suspend fun damaged(context: Context): Boolean {
                context.replaceFromAnotherCaller()
                return false
            }
            override suspend fun lastCheckAt(context: Context) = 0L
            override suspend fun lastExportAt(context: Context) = 0L
        }

        settings.refresh()

        assertNotNull("the counts read nothing through the replaced instance", settings.state.value.wipeCounts)
        assertNotNull("the source count read nothing through the replaced instance", settings.state.value.sourcesOn)
    }

    /** Another caller meets code 26 on the shared instance, replaces it, and is done. */
    private suspend fun Context.replaceFromAnotherCaller() {
        CaptureStorage.guarded(this, "another read", { Unit }, damageStopsCapture = false) {
            Databases.replacePoisoned(Databases.shared(this))
        }
    }

    private fun assertWritable() {
        val opened = runCatching {
            DatabaseFactory.build(context).let { own ->
                try { own.captureSourceDao().setEnabled("my.pinged.test.bank", true) } finally { own.close() }
            }
        }
        assertNull(
            "pinged.db would not open and take a write: ${opened.exceptionOrNull()} " +
                "(cause ${opened.exceptionOrNull()?.cause})",
            opened.exceptionOrNull(),
        )
    }
}
