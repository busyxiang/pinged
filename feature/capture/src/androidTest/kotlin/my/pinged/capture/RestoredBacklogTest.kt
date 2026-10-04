package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.ledger.transfer.ExportJson
import my.pinged.ledger.transfer.Restore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Restore`'s cache invalidation, exercised from this module rather than
 * `:feature:ledger`'s own `RestoreTest`.
 *
 * `Reparse`, which [Restore] must invalidate the mark of, is `internal` to
 * this module on purpose -- `feature/capture/build.gradle.kts` already
 * rejected widening a private stage-one entry point (`CaptureIngest`) to
 * public just to buy a cross-module test, and a sweep mark is the same shape
 * of thing. This test needs to read that mark back, so it lives where the
 * mark does; `:feature:capture`'s androidTest already depends on
 * `:feature:ledger` for exactly this direction, and gets its own module's
 * `internal` for free by being the same module's test source set. This is the
 * test's right home, not a workaround for a compiler error.
 */
@RunWith(AndroidJUnit4::class)
class RestoredBacklogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Revert `CaptureCaches.clear` from `Restore` and this fails: the restored
     * captures stay at their terminal `parse_status` forever.
     */
    @Test fun aRestoredBacklogIsReRead() = runBlocking {
        val packVersion = Graph.ruleMatcher().packVersion

        // This device has already swept, so the mark says there is nothing to do.
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        DatabaseFactory.build(context).close()
        Reparse.sweep(context, Databases.rawCaptureDao(context), packVersion)

        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        val source = DatabaseFactory.build(context)
        source.rawCaptureDao().insert(
            RawCapture(
                sourcePackage = "my.com.tngdigital.ewallet",
                postedAt = 1_600_000_000_000L,
                whenMillis = null,
                capturedAt = 1_600_000_000_000L,
                sbnKey = "stale",
                notifId = 9_001,
                notifTag = null,
                userHandle = 0,
                channelId = "txn",
                flags = 0,
                arrival = Arrival.POSTED,
                title = "Touch 'n Go eWallet",
                text = "a wording the shipped pack could not read",
                bigText = null,
                subText = null,
                extrasJson = null,
                contentHash = "stale-capture",
                parseStatus = ParseStatus.UNMATCHED,
                packVersion = packVersion - 1,
            ),
        )
        val bytes = ByteArrayOutputStream().also { ExportJson.write(source, it) }.toByteArray()
        source.close()

        Restore.replaceEverything(context, ByteArrayInputStream(bytes))

        val swept = Reparse.sweep(
            context, Databases.rawCaptureDao(context), packVersion,
        )
        assertTrue(
            "the restored backlog was left at its terminal status, so a restore " +
                "lands captures nothing will ever re-read",
            swept.moved > 0,
        )
    }
}
