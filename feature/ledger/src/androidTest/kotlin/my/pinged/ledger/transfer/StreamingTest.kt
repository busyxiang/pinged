package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import my.pinged.data.PingedDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 12: "Nothing is assembled in memory first."
 *
 * **The plan's version of this test does not test that.** It exported into a
 * `ByteArrayOutputStream`, measured `totalMemory - freeMemory` either side, and
 * asserted the difference was smaller than the payload -- while the payload was
 * on the heap inside that very stream, counted in the difference. It could pass
 * for a fully buffered implementation on a lucky run and fail for a streaming
 * one on an unlucky one.
 *
 * What is observable and deterministic is *interleaving*: an exporter that
 * streams has bytes in the file while it is still reading rows. So the stream
 * here counts bytes and discards them, and every progress callback records how
 * many had reached it by then. A buffered implementation reports zero at every
 * one.
 */
@RunWith(AndroidJUnit4::class)
class StreamingTest {
    /**
     * Ten pages of captures. Spec 15.8's fixture is 50,000, and this is not
     * that: at 50,000 the seeding alone dominates the run and this test is
     * about the shape of the write, which a tenth of that already has -- ten
     * page boundaries, ten progress reports, and a payload well past anything
     * a single page could hold.
     */
    private val captureCount = ExportJson.PAGE * 10

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: PingedDatabase? = null

    @After fun tearDown() {
        db?.close()
        db = null
    }

    private fun fresh(): PingedDatabase {
        db?.close()
        db = null
        return freshDatabase(context).also { db = it }
    }

    /** Counts what it is given and keeps none of it. */
    private class CountingStream : OutputStream() {
        var bytes = 0L
            private set

        override fun write(b: Int) {
            bytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            bytes += len
        }
    }

    private fun seedBulk(target: PingedDatabase) {
        // One transaction, because `DatabaseFactory` sets `synchronous = FULL`
        // and five thousand individually committed inserts is five thousand
        // fsyncs.
        target.runInTransaction(
            Runnable { repeat(captureCount) { target.rawCaptureDao().insert(bulkCapture(it)) } },
        )
    }

    @Test fun theExportReachesTheStreamBeforeItHasReadEveryRow() {
        val source = fresh()
        seedBulk(source)

        val stream = CountingStream()
        val observed = mutableListOf<Pair<Int, Long>>()
        val total = ExportJson.write(source, stream, onProgress = { rows ->
            observed += rows to stream.bytes
        })

        assertEquals(captureCount + 14, total)
        assertTrue(
            "Only ${observed.size} progress reports for $captureCount captures; the " +
                "export is not reporting per page.",
            observed.size >= 10,
        )

        // The first report that covers a whole page of captures already had
        // bytes on the stream. This is the assertion a buffered implementation
        // fails: it would be reporting rows with a byte count of zero.
        val firstFullPage = observed.first { it.first >= ExportJson.PAGE }
        assertTrue(
            "After ${firstFullPage.first} rows the stream had ${firstFullPage.second} " +
                "bytes. An export that assembles the document first has none until it " +
                "finishes.",
            firstFullPage.second > 0,
        )

        // And it kept arriving, rather than one flush at the end.
        val midway = observed[observed.size / 2]
        assertTrue(
            "Halfway through, ${midway.second} of ${stream.bytes} bytes had been " +
                "written; the document is arriving in one piece at the end.",
            midway.second in 1 until stream.bytes,
        )
        assertTrue(
            "The payload is only ${stream.bytes} bytes, which is too small for this " +
                "test to be measuring anything.",
            stream.bytes > 500_000,
        )
    }

    /**
     * And the whole of it survives a round trip at that size, which is the
     * only way to know the paging cursor does not skip or repeat a row at a
     * page boundary. A keyset cursor that got its comparison wrong by one
     * would lose or duplicate exactly one capture per page, and at four rows
     * the round-trip test could not see it.
     */
    @Test fun everyRowSurvivesAcrossPageBoundaries() {
        val source = fresh()
        seedBulk(source)
        val bytes = exportBytes(source)

        val target = fresh()
        val report = ImportJson.read(target, ByteArrayInputStream(bytes))

        assertEquals(captureCount, report.rawCaptures)
        assertEquals(captureCount, target.rawCaptureDao().countAll())
        val hashes = target.rawCaptureDao().pageFrom(0L, captureCount * 2).map { it.contentHash }
        assertEquals(
            "a page boundary lost or repeated a row",
            captureCount,
            hashes.toSet().size,
        )
        assertEquals("bulk-0", hashes.first())
        assertEquals("bulk-${captureCount - 1}", hashes.last())
    }

    /**
     * Cancelling stops the export rather than finishing it quietly, and says
     * so. The half-written file is the caller's to delete; there is no way to
     * write a prefix of a JSON object that is also a JSON object, so the only
     * alternative would be a file that closes its braces and claims to be a
     * complete backup of a ledger it holds a tenth of.
     */
    @Test fun cancellingAnExportStopsIt() {
        val source = fresh()
        seedBulk(source)

        val stream = CountingStream()
        var pages = 0
        val thrown = runCatching {
            ExportJson.write(source, stream, isCancelled = { pages++ >= 3 })
        }.exceptionOrNull()

        assertTrue(
            "Expected a TransferCancelledException, got $thrown",
            thrown is TransferCancelledException,
        )
        assertTrue(
            "The message should say where it stopped: ${thrown?.message}",
            thrown!!.message!!.contains(Backup.RAW_CAPTURES),
        )
        assertTrue("nothing was written at all", stream.bytes > 0)
    }
}
