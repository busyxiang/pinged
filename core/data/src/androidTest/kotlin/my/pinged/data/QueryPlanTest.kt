package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.TxnDao
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
 * Where the app has the query, its SQL comes from the DAO's own constants
 * through [bindable] rather than from a copy typed out here. A pinned plan is
 * only worth having if it is the plan of the string the app issues: four of
 * these cases once pinned a hand-written copy and stayed green while the real
 * queries drifted away from them.
 *
 * The one place that is still a proxy rather than the literal statement is
 * the feed, because Room wraps a `PagingSource` query before preparing it.
 * Both wrapped forms were measured and agree with the bare one; see
 * [theFeedTakesTheDayOrderedIndex].
 *
 * The fixture is populated, mixed in `state`, and its `local_date` values fall
 * inside the month the queries ask for. A plan asserted against an empty table,
 * or a range that matches no row, is a plan asserted against nothing. No
 * `ANALYZE` runs before the assertions, which is the app's condition too --
 * see [analyzeFixesTheNaivePlanButOnlyOnceTheConnectionReloads].
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
     * about, and it must be passed for anything else: with `txn` hardcoded the
     * scan half of this assertion passes for any query over another table
     * whatever its plan.
     */
    private fun assertUsesIndex(index: String, plan: String, table: String = "txn") {
        assertTrue("Plan does not use $index:\n$plan", plan.contains(index))
        // "SCAN <table> USING INDEX ..." is an index scan and fine; a bare
        // "SCAN <table>" is the whole table.
        assertTrue("Plan contains a full table scan:\n$plan", !plan.contains("SCAN $table\n"))
    }

    /**
     * A `@Query` string, ready for [plan].
     *
     * Room's `:name` placeholders become `?`, which is what the driver binds
     * by position and in the order they appear. Nothing about a plan depends
     * on how a bound parameter is spelled -- SQLite plans both as a value it
     * will not see until run time -- and the alternative is a second copy of
     * the SQL, which is the drift these cases exist to stop.
     */
    private fun bindable(sql: String): String =
        sql.replace(Regex(":[A-Za-z][A-Za-z0-9_]*"), "?")

    private fun assertNoSort(plan: String) {
        assertTrue(
            "Plan needed a temp b-tree to order or group:\n$plan",
            !plan.contains("TEMP B-TREE"),
        )
    }

    // ---- TxnDao.recent, and TxnDao.feed which replaced it ---------------

    /**
     * `SELECT * FROM txn ORDER BY occurred_at DESC LIMIT ?`, which is
     * `TxnDao.recent`. **The method name overstates its subject:** spec 9.1's
     * screen is `TxnDao.feed`, pinned in [theFeedTakesTheDayOrderedIndex], and
     * `recent()` has no production caller left. The plan is still worth pinning
     * because `txn(occurred_at)` exists for this query alone, so this case is
     * what would notice the index going away.
     *
     * Before that index existed this planned as `SCAN txn` plus `USE TEMP
     * B-TREE FOR ORDER BY`: the whole table read and sorted to return fifty
     * rows, growing for as long as the app is installed. Every other index
     * carrying `occurred_at` has it behind a leading equality column, so none
     * of them could order an unfiltered query.
     */
    @Test fun homeListDoesNotScanTheTableAndDoesNotSort() {
        val p = plan("SELECT * FROM txn ORDER BY occurred_at DESC LIMIT 50")
        assertUsesIndex("index_txn_occurred_at", p)
        assertNoSort(p)
    }

    /**
     * **`TxnDao.feed`, the query behind the screen the app opens on.**
     *
     * `ORDER BY local_date DESC, occurred_at DESC, id DESC` is what keeps a
     * date contiguous in a day-sectioned list, and it is only affordable
     * because `(local_date, occurred_at)` supplies the whole order as a
     * reverse scan: no temp b-tree, and the trailing `id` comes free from the
     * non-unique index's rowid tiebreak. Sorting this one in a temp b-tree
     * would mean sorting the whole table to draw the first page and again for
     * every page after it, which is the failure
     * [homeListDoesNotScanTheTableAndDoesNotSort] records for `recent()`.
     *
     * **This pins [TxnDao.FEED_SQL] itself, which is not literally what
     * SQLite is handed.** Room's `LimitOffsetPagingSource` wraps it, so the
     * two statements the app prepares are `SELECT * FROM ( <FEED_SQL> ) LIMIT
     * n OFFSET n` for a page and `SELECT COUNT(*) FROM ( <FEED_SQL> )` for the
     * count. Both were measured rather than assumed -- sqlite3 3.53.4 against
     * the frozen v1 DDL, 5000 rows over 209 distinct days -- and both take
     * `index_txn_local_date_occurred_at` with no temp b-tree, the count query
     * as a co-routine over the same scan. So the bare form is a sound proxy
     * for the wrapped ones; it is a proxy all the same, and a future clause
     * that the wrapper interacts with would have to be measured again.
     */
    @Test fun theFeedTakesTheDayOrderedIndex() {
        val p = plan(bindable(TxnDao.FEED_SQL))
        assertUsesIndex("index_txn_local_date_occurred_at", p)
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
     * `ORDER BY local_date DESC, occurred_at DESC` is the second half. No index here
     * can order `occurred_at` across a `local_date` *range*, since `(local_date,
     * occurred_at)` orders `occurred_at` only within one `local_date` and a month
     * spans thirty. "No TEMP B-TREE on the month list" is achievable only by asking
     * for the order the index holds.
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
     * **The three ledger aggregates, as `TxnDao` issues them.**
     *
     * [bindable] of the DAO's own constants, not a copy; see the class KDoc.
     *
     * `TEMP B-TREE FOR GROUP BY` is not asserted away here and is the correct
     * plan. All three group by `currency` (§4.5) and one also by
     * `category_id`, and no index on `txn` carries either behind `local_date`
     * -- removing the sort would mean a new index for one screen's aggregate
     * over one month of rows, which is a schema change on a frozen schema. The
     * sort is over the month's rows; the index choice is over the table's, and
     * that is the one that grows.
     */
    @Test fun theThreeLedgerAggregatesTakeTheRangeIndex() {
        val day = plan(bindable(TxnDao.DAY_TOTALS_SQL), *MONTH)
        assertUsesIndex("index_txn_local_date_occurred_at", day)

        val month = plan(bindable(TxnDao.MONTH_TOTALS_SQL), *MONTH)
        assertUsesIndex("index_txn_local_date_occurred_at", month)

        val byCategory = plan(bindable(TxnDao.MONTH_BY_CATEGORY_SQL), *MONTH, 3)
        assertUsesIndex("index_txn_local_date_occurred_at", byCategory)
    }

    /**
     * The same aggregate with the hint taken out: the plan the app would have
     * if anyone deleted a single `+`.
     *
     * Derived from the production string rather than written out, so it cannot
     * drift from the case above. Asserted the way
     * [theNaiveMonthListStillTakesTheStateIndexAndSorts] is: if this ever
     * stops being true the hint may be droppable, and the failure says so.
     */
    @Test fun withoutTheHintTheAggregateReadsEveryCommittedRow() {
        val p = plan(bindable(TxnDao.MONTH_TOTALS_SQL.replace("+state", "state")), *MONTH)
        assertTrue(
            "The unhinted aggregate no longer prefers the state index. Good " +
                "news: check whether theThreeLedgerAggregatesTakeTheRangeIndex " +
                "still needs its +state hint.\n$p",
            p.contains("index_txn_state_occurred_at"),
        )
    }

    // ---- the delete sheet's counts, spec 11.3 ---------------------------

    /**
     * **The two counts the delete sheet itemises, and the one plan in this
     * class that is a full scan on purpose.**
     *
     * Both filter `state != 'REJECTED'`, which is a negative condition SQLite
     * cannot range-scan, and `TxnDao.distinctMonthCount` then groups by
     * `local_date / 100`, an expression no index holds. The v1 schema is
     * frozen, so an index that would serve either is not available to add.
     *
     * Pinned anyway, because the KDoc on both methods asserts a cost and
     * `SettingsViewModel.wipeCounts` spends it on every resume of the settings
     * screen. This case is what notices the day either one stops being an
     * accepted scan and becomes an accidental one -- a `GROUP BY` added, a
     * second table joined -- on queries whose behavioural tests would not
     * blink.
     *
     * **The two are not the same scan, and the difference is their whole cost
     * difference.** `COUNT(*)` reads nothing but `state`, so it walks
     * `index_txn_state_occurred_at` as a covering index and never touches the
     * table: 0.195ms over 5,000 rows. `count(DISTINCT local_date / 100)` also
     * needs `local_date`, which that index does not carry, so it reads the
     * table itself and sorts the groups in a temp b-tree: 0.354ms. The bare
     * `SCAN txn\n` is asserted through [assertUsesIndex]'s own guard for the
     * covering case, because "SCAN txn USING COVERING INDEX ..." contains the
     * string "SCAN txn" and an assertion on that substring alone would pass
     * for either plan.
     */
    @Test fun theTwoDeleteSheetCountsScanAndAreAcceptedAtThat() {
        val months = plan(TxnDao.MONTH_COUNT_SQL)
        assertTrue(
            "The month count no longer reads the table itself. Good news: " +
                "re-measure it and correct distinctMonthCount's KDoc.\n$months",
            months.contains("SCAN txn\n"),
        )
        assertTrue(
            "The month count no longer needs a temp b-tree for count(DISTINCT). " +
                "Good news: re-measure it.\n$months",
            months.contains("TEMP B-TREE"),
        )

        val txns = plan(TxnDao.UNREJECTED_COUNT_SQL)
        assertUsesIndex("index_txn_state_occurred_at", txns)
        assertTrue("The transaction count left the covering index:\n$txns", txns.contains("COVERING"))
        assertNoSort(txns)
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

    /** The full predicate set: a plan pinned on a subset of it is another plan. */
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

    /**
     * **Duplicate layer one reads no row**, only `raw_capture(content_hash,
     * posted_at)` and `raw_capture(sbn_key)`, so a repeat of a notification
     * whose row is on a damaged page never reads that page. All three
     * lookups: rule 2, and rule 1 with and without its window.
     */
    @Test fun duplicateLayerOneReadsOnlyItsIndexes() {
        val lookups = mapOf(
            "rule 2" to plan(bindable(RawCaptureDao.CONTENT_REPEATS_SQL), "h1", 0, 999_999, "k1"),
            "rule 1" to plan(bindable(RawCaptureDao.EARLIER_IN_SLOT_SQL), "h1", 999_999, "k1", 7),
            "rule 1, windowed" to
                plan(bindable(RawCaptureDao.EARLIER_IN_SLOT_SINCE_SQL), "h1", 0, 999_999, 999_999, "k1", 7),
        )
        for ((rule, p) in lookups) {
            assertTrue(
                "$rule does not read the hash index alone:\n$p",
                p.contains("USING COVERING INDEX index_raw_capture_content_hash_posted_at"),
            )
            assertTrue(
                "$rule does not read the slot index alone:\n$p",
                p.contains("USING COVERING INDEX index_raw_capture_sbn_key"),
            )
            // A non-covering index, the primary key or a bare scan is the table.
            assertTrue("$rule reads raw_capture's rows:\n$p", !p.contains("USING INDEX") && !p.contains("PRIMARY KEY"))
            assertTrue("$rule scans:\n$p", !Regex("SCAN [hk]\\b(?! USING COVERING)").containsMatchIn(p))
        }
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
     * **Stage two's claim reads no row**, only the queue's index, whose
     * entries carry the rowid: that is what lets a capture on a damaged page
     * fail its own read and nothing else. **And seeks to its cursor**, with
     * no sort for the `id` after `posted_at`, which is the index's rowid.
     */
    @Test fun stageTwoClaimsFromTheIndexAlone() {
        val p = plan(bindable(RawCaptureDao.CLAIM_IDS_SQL), "NEW", 0, 0, 0, 50)
        assertTrue(
            "Stage two's claim is not answered from its covering index:\n$p",
            p.contains("USING COVERING INDEX index_raw_capture_parse_status_posted_at (parse_status=? AND posted_at>?)"),
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
     * has not reloaded keeps planning without statistics already on disk, and
     * nothing in the app can force that reload.
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
        // this test exists to make, and asserting it turns pool scheduling into a
        // gate that fails by machine.
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

    // ---- the import integrity checks ----------------------------------

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
