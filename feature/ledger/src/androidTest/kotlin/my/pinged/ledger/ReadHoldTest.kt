package my.pinged.ledger

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.capture.Corrections
import my.pinged.capture.Graph
import my.pinged.capture.GuardedRunner
import my.pinged.data.Databases
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.ledger.corrections.CorrectionsViewModel
import my.pinged.ledger.corrections.toState
import my.pinged.ledger.sources.SourcesViewModel
import my.pinged.ledger.sources.toState
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a screen's read holds of the ledger, against the real database: CLAUDE.md's
 * bound is that no lease outlasts a gate, and the longest a gate was measured to
 * leave up is 66ms. A transaction holds Room's one connection and `Databases`'
 * monitor for as long as its body runs, so a capture, stage two and a
 * replacement for damage all wait for it.
 *
 * Measured by wrapping the production runner and timing each transaction's body.
 * A JVM test cannot say it: the cost is the size of the ledger and of SQLCipher's
 * scans, which an in-memory database on Robolectric does not reproduce.
 */
@RunWith(AndroidJUnit4::class)
class ReadHoldTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Before fun startFromTheSeed() = app.discardTheDatabase()

    @After fun leaveNothingBehind() = app.discardTheDatabase()

    /** [GuardedRunner], with the length of every transaction's body recorded in milliseconds. */
    private class TimedRunner(private val inner: ReadRunner) : ReadRunner {
        val transactions = CopyOnWriteArrayList<Long>()

        override suspend fun <T> read(what: String, body: (PingedDatabase) -> T): Outcome<T> =
            inner.read(what) { db ->
                val began = System.nanoTime()
                try {
                    body(db)
                } finally {
                    transactions += (System.nanoTime() - began) / 1_000_000
                }
            }

        override suspend fun <T> write(what: String, body: suspend (PingedDatabase) -> T): Outcome<T> =
            inner.write(what, body)
    }

    private val db get() = Databases.shared(app).openHelper.writableDatabase

    /**
     * [copies] more rows like [seed], by SQL: a thousand inserts through the
     * DAO would be the test's whole running time. Each takes `sbn_key` and
     * `content_hash` of its own, and [sourceExpr] says whose capture it is.
     */
    private fun cloneCaptures(seed: Long, copies: Int, sourceExpr: String, postedExpr: String, withTxn: Boolean) {
        db.execSQL(
            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $copies) " +
                "INSERT INTO raw_capture (source_package, posted_at, when_millis, captured_at, sbn_key, notif_id, " +
                "notif_tag, user_handle, channel_id, flags, arrival, title, text, big_text, sub_text, extras_json, " +
                "content_hash, parse_status, rejected_by_rule_id, matched_rule_id, reject_collision_id, pack_version, " +
                "duplicate_of_id, user_reject_rule_id) " +
                "SELECT $sourceExpr, $postedExpr, c.when_millis, c.captured_at, 'hold|' || n.i, c.notif_id, c.notif_tag, " +
                "c.user_handle, c.channel_id, c.flags, c.arrival, c.title, c.text, c.big_text, c.sub_text, c.extras_json, " +
                "'hold' || n.i, c.parse_status, c.rejected_by_rule_id, c.matched_rule_id, c.reject_collision_id, " +
                "c.pack_version, NULL, NULL FROM raw_capture c, n WHERE c.id = $seed",
        )
        if (!withTxn) return
        db.execSQL(
            "INSERT INTO txn (raw_capture_id, amount_sen, currency, direction, occurred_at, local_date, merchant_raw, " +
                "merchant_display, merchant_key, category_id, source_package, source_label, confidence, state, " +
                "pending_reason, is_excluded, exclusion_reason, note, user_edited, created_at, updated_at) " +
                "SELECT r.id, t.amount_sen, t.currency, t.direction, t.occurred_at, t.local_date, t.merchant_raw, " +
                "t.merchant_display, t.merchant_key, t.category_id, t.source_package, t.source_label, t.confidence, " +
                "t.state, t.pending_reason, t.is_excluded, t.exclusion_reason, t.note, 0, t.created_at, t.updated_at " +
                "FROM raw_capture r, txn t WHERE r.sbn_key LIKE 'hold|%' AND t.raw_capture_id = $seed",
        )
    }

    private fun seedCapture(packVersion: Int, matchedRule: String?, status: ParseStatus): Long {
        val now = System.currentTimeMillis()
        return Databases.rawCaptureDao(app).insert(
            RawCapture(
                sourcePackage = "my.com.tngdigital.ewallet",
                postedAt = now - 5_000L,
                whenMillis = null,
                capturedAt = now - 5_000L,
                sbnKey = "hold-seed",
                notifId = 1,
                notifTag = null,
                userHandle = 0,
                channelId = null,
                flags = 0,
                arrival = Arrival.POSTED,
                title = "Touch 'n Go",
                text = "Payment of RM32.00 to KEDAI HOLD successful",
                bigText = null,
                subText = null,
                extrasJson = null,
                contentHash = "hold-seed",
                parseStatus = status,
                matchedRuleId = matchedRule,
                packVersion = packVersion,
            ),
        )
    }

    /**
     * Awaiting captures: each at the previous pack version with the amount the
     * older pack read (RM0.32, for the current pack's RM32.00), so every one
     * is a correction offered and none is applied unasked. A thousand is far
     * past what a real ledger leaves awaiting, and 4x what one transaction
     * over the whole list could hold under the bound.
     */
    @Test fun aThousandCorrectionsAwaitingAreReadWithNoTransactionNearTheBound() = runBlocking {
        val matcher = Graph.ruleMatcher()
        val seed = seedCapture(matcher.packVersion - 1, "tng-payment-v1", ParseStatus.MATCHED)
        val category = Databases.categoryDao(app).requireUncategorizedId()
        Databases.txnDao(app).insert(ledgerTxn(amountSen = 32, categoryId = category, rawCaptureId = seed))
        cloneCaptures(seed, copies = 999, sourceExpr = "c.source_package", postedExpr = "c.posted_at - n.i", withTxn = true)
        while (Corrections.sweep(app, Databases.rawCaptureDao(app), matcher).more) { }
        val runner = TimedRunner(GuardedRunner(app))
        val model = CorrectionsViewModel(app, runner)

        model.read.refreshed()

        val state = model.read.state.value.toState()
        assertEquals("The list was not read, so there is no length to hold it to", 1_000, state.items.size)
        assertTrue("The read took no transaction at all, so it measured nothing of the database", runner.transactions.isNotEmpty())
        assertTrue(
            "A transaction held Room's connection for ${runner.transactions.max()}ms over 1,000 awaiting " +
                "captures (${runner.transactions}), past the 66ms a lease may leave up",
            runner.transactions.max() < 66,
        )
    }

    /**
     * Ten enabled sources all still speaking, over 20,000 captures: the scan
     * `SourceGaps.refresh` makes per source is what a transaction would hold.
     */
    @Test fun anAllowListReadOverTenSpeakingSourcesHoldsNoTransactionNearTheBound() = runBlocking {
        val now = System.currentTimeMillis()
        val sources = Databases.captureSourceDao(app)
        val packages = (0 until 10).map { "com.example.hold.s$it" }
        packages.forEach { pkg ->
            sources.insertIfNew(CaptureSource(pkg = pkg, label = pkg, enabled = false, firstSeenAt = now - 1_000L))
            sources.setEnabled(pkg, true)
            sources.setLastNotificationAt(pkg, now - 3_600_000L)
        }
        val seed = seedCapture(0, null, ParseStatus.MATCHED)
        cloneCaptures(
            seed,
            copies = 20_000,
            sourceExpr = "'com.example.hold.s' || (n.i % 10)",
            postedExpr = "${now - 3_600_000L} - (n.i % 100000) * 1000",
            withTxn = false,
        )
        val runner = TimedRunner(GuardedRunner(app))
        val model = SourcesViewModel(app, runner)

        model.read.refreshed()

        val state = model.read.state.value.toState()
        assertEquals("The allow-list was not read", packages.toSet(), state.suggested.map { it.pkg }.toSet())
        assertEquals(
            "SourceGaps.refresh did not scan the captures, so nothing was measured of what it costs",
            2_000,
            sources.byPackage(packages.first())?.expectedMonthlyCount,
        )
        assertTrue(
            "A transaction held Room's connection for ${runner.transactions.maxOrNull()}ms over ten sources " +
                "and 20,000 captures (${runner.transactions}), past the 66ms a lease may leave up",
            (runner.transactions.maxOrNull() ?: 0L) < 66,
        )
    }
}
