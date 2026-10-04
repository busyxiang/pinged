package my.pinged.ledger.transfer

import android.database.SQLException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import my.pinged.capture.Graph
import my.pinged.data.DatabaseFactory
import my.pinged.data.LocalDate
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RowReader] against real damage: a seeded SQLCipher ledger with a
 * byte flipped in it, read back through the connections the app opens.
 *
 * Where the damage lands is read off `dbstat` before the byte is flipped, so
 * each test knows exactly which ids are unreadable and asserts that salvage
 * returns every other one and counts those. `dbstat` walks every page, so it
 * cannot be asked afterwards.
 */
@RunWith(AndroidJUnit4::class)
class RowReaderDamageTest {
    private companion object {
        /**
         * Measured at 2.37-3.23 ms over seven runs, one failed read and one
         * reopen. Fifty leaves room for a loaded emulator and still fails a
         * reader that asked only every twenty reads.
         */
        const val CANCEL_BOUND_MS = 50.0
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file: File get() = context.getDatabasePath(DatabaseFactory.NAME)

    @After fun tearDown() {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    /** One B-tree page, from `dbstat`; [lo]..[hi] are its ids when it is a table leaf of dense ids. */
    private class Page(val tree: String, val path: String, val number: Int, val type: String, val cells: Int) {
        var lo = 0L
        var hi = 0L
    }

    /**
     * [captures] captures and a transaction for each, in one transaction, then
     * checkpointed into the main file so that the damage lands on it.
     * Returns every page of every tree.
     */
    private fun seedLedger(captures: Int): List<Page> {
        freshDatabase(context).useDb { db ->
            val uncategorized = db.categoryDao().requireUncategorizedId()
            db.runInTransaction {
                (0 until captures).chunked(ExportJson.PAGE).forEach { chunk ->
                    db.rawCaptureDao().insertAll(chunk.map(::bulkCapture))
                }
                for (i in 1..captures) db.txnDao().insert(txnFor(i.toLong(), uncategorized))
            }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            return layout(db)
        }
    }

    private fun txnFor(capture: Long, category: Long) = Txn(
        rawCaptureId = capture,
        amountSen = 100L + capture,
        direction = Direction.EXPENSE,
        occurredAt = 1_600_000_000_000L + capture,
        localDate = LocalDate(20_260_101),
        merchantRaw = "MERCHANT $capture",
        merchantDisplay = "Merchant $capture",
        merchantKey = "MERCHANT $capture",
        categoryId = category,
        sourcePackage = "my.com.tngdigital.ewallet",
        sourceLabel = "TNG",
        confidence = Confidence.HIGH,
        state = TxnState.COMMITTED,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun layout(db: PingedDatabase): List<Page> {
        val pages = mutableListOf<Page>()
        db.openHelper.readableDatabase
            .query("SELECT name, path, pageno, pagetype, ncell FROM dbstat ORDER BY name, path")
            .use { c -> while (c.moveToNext()) pages += Page(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getInt(4)) }
        for ((_, tree) in pages.groupBy { it.tree }) {
            var next = 1L
            tree.filter { it.type == "leaf" }.forEach { it.lo = next; it.hi = next + it.cells - 1; next += it.cells }
        }
        return pages
    }

    private fun leaves(pages: List<Page>, tree: String) = pages.filter { it.tree == tree && it.type == "leaf" }

    /** SQLCipher's page is 4,096 bytes, and a flip anywhere in one fails its HMAC. */
    private fun damage(page: Page) {
        RandomAccessFile(file, "rw").use { raf ->
            val at = (page.number - 1).toLong() * 4096 + 1000
            raf.seek(at)
            val original = raf.readByte()
            raf.seek(at)
            raf.writeByte(original.toInt() xor 0xFF)
        }
    }

    private fun salvaging() = RowReader(open = { DatabaseFactory.build(context) }, close = { it.close() })

    /**
     * [table] with its reads counted, failing past [cap] of them: a walk that
     * does not stop fails here, on its own message, and does not hang.
     */
    private fun <T> capped(table: PagedTable<PingedDatabase, T>, cap: Int): PagedTable<PingedDatabase, T> {
        var reads = 0
        return PagedTable(
            name = table.name,
            fetch = { db, from, limit ->
                if (++reads > cap) throw AssertionError("salvage of ${table.name} made $reads reads and was still going")
                table.fetch(db, from, limit)
            },
            idOf = table.idOf,
            idSources = table.idSources,
            highestIdEver = table.highestIdEver,
        )
    }

    /** `ExportJson.writePaged`'s loop: pages of [ExportJson.PAGE] until a short one. */
    private fun <T> walk(reader: RowReader<PingedDatabase>, table: PagedTable<PingedDatabase, T>): List<Long> {
        val ids = mutableListOf<Long>()
        var after = 0L
        while (true) {
            val page = reader.page(table, after, ExportJson.PAGE)
            ids += page.map(table.idOf)
            if (page.size < ExportJson.PAGE) return ids
            after = table.idOf(page.last())
        }
    }

    /** How far the healthy path got: the rows before the page that threw. */
    private fun <T> strictReach(table: PagedTable<PingedDatabase, T>): Int =
        DatabaseFactory.build(context).useDb { db ->
            var read = 0
            var from = 1L
            val outcome = runCatching {
                while (true) {
                    val page = table.fetch(db, from, ExportJson.PAGE)
                    read += page.size
                    if (page.size < ExportJson.PAGE) break
                    from = table.idOf(page.last()) + 1
                }
            }
            assertTrue(
                "the strict walk of ${table.name} read $read rows and ended in $outcome, " +
                    "not in the damage, so this test proves nothing",
                outcome.exceptionOrNull() is SQLiteDatabaseCorruptException,
            )
            read
        }

    /**
     * Asserts design 5's four claims of salvage over one table: it ended, it
     * got at least as far as the strict walk, it returned no row twice, and
     * its loss figure is true of what was seeded. [unreadable] is known from
     * `dbstat`, so recovery is asserted exact rather than merely larger.
     */
    private fun <T> assertSalvaged(
        reader: RowReader<PingedDatabase>,
        table: PagedTable<PingedDatabase, T>,
        seeded: Int,
        unreadable: Set<Long>,
        strictReach: Int,
    ) {
        val outcome = runCatching { walk(reader, capped(table, cap = 3 * seeded)) }
        assertNull("salvage of ${table.name} did not end in rows: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        val ids = outcome.getOrThrow()
        assertEquals("a row of ${table.name} came back twice", ids.size, ids.toSet().size)
        assertTrue("salvage read ${ids.size} of ${table.name}, fewer than the $strictReach the strict walk reached", ids.size >= strictReach)
        assertEquals(
            "salvage of ${table.name} lost a readable row or kept an unreadable one",
            (1L..seeded).filterNot { it in unreadable },
            ids,
        )
        assertEquals(
            "the loss reported for ${table.name} is not the rows seeded and not read",
            Unreadable.Counted(recovered = ids.size, unread = unreadable.size),
            reader.unreadable[table.name],
        )
        assertEquals("recovered and lost do not add up to what was seeded", seeded, ids.size + unreadable.size)
    }

    private val Page.ids: Set<Long> get() = (lo..hi).toSet()

    private fun <T> timed(label: String, block: () -> T): T {
        val start = System.nanoTime()
        return block().also { Log.i("RowReaderDamageTest", "$label: ${(System.nanoTime() - start) / 1_000_000} ms") }
    }

    /**
     * Design 2.3's injury at a year of capture: `damageMidFile` lands on a
     * `raw_capture` leaf, measured as ids 8962..8980 of 10,000 with this seed.
     */
    @Test(timeout = 300_000)
    fun oneFlippedByteAtAYearOfCaptureLosesOnlyItsOwnLeaf() {
        val seeded = 10_000
        val pages = seedLedger(seeded)
        val hit = (file.length() / 2 / 4096 + 1).toInt()
        val leaf = pages.firstOrNull { it.number == hit }
        assertTrue(
            "damageMidFile landed on ${leaf?.tree} ${leaf?.type} page $hit, not a raw_capture leaf, so this test proves nothing",
            leaf != null && leaf.tree == "raw_capture" && leaf.type == "leaf",
        )
        leaf!!
        Graph.reset()
        damageMidFile(file)
        val reach = strictReach(PagedTable.RAW_CAPTURE)

        salvaging().use { reader ->
            timed("salvage of $seeded captures and $seeded transactions") {
                assertSalvaged(reader, PagedTable.RAW_CAPTURE, seeded, leaf.ids, reach)
                val txns = walk(reader, capped(PagedTable.TXN, cap = 3 * seeded))
                assertEquals("the undamaged txn table did not read whole", (1L..seeded).toList(), txns)
            }
            assertFalse("a table that read cleanly was reported as damaged", PagedTable.TXN.name in reader.unreadable)
        }
    }

    /**
     * Correction R43's case: the damaged leaf is the last, so every read at or
     * past it throws, including the one that would have answered that nothing
     * is left. `txn` rather than `raw_capture`, so both tables' index bounds
     * are exercised against real damage.
     */
    @Test(timeout = 120_000)
    fun damageOnTheRightmostPathStillEnds() {
        val seeded = 2_000
        val last = leaves(seedLedger(seeded), "txn").last()
        Graph.reset()
        damage(last)
        DatabaseFactory.build(context).useDb { db ->
            val past = runCatching { db.txnDao().pageStartingAt(seeded + 1L, 1) }
            assertTrue(
                "a read past the last id answered $past, so this damage is not the shape that fails to terminate",
                past.exceptionOrNull() is SQLiteDatabaseCorruptException,
            )
        }
        val reach = strictReach(PagedTable.TXN)

        salvaging().use { reader -> assertSalvaged(reader, PagedTable.TXN, seeded, last.ids, reach) }
    }

    /** Every read goes through the root: nothing reads, and every row is counted. */
    @Test(timeout = 120_000)
    fun aDamagedRootReadsNothingAndCountsEverything() {
        val seeded = 500
        val root = seedLedger(seeded).single { it.tree == "raw_capture" && it.path == "/" }
        assertEquals("the root is a leaf, so this is the leaf case again", "internal", root.type)
        Graph.reset()
        damage(root)

        salvaging().use { reader -> assertSalvaged(reader, PagedTable.RAW_CAPTURE, seeded, (1L..seeded).toSet(), strictReach = 0) }
    }

    /**
     * The first leaf, so nothing reads before the damage, and two leaves with
     * a readable one between them: the island a gallop over damage would
     * jump, on real pages rather than the fake's.
     */
    @Test(timeout = 120_000)
    fun damageAtTheFirstLeafAndAroundAReadableIslandLosesOnlyThoseLeaves() {
        val seeded = 2_000
        val leaves = leaves(seedLedger(seeded), "raw_capture")
        val mid = leaves.size / 2
        val hit = listOf(leaves.first(), leaves[mid], leaves[mid + 2])
        Graph.reset()
        hit.forEach(::damage)
        val reach = strictReach(PagedTable.RAW_CAPTURE)
        assertEquals("the strict walk read past a damaged first leaf, so this is not that case", 0, reach)

        salvaging().use { reader ->
            assertSalvaged(reader, PagedTable.RAW_CAPTURE, seeded, hit.flatMap { it.ids }.toSet(), reach)
        }
    }

    /**
     * An empty table's root is a leaf with no cells. Damaged, every read of
     * the table throws, and there is nothing to lose: no entry, rather than
     * one that reports nothing.
     */
    @Test(timeout = 120_000)
    fun anEmptyTableWhoseRootIsDamagedReportsNoLoss() {
        val root = seedLedger(0).single { it.tree == "raw_capture" && it.path == "/" }
        assertEquals("the empty table's root holds cells", 0, root.cells)
        Graph.reset()
        damage(root)
        strictReach(PagedTable.RAW_CAPTURE)

        salvaging().use { reader ->
            val outcome = runCatching { walk(reader, capped(PagedTable.RAW_CAPTURE, cap = 100)) }
            assertEquals("salvage of an empty table did not end empty", emptyList<Long>(), outcome.getOrThrow())
            assertEquals("an empty table was reported as a loss", emptyMap<String, Unreadable>(), reader.unreadable)
        }
    }

    /**
     * A poisoned connection throws SQLCipher's own `SQLiteNotADatabaseException`,
     * code 26, which is never damage to step over. Measured here on the class
     * SQLCipher throws, because a fake throwing a plain `SQLiteException`
     * would not notice it coming to extend `SQLiteDatabaseCorruptException`.
     * The reader is handed the poisoned instance back on every reopen, so the
     * read after the damage is the one that throws it.
     */
    @Test(timeout = 120_000)
    fun aPoisonedConnectionIsNotSteppedOver() {
        val seeded = 2_000
        val leaf = leaves(seedLedger(seeded), "raw_capture").let { it[it.size / 2] }
        Graph.reset()
        damage(leaf)
        val failures = mutableListOf<Throwable>()
        val watched = PagedTable<PingedDatabase, RawCapture>(
            name = PagedTable.RAW_CAPTURE.name,
            fetch = { db, from, limit ->
                try {
                    PagedTable.RAW_CAPTURE.fetch(db, from, limit)
                } catch (thrown: Throwable) {
                    failures += thrown
                    throw thrown
                }
            },
            idOf = PagedTable.RAW_CAPTURE.idOf,
            idSources = PagedTable.RAW_CAPTURE.idSources,
            highestIdEver = PagedTable.RAW_CAPTURE.highestIdEver,
        )

        DatabaseFactory.build(context).useDb { shared ->
            val reader = RowReader(open = { shared }, close = {})
            val thrown = runCatching { walk(reader, capped(watched, cap = 3 * seeded)) }.exceptionOrNull()
            assertTrue(
                "the first failure was ${failures.firstOrNull()}, not the damage",
                failures.firstOrNull() is SQLiteDatabaseCorruptException,
            )
            val poisoned = failures.getOrNull(1)
            assertEquals(
                "the read after the damage threw $poisoned",
                "net.zetetic.database.sqlcipher.SQLiteNotADatabaseException",
                poisoned?.javaClass?.name,
            )
            assertTrue("\"file is not a database\" became ${poisoned?.message}", poisoned?.message?.contains("code 26") == true)
            assertFalse("SQLCipher's code 26 is now a corruption, so salvage steps over it", poisoned is SQLiteDatabaseCorruptException)
            assertEquals("salvage read on after code 26: $failures", 2, failures.size)
            assertTrue("salvage ended in $thrown, not the code 26 it met", thrown === poisoned)
            assertEquals(emptyMap<String, Unreadable>(), reader.unreadable)
        }
    }

    /**
     * A damaged root reads nothing, so the whole table is one page call of a
     * read and a reopen a row, with no boundary inside it where
     * `ExportJson.writePaged` reports. The reader's own progress has to move.
     */
    @Test(timeout = 120_000)
    fun progressMovesInsideAPageThatReadsNothing() {
        val seeded = 500
        val root = seedLedger(seeded).single { it.tree == "raw_capture" && it.path == "/" }
        Graph.reset()
        damage(root)
        val reported = mutableListOf<Long>()

        RowReader(
            open = { DatabaseFactory.build(context) },
            close = { it.close() },
            onStep = { reported += it },
        ).use { reader ->
            assertEquals(
                "a page over a damaged root returned rows",
                emptyList<RawCapture>(),
                reader.page(PagedTable.RAW_CAPTURE, 0, ExportJson.PAGE),
            )
        }
        assertEquals("progress did not move a step at a time inside the one page", (1L..seeded).toList(), reported)
    }

    /**
     * The same page, cancelled from another thread partway through: it stops
     * at the next read and throws what `writePaged` throws. A page that asked
     * only at its boundary would run on to all 2,000 rows, about 5 s.
     */
    @Test(timeout = 120_000)
    fun cancellingAPageThatReadsNothingStopsItAtTheNextRead() {
        val seeded = 2_000
        val root = seedLedger(seeded).single { it.tree == "raw_capture" && it.path == "/" }
        Graph.reset()
        damage(root)
        val cancelled = AtomicBoolean(false)
        val partway = CountDownLatch(1)
        var steps = 0L
        val reader = RowReader(
            open = { DatabaseFactory.build(context) },
            close = { it.close() },
            isCancelled = { cancelled.get() },
            onStep = { steps = it; if (it == 100L) partway.countDown() },
        )
        var outcome: Result<List<RawCapture>>? = null
        var stoppedAt = 0L
        val walker = thread {
            outcome = runCatching { reader.page(PagedTable.RAW_CAPTURE, 0, ExportJson.PAGE) }
            stoppedAt = System.nanoTime()
        }
        assertTrue("the page never stepped over 100 rows", partway.await(60, TimeUnit.SECONDS))
        val cancelledAt = System.nanoTime()
        cancelled.set(true)
        walker.join()
        reader.close()

        val tookMs = (stoppedAt - cancelledAt) / 1_000_000.0
        Log.i("RowReaderDamageTest", "cancel took effect in $tookMs ms, after $steps steps")
        assertTrue("cancelling did not stop the page: $outcome", outcome?.exceptionOrNull() is TransferCancelledException)
        assertTrue("the page ran on to $steps steps after the cancel", steps < seeded)
        assertTrue("cancel took $tookMs ms to take effect", tookMs <= CANCEL_BOUND_MS)
    }

    @Test(timeout = 120_000)
    fun aDamagedIndexIsPassedOverForTheNext() {
        val seeded = 2_000
        val pages = seedLedger(seeded)
        val leaf = leaves(pages, "raw_capture").let { it[it.size / 2] }
        Graph.reset()
        damage(leaf)
        damage(leaves(pages, "index_raw_capture_parse_status_id").first())

        salvaging().use { reader ->
            assertSalvaged(reader, PagedTable.RAW_CAPTURE, seeded, leaf.ids, strictReach(PagedTable.RAW_CAPTURE))
        }
    }

    /**
     * No index reads, so the walk steps integers up to `sqlite_sequence`. Ids
     * here are dense, so the bound happens to equal the loss; the fake-based
     * `RowReaderTest` has the gaps.
     */
    @Test(timeout = 120_000)
    fun withEveryIndexDamagedTheLossIsReportedAsABound() {
        val seeded = 2_000
        val pages = seedLedger(seeded)
        val leaf = leaves(pages, "raw_capture").let { it[it.size / 2] }
        Graph.reset()
        damage(leaf)
        damage(leaves(pages, "index_raw_capture_parse_status_id").first())
        damage(leaves(pages, "index_raw_capture_sbn_key").first())

        salvaging().use { reader ->
            val outcome = runCatching { walk(reader, capped(PagedTable.RAW_CAPTURE, cap = 3 * seeded)) }
            assertNull("salvage with no readable index did not end in rows: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
            assertEquals(
                "salvage lost a readable row or kept an unreadable one",
                (1L..seeded).filterNot { it in leaf.lo..leaf.hi },
                outcome.getOrThrow(),
            )
            assertEquals(
                "with no index readable, the loss was not reported as a bound",
                Unreadable.AtMost(recovered = seeded - leaf.ids.size, ids = leaf.hi - leaf.lo + 1),
                reader.unreadable[PagedTable.RAW_CAPTURE.name],
            )
        }
    }

    /**
     * A closed connection is `android.database.SQLException`, which is not a
     * `SQLiteException`: salvage must let it through and not read it as
     * damage. Measured, not assumed.
     */
    @Test fun aClosedConnectionIsNotSteppedOver() {
        seedLedger(10)
        Graph.reset()
        val closed = DatabaseFactory.build(context).also { it.close() }
        var reads = 0
        val counted = PagedTable<PingedDatabase, RawCapture>(
            name = PagedTable.RAW_CAPTURE.name,
            fetch = { db, from, limit -> reads++; PagedTable.RAW_CAPTURE.fetch(db, from, limit) },
            idOf = PagedTable.RAW_CAPTURE.idOf,
            idSources = PagedTable.RAW_CAPTURE.idSources,
            highestIdEver = PagedTable.RAW_CAPTURE.highestIdEver,
        )
        val reader = RowReader(open = { closed }, close = {})
        val outcome = runCatching { reader.page(counted, 0, ExportJson.PAGE) }
        val thrown = outcome.exceptionOrNull()
        assertTrue("a closed connection answered $outcome", thrown is SQLException && thrown !is SQLiteException)
        assertTrue("\"connection is closed\" became ${thrown?.message}", thrown?.message?.contains("code: 21") == true)
        // Its id sources fail the same way, so a reader that stepped over it
        // would end in the same exception; what differs is that it kept trying.
        assertEquals("salvage retried a closed connection as if it were damage", 1, reads)
        assertEquals(emptyMap<String, Unreadable>(), reader.unreadable)
    }

    /**
     * The three tables' reads start *at* their id, and each index lists what
     * the table holds. Salvage's stepping depends on the first, its count on
     * the second.
     */
    @Test fun everyPagedTableReadsFromItsIdAndItsIndexesListEveryRow() {
        freshDatabase(context).useDb { db ->
            seedOneOfEverything(db)
            for (table in listOf(PagedTable.MERCHANT_RULE, PagedTable.RAW_CAPTURE, PagedTable.TXN)) {
                val all = table.fetch(db, 1, Int.MAX_VALUE).map(table.idOf)
                assertTrue("${table.name} has too few rows to say anything", all.size >= 2)
                assertEquals(
                    "${table.name}'s read skipped the id it was asked to start at",
                    listOf(all[1]),
                    table.fetch(db, all[1], 1).map(table.idOf),
                )
                table.idSources.forEachIndexed { index, source ->
                    assertEquals("${table.name}'s id source $index", all.sorted(), source(db).sorted())
                }
                assertEquals("${table.name}'s sqlite_sequence", all.max(), table.highestIdEver(db))
            }
        }
    }
}
