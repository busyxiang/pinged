package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.entity.TxnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `EXPLAIN QUERY PLAN` for the queries the DAOs actually issue, **including
 * their predicates**, asserting the **named index** rather than merely
 * `SEARCH`.
 *
 * Both halves matter. The month aggregate carries `state = 'COMMITTED' AND
 * is_excluded = 0` alongside its `local_date` range, and with those present
 * SQLite prefers `index_txn_state_occurred_at` and ignores `local_date`
 * entirely -- a plan on which `SEARCH` is still true. Spec 15.8 warns that plan
 * text is not stable across SQLite versions and that SQLCipher bundles its own
 * build, which is why these are substring assertions on index names: those are
 * Room's, and stable in a way plan wording is not.
 *
 * The fixture is populated, mixed in `state`, and its `local_date` values fall
 * inside the month the queries ask for. A plan asserted against an empty table,
 * or a range that matches no row, is a plan asserted against nothing.
 */
@RunWith(AndroidJUnit4::class)
class QueryPlanTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PingedDatabase

    @Before fun setUp() {
        db = freshDatabase(context)
        val cat = db.categoryDao().requireUncategorizedId()
        repeat(600) {
            db.txnDao().insert(
                sampleTxn(
                    amountSen = (it % 40) * 250L + 100,
                    occurredAt = SEPTEMBER + it * 3_600_000L,
                    categoryId = cat,
                    // A realistic mix, not one value: spec 7.1 sends a minority
                    // of transactions to the review inbox. With a single
                    // distinct `state` the planner's arithmetic is degenerate
                    // and the plans below stop meaning anything.
                    state = if (it % 50 == 0) TxnState.PENDING else TxnState.COMMITTED,
                ),
            )
        }
        repeat(300) {
            db.rawCaptureDao().insert(
                sampleCapture(hash = "h$it", sbnKey = "k$it", postedAt = it * 1_000L),
            )
        }
        // The month range has to actually select rows, or every plan below is
        // being asserted against a predicate that matches nothing.
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM txn WHERE local_date BETWEEN ? AND ?", MONTH)
            .use {
                it.moveToFirst()
                assertTrue(
                    "Fixture is wrong: no rows fall in the month the plans query",
                    it.getInt(0) > 100,
                )
            }
    }

    @After fun tearDown() = db.close()

    private fun plan(sql: String, vararg args: Any): String {
        val out = StringBuilder()
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { c ->
            while (c.moveToNext()) out.append(c.getString(c.columnCount - 1)).append('\n')
        }
        val text = out.toString()
        android.util.Log.i(OpenTest.REPORT_TAG, "PLAN [$sql]\n$text")
        return text
    }

    /**
     * [table] defaults to `txn` because that is what most of this class asks
     * about -- but it is a parameter now. Hardcoded, the scan half of this
     * assertion silently passed for any query over another table, so the seven
     * cases below that could not use the helper open-coded it, and four of
     * those kept only the index half.
     */
    private fun assertUsesIndex(index: String, plan: String, table: String = "txn") {
        assertTrue("Plan does not use $index:\n$plan", plan.contains(index))
        // "SCAN <table> USING INDEX ..." is an index scan and fine; a bare
        // "SCAN <table>" is the whole table.
        assertTrue("Plan contains a full table scan:\n$plan", !plan.contains("SCAN $table\n"))
    }

    private fun assertNoSort(plan: String) {
        assertTrue(
            "Plan needed a temp b-tree to order or group:\n$plan",
            !plan.contains("TEMP B-TREE"),
        )
    }

    // ---- the home list, TxnDao.recent ----------------------------------

    /**
     * `SELECT * FROM txn ORDER BY occurred_at DESC LIMIT ?`, the screen the
     * user opens most (spec 9.1). Before `txn(occurred_at)` existed this
     * planned as `SCAN txn` plus `USE TEMP B-TREE FOR ORDER BY`: the whole
     * table read and sorted to draw the first page, growing for as long as the
     * app is installed. Every other index carrying `occurred_at` has it behind
     * a leading equality column, so none of them could order an unfiltered
     * query.
     */
    @Test fun homeListDoesNotScanTheTableAndDoesNotSort() {
        val p = plan("SELECT * FROM txn ORDER BY occurred_at DESC LIMIT 50")
        assertUsesIndex("index_txn_occurred_at", p)
        assertNoSort(p)
    }

    // ---- the month list and the month aggregates -----------------------

    /**
     * **The month list, and the finding this class exists to record.**
     *
     * `local_date BETWEEN ? AND ?` is a range and `state = ?` is an equality, and
     * SQLite prefers an equality over a range -- so with both present it takes
     * `index_txn_state_occurred_at`, reads *every* `COMMITTED` transaction ever
     * captured to return one month, and sorts them. Adding `(local_date,
     * occurred_at)` does not change that on its own; statistics do, but not at a
     * time the app can arrange (see
     * [analyzeFixesTheNaivePlanButOnlyOnceTheConnectionReloads]).
     *
     * The fix is in the query. `+state` is SQLite's documented unary-plus: it makes
     * that one term unusable as an index seek without affecting its meaning.
     * Narrower than `INDEXED BY`, which forbids the planner from ever doing better
     * and hard-codes a Room-generated index name into hand-written SQL.
     *
     * `ORDER BY local_date DESC, occurred_at DESC` is the second half, and **a
     * correction to the brief**: no index here can order `occurred_at` across a
     * `local_date` *range*, since `(local_date, occurred_at)` orders `occurred_at`
     * only within one `local_date` and a month spans thirty. "No TEMP B-TREE on the
     * month list" is achievable only by asking for the order the index holds.
     *
     * Which is the order the screen wants anyway: `local_date` is derived from
     * `occurred_at` in the zone current at parse time (spec 15.7), so the two agree
     * except where they disagree for a reason -- and in those cases day-major order
     * is what a day-sectioned list (spec 9.1) requires, because `occurred_at`-major
     * order can interleave two days' rows and break the sections.
     */
    @Test fun monthListUsesTheCompositeIndexAndDoesNotSort() {
        val p = plan(MONTH_LIST, *MONTH_ARGS)
        assertUsesIndex("index_txn_local_date_occurred_at", p)
        assertNoSort(p)
    }

    /**
     * The naive form of the same query, asserted so the trap is on the record
     * rather than rediscovered. This is not a plan anyone should ship; it is
     * what the month list plans as if `+state` and the day-major ordering are
     * dropped, and the assertion below fails the moment that stops being true
     * -- at which point this test should be deleted and the one above relied
     * on instead.
     */
    @Test fun theNaiveMonthListStillTakesTheStateIndexAndSorts() {
        val p = plan(NAIVE_MONTH_LIST, *MONTH_ARGS)
        assertTrue(
            "The naive month list no longer prefers the state index. Good news: " +
                "delete this test and check whether monthListUsesTheCompositeIndex" +
                "AndDoesNotSort still needs its +state hint.\n$p",
            p.contains("index_txn_state_occurred_at"),
        )
        assertTrue("Expected a sort in the naive plan:\n$p", p.contains("TEMP B-TREE"))
    }

    /**
     * The daily subtotals of spec 15.3 ("aggregates in SQL, never in Kotlin"),
     * grouped by the index's own leading column, so no sort at all.
     */
    @Test fun theDayAggregateGroupsThroughTheCompositeIndexWithoutSorting() {
        val p = plan(
            "SELECT local_date, SUM(CASE WHEN direction = 'REFUND' THEN -amount_sen " +
                "ELSE amount_sen END) FROM txn WHERE local_date BETWEEN ? AND ? " +
                "AND +state = ? AND is_excluded = 0 GROUP BY local_date",
            *MONTH_ARGS,
        )
        assertUsesIndex("index_txn_local_date_occurred_at", p)
        assertNoSort(p)
    }

    /** The month total, spec 15.3's signed sum. No grouping, so no sort. */
    @Test fun theMonthTotalUsesTheCompositeIndexAndDoesNotSort() {
        val p = plan(
            "SELECT SUM(CASE WHEN direction = 'REFUND' THEN -amount_sen " +
                "ELSE amount_sen END) FROM txn WHERE local_date BETWEEN ? AND ? " +
                "AND +state = ? AND is_excluded = 0",
            *MONTH_ARGS,
        )
        assertUsesIndex("index_txn_local_date_occurred_at", p)
        assertNoSort(p)
    }

    /**
     * Category totals group by a column the range index does not carry, so the
     * `TEMP B-TREE FOR GROUP BY` here is the correct plan and is deliberately
     * not asserted away. Removing it would mean adding a
     * `(local_date, category_id)` index for one screen's aggregate over one
     * month of rows, which spec 15's volumes do not justify.
     */
    @Test fun categoryTotalsUseTheCompositeIndexForTheirRange() {
        val p = plan(
            "SELECT category_id, SUM(amount_sen) FROM txn WHERE local_date BETWEEN ? AND ? " +
                "AND +state = ? AND is_excluded = 0 GROUP BY category_id",
            *MONTH_ARGS,
        )
        assertUsesIndex("index_txn_local_date_occurred_at", p)
    }

    // ---- the review inbox, spec 7.1 ------------------------------------

    /**
     * Here the equality on `state` is the right driver -- `PENDING` is the
     * minority -- and `(state, occurred_at)` supplies the order too.
     */
    @Test fun reviewInboxDoesNotScanAndDoesNotSort() {
        val p = plan("SELECT * FROM txn WHERE state = ? ORDER BY occurred_at DESC", "PENDING")
        assertUsesIndex("index_txn_state_occurred_at", p)
        assertNoSort(p)
    }

    @Test fun theReviewInboxBadgeCountUsesTheSameIndex() {
        val p = plan("SELECT COUNT(*) FROM txn WHERE state = ?", "PENDING")
        assertUsesIndex("index_txn_state_occurred_at", p)
    }

    // ---- duplicate layer 2, TxnDao.findDuplicateSuspects ---------------

    /** The full predicate set, including the two clauses the old case omitted. */
    @Test fun duplicateLayerTwoUsesTheAmountWindowIndex() {
        val p = plan(
            "SELECT * FROM txn WHERE amount_sen = ? AND occurred_at BETWEEN ? AND ? " +
                "AND direction = ? AND (source_package IS NULL OR source_package != ?) " +
                "AND state != ?",
            1200, 0, Long.MAX_VALUE, "EXPENSE", "com.example", "REJECTED",
        )
        assertUsesIndex("index_txn_amount_sen_occurred_at", p)
    }

    // ---- the category foreign keys, spec 4's delete sheet --------------

    @Test fun theInUseCountsDoNotScanTheirTables() {
        val p = plan("SELECT COUNT(*) FROM txn WHERE category_id = ?", 1)
        assertUsesIndex("index_txn_category_id", p)
        val r = plan("SELECT COUNT(*) FROM merchant_rule WHERE category_id = ?", 1)
        assertTrue(
            "Plan does not use index_merchant_rule_category_id:\n$r",
            r.contains("index_merchant_rule_category_id"),
        )
    }

    // ---- raw_capture ---------------------------------------------------

    /** Duplicate layer 1 rule 2, now bounded at both ends. */
    @Test fun duplicateLayerOneRuleTwoUsesTheContentHashWindowIndex() {
        val p = plan(
            "SELECT * FROM raw_capture WHERE content_hash = ? AND posted_at >= ? " +
                "AND posted_at <= ?",
            "h1", 0, 999_999,
        )
        assertUsesIndex("index_raw_capture_content_hash_posted_at", p, "raw_capture")
    }

    /** Duplicate layer 1 rule 1, with the self-exclusion and the ordering. */
    @Test fun duplicateLayerOneRuleOneUsesTheSlotIndex() {
        val p = plan(
            "SELECT * FROM raw_capture WHERE sbn_key = ? AND content_hash = ? " +
                "AND id < ? ORDER BY id ASC LIMIT 1",
            "k1", "h1", 999_999,
        )
        assertUsesIndex("index_raw_capture_sbn_key", p, "raw_capture")
    }

    @Test fun stageTwoQueueDoesNotScanAndDoesNotSort() {
        val p = plan(
            "SELECT * FROM raw_capture WHERE parse_status = ? ORDER BY posted_at ASC LIMIT 20",
            "NEW",
        )
        assertTrue(
            "Plan does not use index_raw_capture_parse_status_posted_at:\n$p",
            p.contains("index_raw_capture_parse_status_posted_at"),
        )
        assertNoSort(p)
    }

    /**
     * Spec 15.5's keyset cursor. Measured before `(parse_status, id)` existed:
     * this took `(parse_status, posted_at)` and added `USE TEMP B-TREE FOR
     * ORDER BY`, so every chunk sorted the whole remaining candidate set --
     * which is worse than the `OFFSET` paging it replaces, not better.
     */
    @Test fun theKeysetCursorDoesNotSort() {
        val p = plan(
            "SELECT * FROM raw_capture WHERE parse_status = ? AND id > ? " +
                "ORDER BY id ASC LIMIT 20",
            "NEW", 0,
        )
        assertTrue(
            "Plan does not use index_raw_capture_parse_status_id:\n$p",
            p.contains("index_raw_capture_parse_status_id"),
        )
        assertNoSort(p)
    }

    // ---- lookups -------------------------------------------------------

    /** `CategoryDao.uncategorizedIdOrNull`, which was a `SCAN category`. */
    @Test fun theCategoryNameLookupDoesNotScan() {
        val p = plan("SELECT id FROM category WHERE name = ? LIMIT 1", Seed.UNCATEGORIZED)
        assertUsesIndex("index_category_name", p, "category")
    }

    /**
     * Learned-rule lookup, which runs per capture in stage two (spec 15.1).
     * `ORDER BY priority DESC` is how spec 4's "LEARNED always outranks
     * BUNDLED" is expressed; with only a bare `pattern` index it needed a temp
     * b-tree, and with `(pattern, priority)` SQLite walks the index backwards.
     */
    @Test fun theLearnedRuleLookupDoesNotSort() {
        val food = db.categoryDao().all().first().id
        db.insertMerchantRule("MCD KLCC", food)
        val p = plan(
            "SELECT * FROM merchant_rule WHERE pattern = ? ORDER BY priority DESC",
            "MCD KLCC",
        )
        assertTrue(
            "Plan does not use index_merchant_rule_pattern_priority:\n$p",
            p.contains("index_merchant_rule_pattern_priority"),
        )
        assertNoSort(p)
    }

    // ---- statistics ----------------------------------------------------

    /**
     * **Whether `ANALYZE` is needed, measured rather than assumed.**
     *
     * `ANALYZE` does fix the plan choice: `sqlite_stat1` records
     * `index_txn_state_occurred_at` as `600 300 1`, the equality selects half the
     * table, and the range wins on cost with no sort left over.
     *
     * **But the fix does not become visible when it is applied, which is the reason
     * not to depend on it.** The same query, in one session, in this order:
     *
     * 1. before `ANALYZE` -- `index_txn_state_occurred_at`, temp b-tree;
     * 2. immediately after `ANALYZE` on the write connection -- **unchanged on an
     *    Android 17 emulator, already reloaded on CI's Android 16**, because
     *    which pool connection serves that read is not the app's decision.
     *    Logged, never asserted; see the note at the step;
     * 3. after any read that touches `sqlite_stat1` -- the composite index, no sort.
     *
     * SQLite loads index statistics when a connection loads the schema, and
     * SQLCipher runs a connection pool behind one `SQLiteDatabase` (the same
     * mechanism `DatabaseFactory.configureConnection` covers for `PRAGMA
     * foreign_keys`). `ANALYZE` is a write, so a read served by a connection that
     * has not reloaded keeps planning without statistics already on disk. Nothing
     * in the app can force that reload, and which connection serves a read is not
     * the app's decision.
     *
     * `PRAGMA optimize`, the maintained form, needs a long-lived connection to hang
     * off before closing, which the listener process does not have. Statistics also
     * decay: `sqlite_stat1` written at 500 rows still steers the planner at 50,000.
     *
     * **So neither is added.** The `+state` hint costs nothing, cannot decay, does
     * not depend on which connection serves the query, and gives the same plan at
     * all three steps.
     *
     * One fact worth recording in case someone reaches for `ANALYZE` anyway: it
     * does **not** disturb Room's `onValidateSchema`, which validates only the
     * tables it declares. The reopen at the end of this test proves it for both
     * `sqlite_stat1` and `sqlite_stat4`.
     */
    @Test fun analyzeFixesTheNaivePlanButOnlyOnceTheConnectionReloads() {
        val naiveBefore = plan(NAIVE_MONTH_LIST, *MONTH_ARGS)
        val hintedBefore = plan(MONTH_LIST, *MONTH_ARGS)
        assertTrue(
            "Step 1: without statistics the naive form must take the state index",
            naiveBefore.contains("index_txn_state_occurred_at") &&
                naiveBefore.contains("TEMP B-TREE"),
        )

        db.openHelper.writableDatabase.execSQL("ANALYZE")

        // Step 2. Logged, never asserted, and the reason is the finding itself.
        //
        // Whether this read sees the new statistics depends on whether the pool
        // serves it from a secondary connection that has not reloaded the schema
        // or from the primary that just ran ANALYZE -- and which one it gets
        // depends on how many concurrent reads happened earlier in the process.
        // `WritePathTest.readPragmaOnTheWriteConnection` records the same
        // mechanism for `PRAGMA synchronous`: the same query returned 2 while the
        // pool had only its primary and 1 once it had opened a secondary.
        //
        // Asserted as an equality this passed on an Android 17 emulator and failed
        // on CI's Android 16, where the read came off the primary and saw the
        // statistics at once. That is not a regression to fix; it is the claim
        // this test exists to make, and asserting it turned an observation about
        // pool scheduling into a gate that fails by machine.
        //
        // The argument survives intact -- it is *strengthened* -- because "the fix
        // becomes visible at a moment nobody controls" is exactly why neither
        // ANALYZE nor PRAGMA optimize is used. Steps 1 and 3 pin the naive plan
        // moving; the hinted form below is identical at all three, which is the
        // whole case for preferring it.
        val naiveRightAfter = plan(NAIVE_MONTH_LIST, *MONTH_ARGS)
        android.util.Log.i(
            OpenTest.REPORT_TAG,
            "ANALYZE step 2: plan " +
                (if (naiveRightAfter == naiveBefore) "unchanged" else "already reloaded") +
                " -- $naiveRightAfter",
        )

        // Step 3. Resolving sqlite_stat1 by name forces the schema reload.
        db.openHelper.readableDatabase
            .query("SELECT tbl, idx, stat FROM sqlite_stat1 ORDER BY tbl, idx").use { c ->
                while (c.moveToNext()) {
                    android.util.Log.i(
                        OpenTest.REPORT_TAG,
                        "sqlite_stat1: ${c.getString(0)} / ${c.getString(1)} / ${c.getString(2)}",
                    )
                }
            }
        val naiveAfterReload = plan(NAIVE_MONTH_LIST, *MONTH_ARGS)
        assertUsesIndex("index_txn_local_date_occurred_at", naiveAfterReload)
        assertNoSort(naiveAfterReload)

        // The hinted form is the same plan at every one of the three steps,
        // which is the entire case for preferring it.
        val hintedAfter = plan(MONTH_LIST, *MONTH_ARGS)
        assertEquals("The +state form's plan moved under ANALYZE", hintedBefore, hintedAfter)
        assertUsesIndex("index_txn_local_date_occurred_at", hintedAfter)
        assertNoSort(hintedAfter)

        // And Room still opens the file with the stat tables sitting in it.
        db.close()
        db = DatabaseFactory.build(context)
        assertEquals(
            "Room's schema validation rejected a database that had been ANALYZEd",
            600,
            db.txnDao().countAll(),
        )
        assertUsesIndex(
            "index_txn_local_date_occurred_at",
            plan(MONTH_LIST, *MONTH_ARGS),
        )
    }

    private companion object {
        /**
         * 2026-09-02T00:00Z, so that 600 hourly rows land inside September
         * 2026 in any device zone the emulator might be set to.
         */
        const val SEPTEMBER = 1_788_307_200_000L
        val MONTH = arrayOf<Any>(20260901, 20260930)
        val MONTH_ARGS = arrayOf<Any>(20260901, 20260930, "COMMITTED")

        /**
         * The month list as it must be written. See
         * [monthListUsesTheCompositeIndexAndDoesNotSort] for both halves of
         * why: `+state` so the range drives the query, and day-major ordering
         * because no index can order `occurred_at` across a `local_date` range.
         */
        const val MONTH_LIST =
            "SELECT * FROM txn WHERE local_date BETWEEN ? AND ? AND +state = ? " +
                "AND is_excluded = 0 ORDER BY local_date DESC, occurred_at DESC"

        /** [MONTH_LIST] without the hint: the plan nobody should ship. */
        const val NAIVE_MONTH_LIST =
            "SELECT * FROM txn WHERE local_date BETWEEN ? AND ? AND state = ? " +
                "AND is_excluded = 0 ORDER BY local_date DESC, occurred_at DESC"
    }

    // ---- plans that were not pinned -------------------------------------

    /**
     * Rule 1's windowed form, which is the one the POSTED path actually calls
     * -- once per capture. Its unwindowed sibling was pinned and this was not,
     * so the extra `posted_at >= ?` was unverified against the same
     * `sbn_key`-led index.
     */
    @Test fun theWindowedSlotLookupStillUsesTheSlotIndex() {
        val p = plan(
            "SELECT * FROM raw_capture WHERE sbn_key = ? AND content_hash = ? " +
                "AND id < ? AND posted_at >= ? ORDER BY id ASC LIMIT 1",
            "k1", "h1", 999_999, 0,
        )
        assertUsesIndex("index_raw_capture_sbn_key", p, "raw_capture")
    }

    /**
     * The two import-time integrity checks. These are the only queries in the
     * module whose cost grows with the table -- a correlated NOT EXISTS per
     * row -- and spec 15.8 sizes the import fixture at 50,000 rows, so the
     * inner lookup has to stay a primary-key probe rather than a nested scan.
     *
     * The outer SCAN is expected and correct: both count every row.
     */
    @Test fun theDanglingRawCaptureIdCheckProbesByPrimaryKey() {
        val p = plan(
            "SELECT COUNT(*) FROM txn t WHERE t.raw_capture_id IS NOT NULL " +
                "AND NOT EXISTS (SELECT 1 FROM raw_capture r WHERE r.id = t.raw_capture_id)",
        )
        assertTrue("Inner lookup is not a primary-key probe:\n$p", p.contains("USING INTEGER PRIMARY KEY"))
        assertTrue("Inner lookup scans raw_capture:\n$p", !p.contains("SCAN raw_capture"))
    }

    @Test fun theDanglingDuplicateOfCheckProbesByPrimaryKey() {
        val p = plan(
            "SELECT COUNT(*) FROM raw_capture c WHERE c.duplicate_of_id IS NOT NULL " +
                "AND NOT EXISTS (SELECT 1 FROM raw_capture p WHERE p.id = c.duplicate_of_id)",
        )
        assertTrue("Inner lookup is not a primary-key probe:\n$p", p.contains("USING INTEGER PRIMARY KEY"))
    }
}
