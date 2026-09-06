package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.CaptureDays
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.Arrival
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spec 11.1's missing-key state, end to end: the database exists, its
 * Keystore-wrapped key does not, and the app has to be able to say so.
 *
 * Before this path existed, nothing anywhere caught
 * `DatabaseKeyUnavailableException`. On the listener it unwound into the
 * CoroutineExceptionHandler -- one log line per notification, forever, every
 * notification dropped. And because `CaptureHealth.recordSeen` runs *before*
 * the first database call, the heartbeat stayed fresh, `looksDead` stayed
 * false, and the banner stayed silent. The app reported healthy capture
 * indefinitely while storing nothing, which is spec 10's opening failure
 * reached from a direction spec 10 did not anticipate.
 */
@RunWith(AndroidJUnit4::class)
class StorageUnavailableTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyFile get() = File(context.noBackupFilesDir, "db.key")

    @Before fun freshDatabaseAndKey() {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        keyFile.delete()
        // Create both, then take only the key away: a key alone missing is the
        // state that must not be mistaken for a first run.
        DatabaseFactory.build(context).close()
        runBlocking { CaptureHealth.clearStorageUnavailable(context) }
    }

    @After fun leaveItOpenable() {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        keyFile.delete()
        runBlocking { CaptureHealth.clearStorageUnavailable(context) }
    }

    /**
     * The same hole, on the other writer, found where the first fix did not
     * reach.
     *
     * `markListenerBound` skips the database once the day is already recorded,
     * exactly as its sibling does -- and `onListenerConnected` wraps it
     * in `CaptureStorage.guarded`. So on the second bind of a day the guarded
     * block touched nothing, returned normally, and the guard *cleared the
     * storage flag*: a routine rebind wiped "CANNOT OPEN YOUR DATA" off a phone
     * whose key was gone. Rebinding is what spec 10.1 says this app dies of
     * first, so this is not a rare path.
     *
     * The first time this was found, the fix went into `CaptureIngest` as an
     * explicit open inside its own guard. It is the guard's job now, so no call
     * site can be the one that forgot.
     */
    @Test fun aRebindOnADeadDatabaseDoesNotClearTheBanner() {
        val today = LocalDates.of(System.currentTimeMillis())

        // First bind of the day, storage healthy: records the day and leaves
        // the memo set, which is what makes every later bind skip.
        runBlocking {
            CaptureHealth.forgetProcessMemo()
            CaptureDays.markListenerBound(today) { Databases.captureDayDao(context) }
        }

        Graph.reset()
        keyFile.delete()
        runBlocking { CaptureHealth.recordStorageUnavailable(context) }

        // The second bind, as `onListenerConnected` performs it. The dao
        // accessor throws if it is ever reached, which asserts the premise:
        // this block genuinely touches no database, so anything the guard
        // concludes about storage it must have found out for itself.
        runBlocking {
            CaptureStorage.guarded(
                context,
                what = "second bind of the day",
                unavailable = {},
            ) { CaptureDays.markListenerBound(today) { error("the block reached the database") } }
        }

        assertTrue(
            "A rebind cleared the storage banner without ever opening the " +
                "database, so the phone says capture is healthy while its key " +
                "is gone",
            runBlocking { CaptureHealth.storageUnavailable(context) },
        )
    }

    /**
     * The same state, reached on the second notification of the day.
     *
     * `CaptureDays.markNotificationSeen` skips the database once the day is
     * already marked --
     * that is its whole point, and it is the case ~299 times out of 300. But
     * the `CaptureStorage.guarded` block it sits in is also the only thing
     * standing between the rest of `ingest` and a missing key, and a block
     * that skips the database probes nothing. It reported storage healthy and
     * the very next line, `Databases.captureSourceDao`, opened the database
     * outside the guard.
     *
     * So the day a key goes away, every notification from the first mark until
     * midnight went back to unwinding into the listener's exception handler
     * with the banner still silent -- the exact failure the class above
     * documents, narrowed to same-day and therefore easy to miss.
     */
    @Test fun aMissingKeyIsReportedOnceTheDayIsAlreadyMarked() {
        val posted = capture("already-marked")

        // One good ingest, so the day is marked in memory and in DataStore.
        runBlocking { CaptureIngest.ingest(context, posted, Arrival.POSTED, System.currentTimeMillis()) }
        runBlocking { CaptureHealth.clearStorageUnavailable(context) }

        Graph.reset()
        keyFile.delete()

        assertNull(
            "Nothing can be stored with no database to store it in",
            runBlocking {
                CaptureIngest.ingest(context, capture("after"), Arrival.POSTED, System.currentTimeMillis())
            },
        )

        val after = runBlocking { ListenerStatus.report(context) }
        assertTrue(
            "The day was already marked, so the storage probe skipped the database " +
                "and reported it healthy while the next line could not open it",
            after.storageUnavailable,
        )
    }

    private fun capture(tag: String) = CaptureFixtures.posted(
        CaptureFixtures.notification(
            context,
            title = "Touch 'n Go",
            text = "Payment of RM12.00 to Kedai Ali successful",
        ),
        pkg = ParseFixtures.TNG,
        id = 1,
        tag = tag,
        postTime = System.currentTimeMillis(),
    )

    @Test fun aMissingKeyIsReportedRatherThanSwallowed() {
        val before = runBlocking { ListenerStatus.report(context) }
        assertFalse("precondition: storage is fine to start with", before.storageUnavailable)

        Graph.reset()
        keyFile.delete()

        val notification = CaptureFixtures.notification(
            context,
            title = "Touch 'n Go",
            text = "Payment of RM12.00 to Kedai Ali successful",
        )
        val posted = CaptureFixtures.posted(
            notification,
            pkg = ParseFixtures.TNG,
            id = 1,
            tag = "storage-unavailable",
            postTime = System.currentTimeMillis(),
        )

        // `ingest` returns null rather than throwing: `CaptureStorage.guarded`
        // catches the whole `DatabaseUnavailableException` family, so nothing
        // reaches the listener's exception handler. Asserting the return is
        // stronger than swallowing it, and it is now the contract.
        val stored = runBlocking {
            CaptureIngest.ingest(context, posted, Arrival.POSTED, System.currentTimeMillis())
        }
        assertNull("Nothing can be stored with no database to store it in", stored)

        val after = runBlocking { ListenerStatus.report(context) }
        assertTrue(
            "The database cannot be opened and the report cannot say so",
            after.storageUnavailable,
        )
        assertTrue(
            "A phone storing nothing must not read as needing no action. The heartbeat " +
                "is written before the first database call, so every other signal in " +
                "this report says capture is healthy.",
            after.needsUserAction,
        )
    }
}
