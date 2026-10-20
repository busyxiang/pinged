package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import my.pinged.data.Databases
import my.pinged.data.PingedDatabase
import my.pinged.data.SearchFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Databases.holder` across an export (#279): a screen's read made while
 * `ExportJson.write` holds the database sees `Export`, and `null` once the
 * export has ended however it ended. The integrity check's half is
 * `:core:data`'s `HolderTest`.
 *
 * The export is held mid-write from its own progress callback, which runs
 * inside its transaction, as `ExportIsolationTest` does. The read is one
 * through `Databases.inOneTransaction`, as Charts' and the Day screen's are:
 * a plain DAO read on the export's instance did not wait for it on
 * emulator-5554, and it is the monitor a screen waits behind.
 */
@RunWith(AndroidJUnit4::class)
class ExportHolderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: PingedDatabase? = null

    @After fun tearDown() {
        db?.close()
        db = null
    }

    private fun seeded(): PingedDatabase =
        freshDatabase(context).also {
            db = it
            seedOneOfEverything(it)
        }

    @Test fun aReadDuringAnExportSeesItUntilItEnds() {
        val source = seeded()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exporter = thread {
            ExportJson.write(source, ByteArrayOutputStream(), onProgress = {
                writing.countDown()
                release.await(10, TimeUnit.SECONDS)
            })
        }
        assertTrue("the export never started writing", writing.await(10, TimeUnit.SECONDS))

        val seenByRead = AtomicReference<Databases.Holder?>()
        val reader = thread {
            seenByRead.set(Databases.holder.value)
            Databases.inOneTransaction(context) { it.categoryDao().countAll() }
        }
        reader.join(200)
        assertTrue("the read did not wait behind the export", reader.isAlive)
        assertEquals(Databases.Holder.Export, Databases.holder.value)

        release.countDown()
        exporter.join(10_000)
        reader.join(10_000)
        assertEquals(Databases.Holder.Export, seenByRead.get())
        assertNull(
            "The export returned, but holder still names ${Databases.holder.value}: " +
                "a screen would say it is waiting behind an export forever",
            Databases.holder.value,
        )
    }

    @Test fun anExportThatThrowsLeavesNothingHolding() {
        val source = seeded()
        val outcome = runCatching {
            ExportJson.write(source, ByteArrayOutputStream(), onProgress = {
                assertEquals(Databases.Holder.Export, Databases.holder.value)
                throw IllegalStateException("the provider went away")
            })
        }
        assertTrue("expected the write to fail, got $outcome", outcome.exceptionOrNull() is IllegalStateException)
        assertNull("A failed export left holder at ${Databases.holder.value}", Databases.holder.value)
    }

    @Test fun aCancelledExportLeavesNothingHolding() {
        val source = seeded()
        val outcome = runCatching {
            ExportJson.write(source, ByteArrayOutputStream(), isCancelled = { true })
        }
        assertTrue(
            "expected the write to be cancelled, got $outcome",
            outcome.exceptionOrNull() is TransferCancelledException,
        )
        assertNull("A cancelled export left holder at ${Databases.holder.value}", Databases.holder.value)
    }

    @Test fun aSpreadsheetIsAnExportToo() {
        val source = seeded()
        val seen = AtomicReference<Databases.Holder?>()
        ExportCsv.write(
            source,
            ByteArrayOutputStream(),
            text = "",
            filter = SearchFilter(),
            onProgress = { seen.set(Databases.holder.value) },
        )
        assertEquals(Databases.Holder.Export, seen.get())
        assertNull(Databases.holder.value)
    }
}
