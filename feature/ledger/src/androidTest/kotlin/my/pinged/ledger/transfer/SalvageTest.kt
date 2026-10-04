package my.pinged.ledger.transfer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import my.pinged.capture.ParseWorker
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.PingedDatabase
import my.pinged.data.Seed
import my.pinged.data.entity.Category
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RuleOrigin
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.settings.Transfers
import my.pinged.ledger.settings.grouped
import my.pinged.ledger.settings.salvageSentences
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Design 5's salvage end to end: a real damaged ledger in the app's own
 * `pinged.db`, salvaged through [SalvageJson], and **the file it wrote
 * restored** through [Restore.replaceEverything] -- the import's every
 * reference check included, which is what a salvaged file exists to pass.
 *
 * Where the damage lands is read off `dbstat` before the byte is flipped, as
 * `RowReaderDamageTest` does, so each test knows the unreadable ids exactly.
 */
@RunWith(AndroidJUnit4::class)
class SalvageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file: File get() = context.getDatabasePath(DatabaseFactory.NAME)

    @Before fun startClean() {
        Transfers.forgetOutcome()
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @After fun leaveAnOpenableDatabase() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        Transfers.forgetOutcome()
    }

    /** One B-tree page, from `dbstat`; [lo]..[hi] are its ids when it is a table leaf of dense ids. */
    private class Page(val tree: String, val path: String, val number: Int, val type: String, val cells: Int) {
        var lo = 0L
        var hi = 0L
        val ids: Set<Long> get() = (lo..hi).toSet()
    }

    /**
     * [captures] captures with a transaction each, then [more], checkpointed
     * so the damage lands on the main file. Every page of every tree.
     */
    private fun seedLedger(captures: Int, more: (PingedDatabase, Long) -> Unit = { _, _ -> }): List<Page> {
        freshDatabase(context).useDb { db ->
            val uncategorized = db.categoryDao().requireUncategorizedId()
            db.runInTransaction {
                (0 until captures).chunked(ExportJson.PAGE).forEach { chunk ->
                    db.rawCaptureDao().insertAll(chunk.map(::bulkCapture))
                }
                for (i in 1..captures) db.txnDao().insert(txnFor(i.toLong(), uncategorized))
            }
            more(db, uncategorized)
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

    private fun salvage(onProgress: (Int) -> Unit = {}): Pair<SalvageReport, ByteArray> {
        val out = ByteArrayOutputStream()
        val start = System.nanoTime()
        val report = SalvageJson.write(context, out, onProgress)
        Log.i("SalvageTest", "salvage: ${(System.nanoTime() - start) / 1_000_000} ms, $report")
        return report to out.toByteArray()
    }

    /** The file restored into the app's own database, and that database open on it. */
    private fun restore(bytes: ByteArray): ImportReport = runBlocking {
        Restore.replaceEverything(context, ByteArrayInputStream(bytes))
    }

    private fun restored(): PingedDatabase = Databases.shared(context)

    /**
     * **The point of the task.** Design 2.3's injury at a year of capture --
     * `damageMidFile` lands on a `raw_capture` leaf -- leaves transactions
     * naming captures the file cannot carry. The file restores, and holds
     * exactly the rows that read.
     */
    @Test(timeout = 300_000)
    fun aDamagedLedgerGivesBackItsRowsAndTheFileRestores() {
        val seeded = 10_000
        val pages = seedLedger(seeded)
        val hit = (file.length() / 2 / 4096 + 1).toInt()
        val leaf = pages.firstOrNull { it.number == hit }
        assertTrue(
            "damageMidFile landed on ${leaf?.tree} ${leaf?.type} page $hit, not a raw_capture leaf, so this test proves nothing",
            leaf != null && leaf.tree == "raw_capture" && leaf.type == "leaf",
        )
        val lost = leaf!!.ids
        Databases.reset()
        damageMidFile(file)

        val (report, bytes) = salvage()
        val imported = restore(bytes)

        assertEquals(
            "the loss reported is not the captures seeded and not read",
            Unreadable.Counted(recovered = seeded - lost.size, unread = lost.size),
            report.unreadable["raw_capture"],
        )
        assertFalse("a table that read cleanly was reported as damaged", "txn" in report.unreadable)
        assertEquals("transactions naming an unreadable capture were not all left out", lost.size, report.txnsLeftOut)
        assertTrue(
            "the sheet does not add up against the $seeded seeded: ${salvageSentences(report)}",
            "Recovered ${grouped(seeded - lost.size)} of ${grouped(seeded)} notifications; ${lost.size} could not be read." in
                salvageSentences(report),
        )

        assertEquals("captures restored", seeded - lost.size, imported.rawCaptures)
        assertEquals("transactions restored", seeded - lost.size, imported.txns)
        val readable = (1L..seeded).filterNot { it in lost }
        assertEquals("the restored captures are not exactly those that read", readable, restored().rawCaptureDao().idsFromStatusIndex().sorted())
        assertEquals(
            "the restored transactions do not each name a restored capture",
            readable,
            restored().txnDao().pageFrom(0, seeded).map { it.rawCaptureId },
        )
    }

    /**
     * **A damaged `txn` page gives its money back.** Every capture is
     * `MATCHED`, as stage two leaves one that wrote a transaction, and one
     * `txn` leaf will not read. The captures section is written before the
     * transactions, so those captures reach the file `MATCHED` with nothing
     * behind them -- and stage two claims only `NEW`. Restored and parsed by
     * the real stage two, every capture has its transaction again, at the
     * amount its notification states.
     */
    @Test(timeout = 300_000)
    fun aDamagedTransactionPageGivesItsMoneyBackOnceStageTwoHasRun() {
        val seeded = 2_000
        val pages = seedLedger(seeded) { db, _ ->
            db.openHelper.writableDatabase.execSQL(
                "UPDATE raw_capture SET parse_status = 'MATCHED', matched_rule_id = 'tng-payment-v1'",
            )
        }
        val leaf = pages.filter { it.tree == "txn" && it.type == "leaf" }.let { it[it.size / 2] }
        val lost = leaf.ids
        Databases.reset()
        damage(leaf)

        val (report, bytes) = salvage()
        assertEquals(
            "the damaged leaf's transactions were not the ones stepped over",
            Unreadable.Counted(recovered = seeded - lost.size, unread = lost.size),
            report.unreadable["txn"],
        )
        val imported = restore(bytes)
        assertEquals("captures restored", seeded, imported.rawCaptures)
        assertEquals("transactions restored", seeded - lost.size, imported.txns)

        var runs = 0
        do {
            val result = TestListenableWorkerBuilder<ParseWorker>(context).build().startWork().get()
            runs++
        } while (result != ListenableWorker.Result.success() && runs < 10)

        val txns = restored().txnDao().pageFrom(0, seeded * 2)
        val rewritten = txns.filter { it.rawCaptureId in lost }
        assertEquals(
            "the transactions on the damaged page did not come back once stage two ran",
            lost.sorted(),
            rewritten.mapNotNull { it.rawCaptureId }.sorted(),
        )
        assertEquals("a transaction written again is not the notification's RM1.00", lost.map { 100L }, rewritten.map { it.amountSen })
        assertEquals("a transaction was written twice", seeded, txns.size)
        assertEquals("the sheet's count of transactions to read again", lost.size, report.txnsReread)
        assertTrue(
            "the sheet does not say they will be read again: ${salvageSentences(report)}",
            salvageSentences(report).any { it.startsWith("${lost.size} transactions that could not be read will be read again") },
        )
    }

    /**
     * **`e04ab72`'s duplicate suspects, whose original is the row that will
     * not read.** A copy of an undecidable capture is filed as a suspect
     * naming it -- matched with a `PENDING` transaction, or as unmatched --
     * and a refresh names it too. Two earlier captures name later ones, as a
     * catch-up arrival can: one the unreadable original, one a capture that
     * reads. Every capture is kept, only links to the lost original are
     * cleared, and the file restores.
     */
    @Test(timeout = 120_000)
    fun duplicateSuspectsNamingAnUnreadableOriginalAreKeptAndTheFileRestores() {
        val seeded = 2_000
        val original = 1_000L
        val earlierToLost = 500L
        val earlierToKept = 501L
        val keptTarget = 1_900L
        var suspect = 0L
        var unmatched = 0L
        var refresh = 0L
        var suspectTxn = 0L
        val pages = seedLedger(seeded) { db, uncategorized ->
            // The shapes `ParsePass` writes for `Layer1Verdict.UndecidablePrior`
            // (`parse(capture, slotSuspectOf = verdict.priorId)`) and for a
            // refresh (`mark(UPDATE_OF, duplicateOf = priorId)`).
            suspect = db.rawCaptureDao().insert(
                bulkCapture(seeded + 1).copy(parseStatus = ParseStatus.MATCHED, matchedRuleId = "tng.payment.v1", duplicateOfId = original),
            )
            suspectTxn = db.txnDao().insert(
                txnFor(suspect, uncategorized).copy(
                    confidence = Confidence.REVIEW,
                    state = TxnState.PENDING,
                    pendingReason = PendingReason.DUPLICATE_SUSPECT,
                ),
            )
            unmatched = db.rawCaptureDao().insert(
                bulkCapture(seeded + 2).copy(parseStatus = ParseStatus.UNMATCHED, duplicateOfId = original),
            )
            refresh = db.rawCaptureDao().insert(
                bulkCapture(seeded + 3).copy(parseStatus = ParseStatus.UPDATE_OF, duplicateOfId = original),
            )
            db.openHelper.writableDatabase.execSQL("UPDATE raw_capture SET duplicate_of_id = $original WHERE id = $earlierToLost")
            db.openHelper.writableDatabase.execSQL("UPDATE raw_capture SET duplicate_of_id = $keptTarget WHERE id = $earlierToKept")
        }
        val leaf = pages.single { it.tree == "raw_capture" && it.type == "leaf" && original in it.lo..it.hi }
        val bystanders = listOf(earlierToLost, earlierToKept, keptTarget, suspect, unmatched, refresh)
        assertTrue("the damaged leaf holds ${bystanders.filter { it in leaf.ids }} too", bystanders.none { it in leaf.ids })
        Databases.reset()
        damage(leaf)

        val (report, bytes) = salvage()
        restore(bytes)

        assertEquals(
            "the captures read are not every one but the damaged leaf's",
            Unreadable.Counted(recovered = seeded + 3 - leaf.ids.size, unread = leaf.ids.size),
            report.unreadable["raw_capture"],
        )
        assertEquals("links to the unreadable original were not each cleared", 4, report.linksCleared)

        val captures = restored().rawCaptureDao()
        for (id in listOf(suspect, unmatched, refresh, earlierToLost)) {
            assertNull("capture $id still names the unreadable original", captures.byId(id).duplicateOfId)
        }
        assertEquals("a link to a capture that read was cleared", keptTarget, captures.byId(earlierToKept).duplicateOfId)
        val kept = restored().txnDao().byId(suspectTxn)
        assertEquals("the suspect's transaction, the only record of the money, did not survive", suspect, kept?.rawCaptureId)
        assertEquals("the suspect left the review inbox", PendingReason.DUPLICATE_SUSPECT, kept?.pendingReason)
    }

    /**
     * A `category` table that reads and has no `Uncategorized` -- renamed --
     * fails the import's last check. The file carries one.
     */
    @Test(timeout = 60_000)
    fun aLedgerWithoutUncategorizedGainsOneAndTheFileRestores() {
        freshDatabase(context).useDb { db ->
            seedOneOfEverything(db)
            db.openHelper.writableDatabase.execSQL("UPDATE category SET name = 'Sundry' WHERE name = '${Seed.UNCATEGORIZED}'")
        }
        Databases.reset()

        val (report, bytes) = salvage()
        restore(bytes)

        assertTrue("no Uncategorized was added", report.uncategorizedAdded)
        val names = restored().categoryDao().all().map { it.name }
        assertTrue("the restored categories are $names", Seed.UNCATEGORIZED in names && "Sundry" in names)
    }

    /**
     * **`category` itself unreadable.** Every transaction and learned rule
     * then names a category the file cannot carry: the transactions are kept
     * under `Uncategorized`, the rules left out, and the file restores.
     */
    @Test(timeout = 60_000)
    fun aLedgerWhoseCategoriesWillNotReadKeepsItsMoneyAndTheFileRestores() {
        lateinit var seeded: SeededLedger
        val pages = freshDatabase(context).useDb { db ->
            seeded = seedOneOfEverything(db)
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            layout(db)
        }
        val table = pages.single { it.tree == "category" }
        assertEquals("the category table spans more than its root, so this damages part of it", "leaf", table.type)
        Databases.reset()
        damage(table)

        val (report, bytes) = salvage()
        val imported = restore(bytes)

        assertTrue("the unreadable category table was not reported", "category" in report.lostWhole)
        assertEquals("transactions were not all filed under Uncategorized", seeded.txns.size, report.txnsUncategorized)
        assertEquals("learned rules naming a lost category were not left out", 2, report.rulesLeftOut)

        assertEquals("transactions restored", seeded.txns.size, imported.txns)
        val uncategorized = restored().categoryDao().requireUncategorizedId()
        assertEquals(
            "a restored transaction is not under Uncategorized",
            seeded.txns.map { uncategorized },
            seeded.txns.map { restored().txnDao().byId(it)?.categoryId },
        )
        assertEquals("the file's categories are not the standard ones", Seed.categories().map { it.name }, restored().categoryDao().all().map { it.name })
    }

    /**
     * **What changed while the salvage ran is not called unreadable.** It
     * runs outside a transaction, so capture writes beside it: here, between
     * the `category` read and the walk, a category is made, a transaction
     * moved into it and a learned merchant filed under it; and once the
     * `raw_capture` walk has ended, a capture arrives with its transaction,
     * one an earlier capture already names. Nothing is damaged, so "could
     * not be read" would be false of every one of them. The file still
     * restores.
     */
    @Test(timeout = 60_000)
    fun whatChangedDuringTheSalvageIsNotCalledUnreadable() {
        val seeded = 10
        seedLedger(seeded) { db, _ ->
            db.openHelper.writableDatabase.execSQL("UPDATE raw_capture SET duplicate_of_id = ${seeded + 1} WHERE id = 3")
        }
        Databases.reset()
        val categoriesRead = Seed.categories().size
        var categoryMade = false
        var captureArrived = false

        val (report, bytes) = salvage { written ->
            val db = Databases.shared(context)
            if (!categoryMade) {
                categoryMade = true
                db.categoryDao().insertAll(listOf(Category(name = "Made since", iconKey = "coffee", sortOrder = 99)))
                val since = db.categoryDao().all().single { it.name == "Made since" }.id
                db.txnDao().setCategory(1L, since, updatedAt = 3L)
                db.merchantRuleDao().insert(
                    MerchantRule(
                        matchType = MatchType.EXACT,
                        pattern = "KOPI SINCE",
                        merchantDisplay = "Kopi Since",
                        categoryId = since,
                        origin = RuleOrigin.LEARNED,
                        priority = 200,
                    ),
                )
            } else if (!captureArrived && written > categoriesRead) {
                // The first progress past the categories is the walk's one
                // page of captures: the learned merchant was left out.
                captureArrived = true
                val capture = db.rawCaptureDao().insert(bulkCapture(seeded))
                assertEquals("the arrival did not take the id capture 3 names", seeded + 1L, capture)
                db.txnDao().insert(txnFor(capture, db.categoryDao().requireUncategorizedId()))
            }
        }

        assertTrue("the hooks never ran: made=$categoryMade arrived=$captureArrived", categoryMade && captureArrived)
        assertEquals(
            "what arrived since was counted as unreadable: $report",
            listOf(0, 0, 0, 0),
            listOf(report.txnsLeftOut, report.linksCleared, report.txnsUncategorized, report.rulesLeftOut),
        )
        assertEquals(
            "what arrived since was not counted as such: $report",
            listOf(1, 1, 1, 1),
            listOf(report.txnsLeftOutSince, report.linksClearedSince, report.txnsUncategorizedSince, report.rulesLeftOutSince),
        )
        val said = salvageSentences(report)
        assertFalse("an undamaged ledger was told something could not be read: $said", said.any { "could not be read" in it })
        restore(bytes)
        assertNull("capture 3 still names a capture the file does not have", restored().rawCaptureDao().byId(3L).duplicateOfId)
    }

    /**
     * **Captures naming a later id are held to a bound.** Past
     * [SalvageJson.FORWARD_HELD] the rest are written at once with the link
     * cleared, and counted; the held ones keep theirs. Unbounded, a ledger of
     * catch-up copies held every one, text and all.
     */
    @Test(timeout = 60_000)
    fun pastTheBoundACaptureNamingALaterOneIsClearedAndCounted() {
        val over = 2
        val seeded = SalvageJson.FORWARD_HELD + over + 1
        val target = seeded.toLong()
        seedLedger(seeded) { db, _ ->
            db.openHelper.writableDatabase.execSQL("UPDATE raw_capture SET duplicate_of_id = $target WHERE id < $target")
        }
        Databases.reset()

        val (report, bytes) = salvage()
        restore(bytes)

        assertEquals("captures past the bound were not cleared and counted", over, report.linksUnheld)
        assertEquals("a link to a capture that read was called unreadable", 0, report.linksCleared)
        val captures = restored().rawCaptureDao()
        assertEquals("a held capture lost its link", target, captures.byId(1L).duplicateOfId)
        assertEquals("the last held capture lost its link", target, captures.byId(SalvageJson.FORWARD_HELD.toLong()).duplicateOfId)
        assertNull("a capture past the bound kept its link", captures.byId(target - 1).duplicateOfId)
        assertTrue(
            "the sheet does not say so: ${salvageSentences(report)}",
            "$over notifications marked as a copy of one that came after them are kept, without that mark." in salvageSentences(report),
        )
    }

    /**
     * **The key is read once a salvage, not at every reopen** -- a Keystore
     * load and an AES-GCM decrypt each, 7,410 of them over a damaged interior
     * page at 50,000. Shown by taking the key file away once the walk has
     * started: every damaged read after that reopens, and a reopen that
     * read the key again would find none.
     */
    @Test(timeout = 120_000)
    fun theKeyIsReadOnceASalvageRatherThanAtEveryReopen() {
        val seeded = 2_000
        val pages = seedLedger(seeded)
        val leaf = pages.filter { it.tree == "raw_capture" && it.type == "leaf" }.let { it[it.size / 2] }
        Databases.reset()
        damage(leaf)
        val keyFile = File(context.noBackupFilesDir, "db.key")
        val key = keyFile.readBytes()
        try {
            var taken = false
            val outcome = runCatching {
                salvage {
                    if (!taken) {
                        taken = true
                        assertTrue("the key file would not delete", keyFile.delete())
                    }
                }
            }
            assertTrue("a reopen read the key file again: ${outcome.exceptionOrNull()}", outcome.isSuccess)
            val (report, _) = outcome.getOrThrow()
            assertEquals(
                "the walk did not step the damaged leaf, so it did not reopen past it",
                Unreadable.Counted(recovered = seeded - leaf.ids.size, unread = leaf.ids.size),
                report.unreadable["raw_capture"],
            )
        } finally {
            keyFile.writeBytes(key)
        }
    }
}
