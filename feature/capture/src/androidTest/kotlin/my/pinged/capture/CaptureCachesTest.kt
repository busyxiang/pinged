package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.entity.ParseStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gap `Reparse`'s KDoc records: the swept-pack mark does not travel in a
 * backup, so a restored backlog arrives stamped at old pack versions while the
 * mark says the current one is done. The v0.3.0 bug, by a second route.
 */
@RunWith(AndroidJUnit4::class)
class CaptureCachesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startFromAnEmptyDatabase() {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun clearingMakesTheNextSweepRunAgain() = runBlocking {
        val captures = Databases.rawCaptureDao(context)
        val packVersion = Graph.ruleMatcher().packVersion

        // A sweep that records the current version, so a second one is a no-op.
        Reparse.sweep(context, captures, packVersion)
        val second = Reparse.sweep(context, captures, packVersion)
        assertEquals("the mark did not take", 0, second.moved)

        captures.insert(
            my.pinged.data.entity.RawCapture(
                sourcePackage = "my.com.tngdigital.ewallet",
                postedAt = 1_000L, whenMillis = null, capturedAt = 1_000L,
                sbnKey = "restored", notifId = 1, notifTag = null, userHandle = 0,
                channelId = "txn", flags = 0, arrival = my.pinged.data.entity.Arrival.POSTED,
                title = null, text = "restored capture an old pack could not read",
                bigText = null, subText = null, extrasJson = null,
                contentHash = "restored", parseStatus = ParseStatus.UNMATCHED,
                packVersion = packVersion - 1,
            ),
        )

        CaptureCaches.clear(context)

        val afterClear = Reparse.sweep(context, captures, packVersion)
        assertEquals(
            "a restored capture stayed at its terminal status, so a restore " +
                "still lands a backlog nothing ever re-reads",
            1,
            afterClear.moved,
        )
    }
}
