# Pinged Ledger Milestone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the transactions Pinged already records visible, as a paged chronological list that becomes the app's home screen.

**Architecture:** Four new reads on the existing schema — a Room `PagingSource` for the feed, plus three aggregates that are deliberately *not* summed from loaded rows. Two destinations under a Navigation 3 back stack, each with a real `androidx.lifecycle.ViewModel`. The capture-stopped banner sits above the navigation host so it shows on either screen.

**Tech Stack:** Kotlin 2.4.10, Compose (BOM 2026.08.00 → Compose 1.12.0), Room 2.8.4 + `room-paging`, Paging 3, Navigation 3 `1.1.7`, SQLCipher 4.17.0, JDK 25, `compileSdk 37` / `minSdk 27`.

**Spec:** `docs/superpowers/specs/2026-09-07-pinged-ledger-milestone-design.md` (and `2026-09-02-pinged-design.md`, whose section numbers both documents cite as `§`)

## Global Constraints

- **Schema v1 is frozen.** No task changes an entity, adds an index, or adds a `@DatabaseView`. Any of those moves Room's identity hash; CI regenerates the schema and fails on drift. If a task appears to need one, stop — the scope has grown into search or filters, which are out.
- **Never `\d`, `\w` or `\s` in a pattern.** `java.util.regex` reads them as ASCII, ICU on Android reads them as Unicode classes, and a JVM test cannot see the difference. Nothing in this plan should need a regex; if one appears, use explicit classes.
- **Money is `Long` sen.** No `BigDecimal`, no `Double`, no floating point anywhere in an aggregate.
- **`:core:parse` stays Android-free.** A Gradle check enforces it. Nothing in this milestone belongs there.
- **Do not rename or move `PingedNotificationListener`.** The notification-access grant is keyed to its flattened `ComponentName`.
- **No whole-row upserts on `capture_source` or `capture_day`.** Every mutation is a targeted `UPDATE` naming its own column.
- **Excluded and pending rows never enter a total** (§8), and they are always still on screen. There is no review inbox in this milestone, so a `PENDING` row the ledger hides is money that exists in the database and nowhere else.
- **A gap in capture is never drawn as a zero** (§8). The month total greys out and is labelled do-not-trust when the month contains days capture was not alive for.
- **Totals group by currency.** Never one `SUM` across currencies.
- **Verification standard:** a behaviour with no falsifying test has not landed. Every test in this plan has a step that reverts the implementation and confirms the test fails *for its own reason*.
- **One KDoc per declaration.** Two `/** */` blocks in a row bind only the last.
- **Comments** record invariants, spec references, and the measurement behind a constant. They do not narrate history — that goes in the commit message.
- **Commit messages** are a concise summary, about why rather than what: a subject line, then a short paragraph giving the reason, what was measured, and anything left undone. Under about fifteen lines. Never claim a test count or a verification you did not run. End every commit message with:
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`

**Commands**

    ANDROID_HOME=/home/ray/Android/Sdk ./gradlew test
    ANDROID_HOME=/home/ray/Android/Sdk ./gradlew connectedDebugAndroidTest   # needs a device
    ANDROID_HOME=/home/ray/Android/Sdk ./gradlew test lint :app:assembleDebug   # what CI runs

Count tests out of the result XML, never off "BUILD SUCCESSFUL":

    python3 - <<'PY'
    import glob, xml.etree.ElementTree as ET
    t=f=e=0
    for p in glob.glob('*/*/build/outputs/androidTest-results/connected/**/*.xml', recursive=True) + \
             glob.glob('app/build/outputs/androidTest-results/connected/**/*.xml', recursive=True):
        r=ET.parse(p).getroot()
        t+=int(r.get('tests') or 0); f+=int(r.get('failures') or 0); e+=int(r.get('errors') or 0)
    print(f"tests={t} failures={f} errors={e}")
    PY

---

## File Structure

**`:core:data`** — the four reads. No new files beyond projections and tests.

| File | Responsibility |
|---|---|
| `core/data/src/main/kotlin/my/pinged/data/dao/TxnDao.kt` (modify) | The feed `PagingSource`, day subtotals, month total, month-by-category, and the category `UPDATE` |
| `core/data/src/main/kotlin/my/pinged/data/dao/LedgerProjections.kt` (create) | The four `data class` row shapes the aggregates return. Separate from `TxnDao` so the DAO file stays a list of queries |
| `core/data/src/main/kotlin/my/pinged/data/dao/CaptureDayDao.kt` (modify) | One count query behind the do-not-trust rule |
| `core/data/src/androidTest/kotlin/my/pinged/data/Fixtures.kt` (modify) | `sampleTxn` gains `isExcluded`, `exclusionReason`, `currency` |
| `core/data/src/androidTest/kotlin/my/pinged/data/LedgerAggregateTest.kt` (create) | What counts and what does not |
| `core/data/src/androidTest/kotlin/my/pinged/data/LedgerFeedTest.kt` (create) | Order, stability, membership, invalidation |
| `core/data/src/androidTest/kotlin/my/pinged/data/MonthTrustTest.kt` (create) | The capture-gap rule |

**`:feature:ledger`** — the screen.

| File | Responsibility |
|---|---|
| `.../ledger/home/LedgerViewModel.kt` (create) | Paging flow, day-subtotal map, month summary, trust flag |
| `.../ledger/home/LedgerState.kt` (create) | The five render states and the row/header item type |
| `.../ledger/home/LedgerScreen.kt` (create) | The list, the pinned summary, the empty and unavailable states |
| `.../ledger/home/CategoryPicker.kt` (create) | Manual assignment sheet |
| `.../ledger/sources/SourcesViewModel.kt` (modify) | Becomes a real `ViewModel` |

**`:app`** — wiring only, as CLAUDE.md says.

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/my/pinged/Destinations.kt` (create) | The two `@Serializable` Nav3 keys |
| `app/src/main/kotlin/my/pinged/MainActivity.kt` (modify) | Back stack, `NavDisplay`, the banner above it, the database-open ordering |
| `app/src/androidTest/kotlin/my/pinged/LaunchTest.kt` (modify) | Home is the ledger now |
| `gradle/libs.versions.toml`, `*/build.gradle.kts` (modify) | Dependencies |

---

### Task 1: Day subtotals and the month summary

The aggregates first, because every honesty rule in §8 lives in their `WHERE` clause and the screen is meaningless if they are wrong.

**Files:**
- Create: `core/data/src/main/kotlin/my/pinged/data/dao/LedgerProjections.kt`
- Modify: `core/data/src/main/kotlin/my/pinged/data/dao/TxnDao.kt`
- Modify: `core/data/src/androidTest/kotlin/my/pinged/data/Fixtures.kt`
- Test: `core/data/src/androidTest/kotlin/my/pinged/data/LedgerAggregateTest.kt`

**Interfaces:**
- Consumes: `sampleTxn(...)` and `freshDatabase()` from `Fixtures.kt`; `LocalDate(val yyyymmdd: Int)` and `LocalDates.of(epochMillis, zone)` from `my.pinged.data`.
- Produces:
  - `data class DayTotal(val localDate: LocalDate, val currency: String, val netSen: Long)`
  - `data class CurrencyTotal(val currency: String, val netSen: Long)`
  - `data class CategoryTotal(val categoryId: Long, val currency: String, val netSen: Long)`
  - `TxnDao.dayTotals(from: LocalDate, to: LocalDate): List<DayTotal>`
  - `TxnDao.monthTotals(from: LocalDate, to: LocalDate): List<CurrencyTotal>`
  - `TxnDao.monthByCategory(from: LocalDate, to: LocalDate, limit: Int): List<CategoryTotal>`

- [ ] **Step 1: Give the fixture builder the three parameters the honesty rules need**

`sampleTxn` currently cannot produce an excluded row or a non-MYR row, so none of the tests below can be written. Add to its parameter list, after `direction`:

```kotlin
    isExcluded: Boolean = false,
    exclusionReason: ExclusionReason? = if (isExcluded) ExclusionReason.USER else null,
    currency: String = "MYR",
```

and into the `Txn(` construction, after `sourceLabel = null,`:

```kotlin
    isExcluded = isExcluded,
    exclusionReason = exclusionReason,
    currency = currency,
```

Add `import my.pinged.parse.ExclusionReason` to the file's imports.

- [ ] **Step 2: Write the failing test**

Create `core/data/src/androidTest/kotlin/my/pinged/data/LedgerAggregateTest.kt`:

```kotlin
package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * What enters a total, and what does not.
 *
 * §8: "Excluded and pending rows never enter a total." Every assertion here is
 * that rule from a different direction, because the failure it prevents is a
 * number on screen that looks right.
 */
@RunWith(AndroidJUnit4::class)
class LedgerAggregateTest {
    private lateinit var db: PingedDatabase

    /** Fixed so a day boundary is a property of the test, not of the runner's clock. */
    private val utc = ZoneId.of("UTC")

    /** 2026-09-07T00:00:00Z and 2026-09-08T00:00:00Z. */
    private val day1 = 1_788_739_200_000L
    private val day2 = day1 + 86_400_000L

    private val sept = LocalDate(20260901) to LocalDate(20260930)

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private fun uncategorized() = db.categoryDao().requireUncategorizedId()

    @Test fun aDayWhoseOnlyTransactionsAreExcludedHasNoSubtotalAndStillHasRows() {
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = uncategorized(), isExcluded = true),
        )

        val totals = db.txnDao().dayTotals(sept.first, sept.second)

        assertTrue(
            "An excluded row produced a subtotal, so the day reads as money spent " +
                "that §8 says must stay out of the arithmetic: $totals",
            totals.isEmpty(),
        )
        assertEquals("The row itself must still exist to be shown", 1, db.txnDao().countAll())
    }

    @Test fun aRefundReducesItsDayRatherThanAddingToIt() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = cat))
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 2_000L, categoryId = cat, direction = Direction.REFUND),
        )

        val totals = db.txnDao().dayTotals(sept.first, sept.second)

        assertEquals(1, totals.size)
        assertEquals(
            "A REFUND was added instead of subtracted, so a refunded purchase " +
                "reads as twice the spending",
            3_000L, totals.single().netSen,
        )
    }

    @Test fun aPendingRowIsInNeitherTotal() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = cat))
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 9_900L, categoryId = cat, state = TxnState.PENDING),
        )

        assertEquals(5_000L, db.txnDao().dayTotals(sept.first, sept.second).single().netSen)
        assertEquals(5_000L, db.txnDao().monthTotals(sept.first, sept.second).single().netSen)
    }

    @Test fun twoCurrenciesProduceTwoLinesAndNeverOneSum() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 5_000L, categoryId = cat))
        db.txnDao().insert(
            sampleTxn(occurredAt = day1, amountSen = 700L, categoryId = cat, currency = "SGD"),
        )

        val totals = db.txnDao().monthTotals(sept.first, sept.second).sortedBy { it.currency }

        assertEquals("Currencies were summed together", 2, totals.size)
        assertEquals(listOf("MYR", "SGD"), totals.map { it.currency })
        assertEquals(5_000L, totals[0].netSen)
        assertEquals(700L, totals[1].netSen)
    }

    @Test fun dayTotalsAreKeyedByLocalDateNotByOccurredAt() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 1_000L, categoryId = cat))
        db.txnDao().insert(sampleTxn(occurredAt = day2, amountSen = 2_000L, categoryId = cat))

        val totals = db.txnDao().dayTotals(sept.first, sept.second).sortedBy { it.localDate.yyyymmdd }

        assertEquals(2, totals.size)
        assertEquals(1_000L, totals[0].netSen)
        assertEquals(2_000L, totals[1].netSen)
    }

    @Test fun theMonthSummaryRanksCategoriesAndCapsThem() {
        val cats = db.categoryDao().all().filter { !it.isProtected }.take(4).map { it.id }
        assertTrue("The seed did not provide four unprotected categories", cats.size == 4)
        cats.forEachIndexed { i, id ->
            db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = (i + 1) * 1_000L, categoryId = id))
        }

        val top = db.txnDao().monthByCategory(sept.first, sept.second, limit = 3)

        assertEquals(3, top.size)
        assertEquals(
            "Categories were not ordered by size, so the summary names the wrong three",
            listOf(4_000L, 3_000L, 2_000L), top.map { it.netSen },
        )
    }
}
```

- [ ] **Step 3: Run it and confirm it fails for the right reason**

```bash
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.LedgerAggregateTest
```

Expected: compilation failure — `dayTotals`, `monthTotals` and `monthByCategory` do not exist. That is the correct first failure; a test that compiled here would mean the queries already existed.

- [ ] **Step 4: Add the projections**

Create `core/data/src/main/kotlin/my/pinged/data/dao/LedgerProjections.kt`:

```kotlin
package my.pinged.data.dao

import my.pinged.data.LocalDate

/**
 * One day's net spending, in one currency.
 *
 * Read by the list's day header and never summed from the rows the header sits
 * above: a page boundary can fall mid-day (§9.1), so a subtotal derived from
 * loaded rows is short by whatever is on the next page and short silently.
 */
data class DayTotal(
    val localDate: LocalDate,
    val currency: String,
    val netSen: Long,
)

/** A period total, per currency. Never one row across currencies. */
data class CurrencyTotal(
    val currency: String,
    val netSen: Long,
)

/** A period total for one category, per currency, for the pinned summary's top three. */
data class CategoryTotal(
    val categoryId: Long,
    val currency: String,
    val netSen: Long,
)
```

- [ ] **Step 5: Add the three queries**

In `TxnDao.kt`, add these imports:

```kotlin
import my.pinged.data.LocalDate
```

and add the queries to the interface:

```kotlin
    /**
     * Net spending per day, for the list's day headers.
     *
     * `state = 'COMMITTED' AND is_excluded = 0` is §8's "excluded and pending
     * rows never enter a total". The identical predicate is repeated in
     * [monthTotals] and [monthByCategory] rather than shared through a constant,
     * because Room's processor reads `@Query` as a literal and a
     * `@DatabaseView` would move the schema's identity hash. What guarantees
     * they agree is behavioural, not textual: `LedgerAggregateTest` asserts the
     * same excluded and pending rows are absent from all three.
     *
     * Signed, so a `REFUND` reduces its day. Adding it would report a refunded
     * purchase as twice the spending.
     *
     * Grouped by `currency` as well as `local_date` (§4.5 of the milestone
     * design): the pack emits only MYR today, so a bare `SUM` would be correct
     * now and silently wrong at the first non-MYR rule.
     *
     * Served by `index_txn_local_date_occurred_at`.
     */
    @Query(
        """
        SELECT local_date AS localDate, currency,
               SUM(CASE WHEN direction = 'EXPENSE' THEN amount_sen ELSE -amount_sen END) AS netSen
        FROM txn
        WHERE state = 'COMMITTED' AND is_excluded = 0
          AND local_date BETWEEN :from AND :to
        GROUP BY local_date, currency
        """,
    )
    fun dayTotals(from: LocalDate, to: LocalDate): List<DayTotal>

    /** [dayTotals]'s predicate over a whole period, for the pinned summary's headline. */
    @Query(
        """
        SELECT currency,
               SUM(CASE WHEN direction = 'EXPENSE' THEN amount_sen ELSE -amount_sen END) AS netSen
        FROM txn
        WHERE state = 'COMMITTED' AND is_excluded = 0
          AND local_date BETWEEN :from AND :to
        GROUP BY currency
        """,
    )
    fun monthTotals(from: LocalDate, to: LocalDate): List<CurrencyTotal>

    /**
     * The summary's top categories, largest first.
     *
     * `LIMIT` is a parameter rather than the literal 3 §9.1 asks for, so the
     * caller states how many it is going to draw and the query cannot silently
     * disagree with the layout.
     */
    @Query(
        """
        SELECT category_id AS categoryId, currency,
               SUM(CASE WHEN direction = 'EXPENSE' THEN amount_sen ELSE -amount_sen END) AS netSen
        FROM txn
        WHERE state = 'COMMITTED' AND is_excluded = 0
          AND local_date BETWEEN :from AND :to
        GROUP BY category_id, currency
        ORDER BY netSen DESC
        LIMIT :limit
        """,
    )
    fun monthByCategory(from: LocalDate, to: LocalDate, limit: Int): List<CategoryTotal>
```

- [ ] **Step 6: Run the test and confirm it passes**

```bash
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.LedgerAggregateTest
```

Expected: 6 tests, 0 failures. Read the count out of the result XML, not the Gradle line.

- [ ] **Step 7: Falsify each guard, one at a time**

For each of the four mutations below: apply it, run the class, confirm **which** test fails and that its message is about its own subject, then revert. A mutation that fails nothing means the test is not testing what it claims.

| Mutation in `dayTotals`/`monthTotals` | Must fail |
|---|---|
| drop `AND is_excluded = 0` | `aDayWhoseOnlyTransactionsAreExcluded...` |
| drop `state = 'COMMITTED' AND` | `aPendingRowIsInNeitherTotal` |
| replace the `CASE` with `SUM(amount_sen)` | `aRefundReducesItsDay...` |
| drop `, currency` from the `GROUP BY` | `twoCurrenciesProduceTwoLines...` |

And in `monthByCategory`: drop `ORDER BY netSen DESC` → `theMonthSummaryRanksCategories...` must fail. Note this one may pass by accident depending on the plan SQLite picks, so if it does not fail, the test needs a fixture whose insertion order differs from its size order; fix the test rather than accepting the pass.

- [ ] **Step 8: Confirm the schema did not move**

```bash
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew :core:data:connectedDebugAndroidTest
```

Expected: the existing schema and enum-vocabulary guards still pass. Adding a `@Query` must not change Room's identity hash; if `SchemaConstraintTest` or the schema-drift check fails, something in this task touched an entity and must be reverted.

- [ ] **Step 9: Commit**

```bash
git add core/data/src/main/kotlin/my/pinged/data/dao/LedgerProjections.kt \
        core/data/src/main/kotlin/my/pinged/data/dao/TxnDao.kt \
        core/data/src/androidTest/kotlin/my/pinged/data/Fixtures.kt \
        core/data/src/androidTest/kotlin/my/pinged/data/LedgerAggregateTest.kt
git commit
```

The message should say what the predicate is and why it is duplicated rather than shared, that the agreement between the three queries is guaranteed behaviourally, and which mutations were run to falsify each test.

---

### Task 2: The feed, as a Room `PagingSource`

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/data/build.gradle.kts`
- Modify: `core/data/src/main/kotlin/my/pinged/data/dao/TxnDao.kt`
- Test: `core/data/src/androidTest/kotlin/my/pinged/data/LedgerFeedTest.kt`

**Interfaces:**
- Consumes: `sampleTxn(...)`, `freshDatabase()`, `TxnDao`.
- Produces: `TxnDao.feed(): PagingSource<Int, Txn>`

- [ ] **Step 1: Add `room-paging`**

In `gradle/libs.versions.toml`, under `[libraries]` beside the other Room entries:

```toml
androidx-room-paging = { group = "androidx.room", name = "room-paging", version.ref = "room" }
```

It takes `version.ref = "room"` because it ships with Room and must not drift from `room-runtime`; Room's generated `PagingSource` and this artifact's base class are the same release's API surface.

Add a version entry for Paging under `[versions]`, and the two libraries `:feature:ledger` needs in Task 5:

```toml
paging = "3.4.0"
```

```toml
androidx-paging-runtime = { group = "androidx.paging", name = "paging-runtime", version.ref = "paging" }
androidx-paging-compose = { group = "androidx.paging", name = "paging-compose", version.ref = "paging" }
```

**Verify `3.4.0` is the current stable before writing it in.** Do not take it from this plan:

```bash
curl -s https://dl.google.com/dl/android/maven2/androidx/paging/paging-runtime/maven-metadata.xml | grep -oE '<version>[^<]+</version>' | tail -8
```

Pin the latest release without an `-alpha`/`-beta`/`-rc` suffix, and put the reason in the catalog comment the way the `sqlcipher-android` and `androidx-sqlite` entries do.

- [ ] **Step 2: Wire it into `:core:data`**

In `core/data/build.gradle.kts`, in `dependencies`:

```kotlin
    // Room's PagingSource return type. Pinned to Room's own version, not
    // Paging's: the generated code and this artifact's LimitOffsetPagingSource
    // are the same Room release's API.
    api(libs.androidx.room.paging)
```

`api`, not `implementation`, because `TxnDao.feed()` returns a `PagingSource` and `:feature:ledger` has to name that type.

- [ ] **Step 3: Write the failing test**

Create `core/data/src/androidTest/kotlin/my/pinged/data/LedgerFeedTest.kt`:

```kotlin
package my.pinged.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The feed's order, its membership, and its invalidation.
 *
 * Order is asserted rather than assumed because paging makes an arbitrary
 * order an unstable one: without a tiebreaker two rows sharing `occurred_at`
 * can be returned on two pages or on none, and the symptom is a duplicated or
 * missing transaction rather than an error.
 */
@RunWith(AndroidJUnit4::class)
class LedgerFeedTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private fun uncategorized() = db.categoryDao().requireUncategorizedId()

    private fun loadFirst(size: Int): List<Txn> = runBlocking {
        val page = db.txnDao().feed().load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = size, placeholdersEnabled = false),
        )
        assertTrue("The feed refused to load: $page", page is PagingSource.LoadResult.Page)
        (page as PagingSource.LoadResult.Page).data
    }

    @Test fun theFeedIsNewestFirst() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = 1_000L, amountSen = 100L, categoryId = cat))
        db.txnDao().insert(sampleTxn(occurredAt = 3_000L, amountSen = 300L, categoryId = cat))
        db.txnDao().insert(sampleTxn(occurredAt = 2_000L, amountSen = 200L, categoryId = cat))

        assertEquals(listOf(300L, 200L, 100L), loadFirst(10).map { it.amountSen })
    }

    @Test fun rowsSharingASecondHaveAStableOrder() {
        val cat = uncategorized()
        val ids = (1..6).map {
            db.txnDao().insert(sampleTxn(occurredAt = 5_000L, amountSen = it * 10L, categoryId = cat))
        }

        // Two overlapping windows over the same fixture. With no tiebreaker the
        // two loads can disagree, which is how a row appears twice or not at all.
        val whole = loadFirst(10).map { it.id }
        val firstThree = loadFirst(3).map { it.id }

        assertEquals(ids.sortedDescending(), whole)
        assertEquals(whole.take(3), firstThree)
    }

    @Test fun pendingAndExcludedRowsAreInTheFeedAndRejectedOnesAreNot() {
        val cat = uncategorized()
        db.txnDao().insert(sampleTxn(occurredAt = 1_000L, amountSen = 100L, categoryId = cat))
        db.txnDao().insert(sampleTxn(occurredAt = 2_000L, amountSen = 200L, categoryId = cat, state = TxnState.PENDING))
        db.txnDao().insert(sampleTxn(occurredAt = 3_000L, amountSen = 300L, categoryId = cat, isExcluded = true))
        db.txnDao().insert(sampleTxn(occurredAt = 4_000L, amountSen = 400L, categoryId = cat, state = TxnState.REJECTED))

        val amounts = loadFirst(10).map { it.amountSen }

        assertTrue(
            "A PENDING row is missing from the feed. There is no review inbox in " +
                "this milestone, so a hidden PENDING row is money on disk and " +
                "nowhere on screen: $amounts",
            amounts.contains(200L),
        )
        assertTrue("An excluded row must still be shown, struck through", amounts.contains(300L))
        assertFalse("A REJECTED row was shown", amounts.contains(400L))
    }

    @Test fun insertingATransactionInvalidatesTheFeed() {
        val cat = uncategorized()
        val source = db.txnDao().feed()
        runBlocking {
            source.load(PagingSource.LoadParams.Refresh(null, 10, false))
        }
        assertFalse("The source was invalid before anything was written", source.invalid)

        db.txnDao().insert(sampleTxn(occurredAt = 1_000L, categoryId = cat))

        // Room's invalidation is delivered on its own executor, so poll rather
        // than assert immediately. A fixed sleep here would be flaky on a loaded
        // emulator in one direction and slow in the other.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!source.invalid && System.nanoTime() < deadline) Thread.sleep(20)

        assertTrue(
            "The PagingSource was not invalidated by an insert, so a captured " +
                "payment would not appear until the screen was recreated",
            source.invalid,
        )
    }
}
```

- [ ] **Step 4: Run it and confirm it fails for the right reason**

```bash
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.LedgerFeedTest
```

Expected: compilation failure — `feed()` does not exist.

- [ ] **Step 5: Add the query**

In `TxnDao.kt`, add the import:

```kotlin
import androidx.paging.PagingSource
```

and the query:

```kotlin
    /**
     * The home list (§9.1), newest first.
     *
     * A `PagingSource` and not a `Flow<List<Txn>>` (§15.4): the flow re-emits
     * every row on every insert, and this app inserts from a background worker
     * while the screen is open. Room invalidates this source instead, so a
     * captured payment appears without re-reading the list.
     *
     * **`id DESC` is a correctness tiebreaker, not a preference.** Two captures
     * inside the same second -- a payment and the bank's own confirmation of it
     * -- are otherwise ordered arbitrarily, and under paging an arbitrary order
     * is an unstable one: the same row can land on two pages or on none.
     * `LedgerFeedTest.rowsSharingASecondHaveAStableOrder` is the falsifying
     * test.
     *
     * `REJECTED` is excluded because a rejected capture is not a transaction.
     * `PENDING` and excluded rows are **included** -- they are kept out of every
     * total (§8) and still drawn, because until §9.2's review inbox exists a
     * hidden `PENDING` row is money on disk and nowhere on screen.
     *
     * Served by `index_txn_state_occurred_at`.
     */
    @Query("SELECT * FROM txn WHERE state != 'REJECTED' ORDER BY occurred_at DESC, id DESC")
    fun feed(): PagingSource<Int, Txn>
```

- [ ] **Step 6: Run the test and confirm it passes**

```bash
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.LedgerFeedTest
```

Expected: 4 tests, 0 failures.

- [ ] **Step 7: Falsify**

| Mutation | Must fail |
|---|---|
| drop `, id DESC` | `rowsSharingASecondHaveAStableOrder` |
| `ORDER BY occurred_at ASC` | `theFeedIsNewestFirst` |
| `WHERE state = 'COMMITTED'` | `pendingAndExcludedRowsAreInTheFeed...` |
| drop `WHERE state != 'REJECTED'` | same test, on the `assertFalse` |

The tiebreaker mutation is the one most likely to pass anyway, because SQLite may return insertion order by chance. If it passes, the fixture is not adversarial enough — vary the insertion order relative to `id` until removing the tiebreaker demonstrably breaks it, and record in the commit message what it took.

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml core/data/build.gradle.kts \
        core/data/src/main/kotlin/my/pinged/data/dao/TxnDao.kt \
        core/data/src/androidTest/kotlin/my/pinged/data/LedgerFeedTest.kt
git commit
```

State the Paging version you verified and where you verified it, and why `room-paging` follows Room's version rather than Paging's.

---

### Task 3: The do-not-trust rule

**Files:**
- Modify: `core/data/src/main/kotlin/my/pinged/data/dao/CaptureDayDao.kt`
- Test: `core/data/src/androidTest/kotlin/my/pinged/data/MonthTrustTest.kt`

**Interfaces:**
- Consumes: `CaptureDay(localDate, listenerBound, sawAnyNotification)`, `CaptureDayDao.recordListenerBound(localDate, bound)`.
- Produces: `CaptureDayDao.boundDayCount(from: LocalDate, to: LocalDate): Int`

- [ ] **Step 1: Write the failing test**

Create `core/data/src/androidTest/kotlin/my/pinged/data/MonthTrustTest.kt`:

```kotlin
package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §8: "A gap in capture is never drawn as a zero."
 *
 * The month total is greyed and labelled do-not-trust when the month holds days
 * capture was not alive for. Without this the summary understates a month the
 * app spent dead, the number looks right, and nothing lets the user suspect it.
 *
 * A day with no `capture_day` row at all is an uncaptured day. That is why this
 * counts the days that *were* bound and leaves the comparison against the
 * month's elapsed length to the caller: SQLite has no calendar to left-join
 * against, so "how many days are missing" is not a question this query can ask.
 */
@RunWith(AndroidJUnit4::class)
class MonthTrustTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private fun bind(yyyymmdd: Int, bound: Boolean = true) =
        db.captureDayDao().recordListenerBound(LocalDate(yyyymmdd), bound)

    @Test fun aMonthWithEveryDayBoundCountsEveryDay() {
        (1..5).forEach { bind(20260900 + it) }

        assertEquals(5, db.captureDayDao().boundDayCount(LocalDate(20260901), LocalDate(20260930)))
    }

    @Test fun aDayWithNoRowIsNotCounted() {
        bind(20260901)
        bind(20260903)

        assertEquals(
            "A day the app never ran on was counted as captured, which is exactly " +
                "the gap §8 says must not read as a zero",
            2, db.captureDayDao().boundDayCount(LocalDate(20260901), LocalDate(20260930)),
        )
    }

    @Test fun aDayRecordedAsUnboundIsNotCounted() {
        bind(20260901)
        bind(20260902, bound = false)

        assertEquals(1, db.captureDayDao().boundDayCount(LocalDate(20260901), LocalDate(20260930)))
    }

    @Test fun daysOutsideTheRangeDoNotCount() {
        bind(20260831)
        bind(20260901)
        bind(20261001)

        assertEquals(1, db.captureDayDao().boundDayCount(LocalDate(20260901), LocalDate(20260930)))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails for the right reason**

```bash
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=my.pinged.data.MonthTrustTest
```

Expected: compilation failure — `boundDayCount` does not exist.

- [ ] **Step 3: Add the query**

In `CaptureDayDao.kt`:

```kotlin
    /**
     * How many days in `[from, to]` the listener was bound on.
     *
     * The caller compares this against the number of days the month has
     * *elapsed*, and greys the total when they differ (§8). Two reasons the
     * comparison is not in SQL: a day with no row at all is an uncaptured day,
     * and SQLite has no calendar table to left-join against, so the absent days
     * cannot be counted here; and "elapsed" depends on today, which a query
     * would have to be told anyway.
     *
     * `listener_bound = 1` and not `saw_any_notification`: a genuinely quiet
     * phone posts nothing for a day and is still being captured, so keying trust
     * on notifications seen would grey out a correct total.
     */
    @Query(
        """
        SELECT COUNT(*) FROM capture_day
        WHERE local_date BETWEEN :from AND :to AND listener_bound = 1
        """,
    )
    fun boundDayCount(from: LocalDate, to: LocalDate): Int
```

- [ ] **Step 4: Run the test and confirm it passes**

Expected: 4 tests, 0 failures.

- [ ] **Step 5: Falsify**

| Mutation | Must fail |
|---|---|
| drop `AND listener_bound = 1` | `aDayRecordedAsUnboundIsNotCounted` |
| `local_date >= :from` only | `daysOutsideTheRangeDoNotCount` |
| replace `listener_bound = 1` with `saw_any_notification = 1` | `aMonthWithEveryDayBoundCountsEveryDay` |

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/my/pinged/data/dao/CaptureDayDao.kt \
        core/data/src/androidTest/kotlin/my/pinged/data/MonthTrustTest.kt
git commit
```

Say why the elapsed-day comparison is in Kotlin and not SQL, and why trust keys on `listener_bound` rather than `saw_any_notification`.

---

### Task 4: Navigation 3, and real ViewModels

The app still shows only the allow-list at the end of this task. That is the point: navigation and the `ViewModel` conversion land while there is exactly one screen to break, so a failure here cannot be confused with a failure in the ledger.

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `feature/ledger/build.gradle.kts`
- Create: `app/src/main/kotlin/my/pinged/Destinations.kt`
- Modify: `app/src/main/kotlin/my/pinged/MainActivity.kt`
- Modify: `feature/ledger/src/main/kotlin/my/pinged/ledger/sources/SourcesViewModel.kt`
- Modify: `app/src/androidTest/kotlin/my/pinged/LaunchTest.kt`

**Interfaces:**
- Consumes: `SourcesScreen(viewModel: SourcesViewModel, modifier: Modifier)`, `ListenerStatus.report(context, now)`, `CaptureBanner(label, body, actionLabel, onAction)`.
- Produces:
  - `my.pinged.Ledger` and `my.pinged.Sources`, both `NavKey`
  - `SourcesViewModel(app: Application) : AndroidViewModel(app)` with `SourcesViewModel.Factory`

- [ ] **Step 1: Verify the two coordinates this plan refuses to guess**

The milestone design deliberately does not pin these from memory. Establish them now:

```bash
curl -s https://dl.google.com/dl/android/maven2/androidx/navigation3/navigation3-runtime/maven-metadata.xml | grep -oE '<version>[^<]+</version>' | tail -6
curl -s https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-navigation3/maven-metadata.xml | grep -oE '<version>[^<]+</version>' | tail -6
curl -s https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-compose/maven-metadata.xml | grep -oE '<version>[^<]+</version>' | tail -6
```

Expect `navigation3` at `1.1.7` stable with `1.2.0-beta01` above it — pin `1.1.7`. The lifecycle artifacts track the lifecycle line, not navigation3's; pin the latest stable of each. If `lifecycle-viewmodel-navigation3` has no stable release, **stop and report it** rather than pinning an alpha: without it, per-destination `ViewModel` scoping has to be reconsidered and that is a design decision, not an implementation one.

- [ ] **Step 2: Add the catalog entries**

```toml
navigation3 = "1.1.7"
lifecycle = "<the version you verified>"
```

```toml
androidx-navigation3-runtime = { group = "androidx.navigation3", name = "navigation3-runtime", version.ref = "navigation3" }
androidx-navigation3-ui = { group = "androidx.navigation3", name = "navigation3-ui", version.ref = "navigation3" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-navigation3 = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-navigation3", version.ref = "lifecycle" }
```

Comment the `navigation3` entry with why `1.1.7` and not `1.2.0-beta01`, the way the existing entries justify their pins.

- [ ] **Step 3: Wire the modules**

`app/build.gradle.kts` — add to `plugins`:

```kotlin
    alias(libs.plugins.kotlin.serialization)
```

Nav3 keys are `@Serializable`. The plugin is already in the root build as `apply false` and already applied by `:core:parse`, so this costs no new dependency.

Add to `dependencies`:

```kotlin
    // Navigation 3 rather than navigation-compose. The back stack is saved
    // state here, and this process is created and destroyed constantly by
    // design -- the listener is spawned for a notification and dies -- so a
    // back stack held in a StateFlow would restore the user onto the wrong
    // screen with nothing to explain it.
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
```

`feature/ledger/build.gradle.kts` — add:

```kotlin
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
```

- [ ] **Step 4: Build and confirm the dependencies resolve**

```bash
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew :app:assembleDebug
```

Expected: success. If it fails on AAR metadata with a `minCompileSdk` above 37, that is the `sqlcipher-android` 4.18.0 failure mode repeating — **stop and report**, because the fix is a `compileSdk` bump and that is a spec decision.

- [ ] **Step 5: Add the destination keys**

Create `app/src/main/kotlin/my/pinged/Destinations.kt`:

```kotlin
package my.pinged

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The app's destinations.
 *
 * `@Serializable` because Nav3 keeps the back stack in saved state, which is the
 * reason it was chosen over a hand-rolled switch: this process dies constantly
 * and has to come back on the screen the user left.
 *
 * Objects rather than classes while neither carries an argument. A deep link
 * into one transaction (§9.1) would make `Ledger` a class with an id, which is
 * the shape this exists to leave room for.
 */
@Serializable data object Ledger : NavKey

@Serializable data object Sources : NavKey
```

- [ ] **Step 6: Convert `SourcesViewModel` to a real `ViewModel`**

Change the class declaration in `feature/ledger/src/main/kotlin/my/pinged/ledger/sources/SourcesViewModel.kt`:

```kotlin
class SourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context = app
    private val scope: CoroutineScope get() = viewModelScope
```

Add the imports `android.app.Application`, `androidx.lifecycle.AndroidViewModel`, `androidx.lifecycle.viewModelScope`, `androidx.lifecycle.ViewModelProvider`, `androidx.lifecycle.viewmodel.CreationExtras`, and add a factory inside the class:

```kotlin
    /**
     * No DI framework, for the reason `Databases`' KDoc gives about the one in
     * `Graph`: a container with two consumers is scaffolding. This is the
     * smallest thing that lets `viewModel()` build it.
     */
    companion object {
        val Factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras")
                @Suppress("UNCHECKED_CAST")
                return SourcesViewModel(app) as T
            }
        }
    }
```

Update the existing KDoc on the class: it currently argues this should *not* be a `ViewModel` because the artifact would be "a dependency bought for one class". Replace that paragraph with why it is one now — `viewModelScope` outlives configuration change, which `Pager`'s `cachedIn` needs, and the app has two destinations with four more specified. Leave the `tail`/`enqueue` KDoc alone: its claim that the chain needs no lock because it is only touched from the main thread still holds, because `viewModelScope` is main-dispatched. Step 9 asserts that rather than trusting it.

- [ ] **Step 7: Rewrite `MainActivity` around a `NavDisplay`**

Replace `onCreate` and `onResume` in `app/src/main/kotlin/my/pinged/MainActivity.kt`:

```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PingedTheme {
                val backStack = rememberNavBackStack(Ledger)
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Paper)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    // Above the NavDisplay, not inside a screen: capture being
                    // dead matters whichever destination is showing, and one
                    // holder computing it is one place to get it wrong.
                    GrantBanner()
                    NavDisplay(
                        backStack = backStack,
                        onBack = { backStack.removeLastOrNull() },
                        entryProvider = entryProvider {
                            entry<Ledger> {
                                // Task 5 replaces this with LedgerScreen.
                                val sources: SourcesViewModel = viewModel(factory = SourcesViewModel.Factory)
                                SourcesScreen(sources, Modifier.weight(1f))
                            }
                            entry<Sources> {
                                val sources: SourcesViewModel = viewModel(factory = SourcesViewModel.Factory)
                                SourcesScreen(sources, Modifier.weight(1f))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The banner after something has touched the database, and in that
        // order. `CaptureHealth`'s storage flag is written by whatever opens the
        // database, so reading the report first describes the *previous*
        // foreground. This used to be ordered against
        // `SourcesViewModel.refresh()`; the allow-list may now never be
        // composed, so the probe is explicit rather than a side effect of a
        // screen that happens to be on top. Both directions of this bug were
        // seen on device.
        lifecycleScope.launch {
            CaptureStorage.guarded(
                applicationContext,
                what = "The ledger cannot be read",
                unavailable = {},
            ) {}
            health.value = ListenerStatus.report(applicationContext)
        }
    }
```

Add the imports `androidx.lifecycle.viewmodel.compose.viewModel`, `androidx.navigation3.runtime.rememberNavBackStack`, `androidx.navigation3.runtime.entryProvider`, `androidx.navigation3.runtime.entry`, `androidx.navigation3.ui.NavDisplay`, and `my.pinged.capture.CaptureStorage`. Remove the `private lateinit var sources` field and the `sources = SourcesViewModel(...)` line — the destinations own their holders now.

Check the exact `rememberNavBackStack`, `entryProvider` and `entry` import paths against the resolved artifact rather than trusting this plan; Nav3's minor line is still moving and these moved between alphas.

- [ ] **Step 8: Update `LaunchTest` for the new home**

`LaunchTest` currently asserts only that nothing threw. Its subject is unchanged but its coverage now has to include the navigation host, so add a second test to `app/src/androidTest/kotlin/my/pinged/LaunchTest.kt`:

```kotlin
    @Test fun theBackStackSurvivesRecreation() {
        Databases.reset()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNothingWasThrownDuring("launch")
            // A configuration change re-runs onCreate against a restored
            // back stack. Nav3 keeps it in saved state, which is the reason it
            // was chosen; this is the assertion that the reason holds.
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertNothingWasThrownDuring("recreation with a restored back stack")
        }
    }
```

- [ ] **Step 9: Add the main-thread invariant test for the converted ViewModel**

`SourcesViewModel`'s `enqueue` KDoc claims `tail` needs no lock because it is only touched from the main thread. `viewModelScope` is main-dispatched, so the claim should survive — assert it. Add to `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/MainThreadRefreshTest.kt`:

```kotlin
    @Test fun enqueueStillRunsOnTheMainThread() {
        Databases.reset()
        val seen = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val sources = SourcesViewModel(context.applicationContext as android.app.Application)
        sources.beforeEachOperation = { seen.set(Thread.currentThread().name) }

        runBlocking { withTimeout(TIMEOUT_MILLIS) { sources.refresh().join() } }

        assertEquals(
            "enqueue no longer runs on the main thread, so `tail`'s documented " +
                "lock-free invariant is false and two toggles can race",
            "main", seen.get(),
        )
    }
```

Update this file's existing test to construct the ViewModel the same way; it currently passes `(context, scope)`.

- [ ] **Step 10: Run everything and confirm it passes**

```bash
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew test lint :app:assembleDebug
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew connectedDebugAndroidTest
```

Expected: all green. Count from the XML. The app should still launch to the allow-list, reachable now through `NavDisplay`.

- [ ] **Step 11: Falsify the ordering fix**

Delete the `CaptureStorage.guarded` call from `onResume`, leaving only the `ListenerStatus.report`. Then, on a device with a fresh install, the storage flag is read before anything has opened the database. Confirm the banner is wrong on first foreground and right on the second, then restore the call. If it cannot be made to misbehave, say so in the commit message rather than claiming the guard works — the original comment says both directions were observed, and a guard whose absence changes nothing needs explaining.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit
```

Record the versions you verified in Step 1 and where, whether the `minCompileSdk` check in Step 4 passed, what Step 11 showed, and that the app deliberately still has one screen at this point.

---

### Task 5: The ledger screen

**Files:**
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/home/LedgerState.kt`
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/home/LedgerViewModel.kt`
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/home/LedgerScreen.kt`
- Modify: `app/src/main/kotlin/my/pinged/MainActivity.kt`
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/LedgerScreenTest.kt`

**Interfaces:**
- Consumes: `TxnDao.feed()`, `dayTotals`, `monthTotals`, `monthByCategory`, `CaptureDayDao.boundDayCount`, `Databases.txnDao(context)`, `CaptureStorage.guarded`, theme tokens `Paper`, `Card`, `Ink`, `Muted`, `Faint`, `Rule`, `Stamp`, `MonoNumerals`, `MonoLabel`, `Separator`.
- Produces:
  - `sealed interface LedgerItem { data class Row(val txn: Txn); data class DayHeader(val date: LocalDate) }`
  - `data class MonthSummary(val totals: List<CurrencyTotal>, val top: List<CategoryTotal>, val trustworthy: Boolean)`
  - `LedgerViewModel(app: Application)` exposing `items: Flow<PagingData<LedgerItem>>`, `daySubtotals: StateFlow<Map<LocalDate, List<CurrencyTotal>>>`, `summary: StateFlow<MonthSummary?>`, `storageUnavailable: StateFlow<Boolean>`, `refresh(): Job`
  - `LedgerScreen(viewModel: LedgerViewModel, onOpenSources: () -> Unit, modifier: Modifier)`

- [ ] **Step 1: Define the state types**

Create `LedgerState.kt`:

```kotlin
package my.pinged.ledger.home

import my.pinged.data.LocalDate
import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.entity.Txn

/**
 * A row of the list, or a day header above one.
 *
 * The header carries **only its date**. Its subtotal is looked up separately,
 * because the feed and the aggregate are independent flows: a header that
 * rendered a number straight out of the paging transform would show whatever
 * the aggregate last emitted, which on first composition is nothing and after
 * an insert is stale. Absent means no number yet, and never zero.
 */
sealed interface LedgerItem {
    data class Row(val txn: Txn) : LedgerItem
    data class DayHeader(val date: LocalDate) : LedgerItem
}

/**
 * The pinned month summary (§9.1).
 *
 * [trustworthy] is false when the month holds days capture was not alive for
 * (§8), and the total is then greyed and labelled rather than hidden: hiding it
 * loses information, and showing it plainly asserts a number the app cannot
 * stand behind.
 */
data class MonthSummary(
    val totals: List<CurrencyTotal>,
    val top: List<CategoryTotal>,
    val trustworthy: Boolean,
)
```

- [ ] **Step 2: Write the ViewModel**

Create `LedgerViewModel.kt`:

```kotlin
package my.pinged.ledger.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.pinged.capture.CaptureStorage
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.dao.CurrencyTotal
import java.time.YearMonth

/**
 * Home's state (§9.1). Three reads, deliberately separate.
 *
 * The feed is a `PagingSource` and the subtotals are not derived from it: a page
 * boundary can fall mid-day, so a subtotal summed from loaded rows is short by
 * whatever is on the next page (§15.4, §9.1).
 *
 * `cachedIn(viewModelScope)` is why this is an `AndroidViewModel` rather than
 * the plain state holder `SourcesViewModel` used to be: the scope has to outlive
 * a configuration change or every rotation re-queries from page one.
 */
class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app

    private val _daySubtotals = MutableStateFlow<Map<LocalDate, List<CurrencyTotal>>>(emptyMap())
    val daySubtotals: StateFlow<Map<LocalDate, List<CurrencyTotal>>> = _daySubtotals.asStateFlow()

    private val _summary = MutableStateFlow<MonthSummary?>(null)
    val summary: StateFlow<MonthSummary?> = _summary.asStateFlow()

    private val _storageUnavailable = MutableStateFlow(false)
    val storageUnavailable: StateFlow<Boolean> = _storageUnavailable.asStateFlow()

    /**
     * `enablePlaceholders = false`: a placeholder row would draw an amount-shaped
     * blank in a list of money, and §8's honesty rules make an unknown number
     * worse than a shorter list.
     */
    val items: Flow<PagingData<LedgerItem>> =
        Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
            Databases.txnDao(context).feed()
        }.flow
            .map { paging -> paging.map { LedgerItem.Row(it) as LedgerItem } }
            .map { paging ->
                paging.insertSeparators { before, after ->
                    val next = (after as? LedgerItem.Row)?.txn ?: return@insertSeparators null
                    val prev = (before as? LedgerItem.Row)?.txn
                    if (prev == null || prev.localDate != next.localDate) {
                        LedgerItem.DayHeader(next.localDate)
                    } else {
                        null
                    }
                }
            }
            .cachedIn(viewModelScope)

    /**
     * Re-read the aggregates for the current month.
     *
     * Also the app's database probe: `guarded` is what opens the database and
     * therefore what records or clears the storage flag the banner reads, so
     * anything ordering itself after the ledger's first read gets an honest
     * flag. See `MainActivity.onResume`.
     */
    fun refresh(now: YearMonth = YearMonth.now()): Job = viewModelScope.launch {
        val from = LocalDate(now.year * 10_000 + now.monthValue * 100 + 1)
        val to = LocalDate(now.year * 10_000 + now.monthValue * 100 + now.lengthOfMonth())

        CaptureStorage.guarded(
            context,
            what = "The ledger cannot be read",
            unavailable = { _storageUnavailable.value = true },
        ) {
            withContext(Dispatchers.IO) {
                val txns = Databases.txnDao(context)
                _daySubtotals.value = txns.dayTotals(from, to)
                    .groupBy({ it.localDate }, { CurrencyTotal(it.currency, it.netSen) })

                // Elapsed, not the whole month: days that have not happened yet
                // are not gaps, and counting them would grey out every month
                // total until its last day.
                val elapsed = if (YearMonth.now() == now) java.time.LocalDate.now().dayOfMonth
                else now.lengthOfMonth()
                val bound = Databases.captureDayDao(context).boundDayCount(from, to)

                _summary.value = MonthSummary(
                    totals = txns.monthTotals(from, to),
                    top = txns.monthByCategory(from, to, limit = TOP_CATEGORIES),
                    trustworthy = bound >= elapsed,
                )
            }
            _storageUnavailable.value = false
        }
    }

    companion object {
        /** §9.1 says three. The query takes it as a parameter so the two cannot disagree. */
        const val TOP_CATEGORIES = 3

        val Factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras")
                @Suppress("UNCHECKED_CAST")
                return LedgerViewModel(app) as T
            }
        }
    }
}
```

Add `import kotlinx.coroutines.flow.map` for the two `Flow.map` calls.

- [ ] **Step 3: Write the screen**

Create `LedgerScreen.kt`. Follow `SourcesScreen.kt` for the visual idiom — `Paper` ground, `MonoLabel` for uppercase labels, `Separator` between label halves, `Rule` hairlines, `MonoNumerals` for every amount so digits align. The five states from the design, in this order:

```kotlin
@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val subtotals by viewModel.daySubtotals.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val unavailable by viewModel.storageUnavailable.collectAsState()

    LaunchedEffect(viewModel) { viewModel.refresh() }

    when {
        // First, because it is the only state where the ledger is certainly
        // unreadable rather than empty. An empty list here would assert "you
        // spent nothing" when the truth is "this cannot be read" (§11.1).
        unavailable -> LedgerUnavailable(modifier)

        // Not empty until the first load has actually returned. `loadState`
        // distinguishes them; a bare `itemCount == 0` does not.
        items.loadState.refresh is LoadState.Loading -> LedgerLoading(modifier)

        items.itemCount == 0 -> LedgerEmpty(onOpenSources, modifier)

        else -> LedgerList(items, subtotals, summary, modifier)
    }
}
```

`LedgerEmpty` must say *nothing captured yet* and offer the route to capture sources. It must never say "nothing spent": with no sources enabled those are different facts, and only one of them is knowable here.

In `LedgerList`, the pinned summary sits above the `LazyColumn`. When `summary?.trustworthy == false`, draw the total in `Muted` with a `MonoLabel` reading `"DO NOT TRUST" + Separator + "CAPTURE HAS GAPS THIS MONTH"`. One line per `CurrencyTotal`.

Each `LedgerItem.DayHeader` reads its subtotal from `subtotals[date]` and draws no number when the key is absent. Each `LedgerItem.Row` shows merchant display name, source label, and amount; `txn.state == TxnState.PENDING` gets a `MonoLabel` badge naming `pendingReason`; `txn.isExcluded` is drawn in `Faint` with a strikethrough and an `exclusionReason` badge. Neither is omitted.

- [ ] **Step 4: Make the ledger home**

In `MainActivity.kt`, replace the `entry<Ledger>` body from Task 4:

```kotlin
                            entry<Ledger> {
                                val ledger: LedgerViewModel = viewModel(factory = LedgerViewModel.Factory)
                                LedgerScreen(
                                    viewModel = ledger,
                                    onOpenSources = { backStack.add(Sources) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
```

`onResume`'s probe from Task 4 can now be dropped in favour of the ledger's own `refresh()`, **but only if** the ledger is guaranteed to be composed. It is the start destination, so it is — record that dependency in a comment, because it stops being true the moment a different start destination is introduced.

- [ ] **Step 5: Write the screen test**

Create `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/LedgerScreenTest.kt` using `createComposeRule()` as `SourcesAllowListTest` does. Four assertions, each on text the screen renders:

1. With no transactions, the screen shows the empty copy and **not** any amount.
2. With one committed transaction of RM50.00, the row and a day header with `50.00` both appear.
3. With a `PENDING` transaction, its row appears and the month total excludes it — assert both, in one test, because it is their combination that §8 requires.
4. With `boundDayCount` short of the elapsed days, the do-not-trust label appears.

Construct the ViewModel directly with the application context, as `MainThreadRefreshTest` does, and call `Databases.reset()` first for the reason that test records.

- [ ] **Step 6: Run and falsify**

```bash
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew :feature:ledger:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

| Mutation | Must fail |
|---|---|
| `LedgerItem.DayHeader` renders `subtotals[date] ?: 0` instead of nothing | the header test, on a fixture where the aggregate has not loaded |
| `insertSeparators` compares `occurredAt` day instead of `localDate` | header test |
| the `unavailable` branch is moved below `itemCount == 0` | the unavailable test |
| `trustworthy` is hardcoded true | the do-not-trust test |

The first mutation is the one that most easily passes anyway: with a small fixture the aggregate is usually loaded by assertion time. Make the fixture prove it — hold the aggregate back, and record in the commit message what that took.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit
```

---

### Task 6: Manual category assignment

**Files:**
- Modify: `core/data/src/main/kotlin/my/pinged/data/dao/TxnDao.kt`
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/home/CategoryPicker.kt`
- Modify: `feature/ledger/src/main/kotlin/my/pinged/ledger/home/LedgerViewModel.kt`, `LedgerScreen.kt`
- Test: `core/data/src/androidTest/kotlin/my/pinged/data/LedgerAggregateTest.kt`, `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/LedgerScreenTest.kt`

**Interfaces:**
- Consumes: `CategoryDao.all(): List<Category>`, `Category(id, name, iconKey, sortOrder, isProtected)`.
- Produces: `TxnDao.setCategory(id: Long, categoryId: Long, updatedAt: Long): Int`, `LedgerViewModel.assignCategory(txnId: Long, categoryId: Long): Job`, `LedgerViewModel.categories: StateFlow<List<Category>>`

- [ ] **Step 1: Add the targeted `UPDATE`**

```kotlin
    /**
     * Assign a category by hand (§9.1's chip, without §6.1's learned rule).
     *
     * A targeted `UPDATE` naming its own columns, for the reason
     * `CaptureSourceDao` has no whole-row upsert: SQLite's `REPLACE` deletes and
     * re-inserts, so a partial write would revert every other column on the row.
     *
     * `user_edited` is set because §5.5's re-parse must not overwrite a decision
     * a person made. Nothing re-parses yet; the column is the record that will
     * make that possible without guessing later.
     */
    @Query(
        """
        UPDATE txn SET category_id = :categoryId, user_edited = 1, updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    fun setCategory(id: Long, categoryId: Long, updatedAt: Long): Int
```

- [ ] **Step 2: Add the failing aggregate test**

Add to `LedgerAggregateTest`:

```kotlin
    @Test fun reassigningACategoryMovesItInTheSummaryAndKeepsEveryOtherColumn() {
        val cats = db.categoryDao().all().filter { !it.isProtected }.take(2).map { it.id }
        val id = db.txnDao().insert(sampleTxn(occurredAt = day1, amountSen = 4_200L, categoryId = cats[0]))

        val rows = db.txnDao().setCategory(id, cats[1], updatedAt = day1 + 1)

        assertEquals(1, rows)
        val after = db.txnDao().byId(id)!!
        assertEquals(cats[1], after.categoryId)
        assertTrue("user_edited was not set, so a re-parse could silently undo this", after.userEdited)
        assertEquals("A targeted UPDATE must not disturb the amount", 4_200L, after.amountSen)
        assertEquals("...nor the state", TxnState.COMMITTED, after.state)
        assertEquals(
            cats[1],
            db.txnDao().monthByCategory(sept.first, sept.second, limit = 3).single().categoryId,
        )
    }
```

- [ ] **Step 3: Run it, confirm it fails on the missing method, implement Step 1, run again**

Expected: compilation failure, then 7 tests passing in the class.

- [ ] **Step 4: Falsify**

Change the `UPDATE` to also write `amount_sen = 0` and confirm the "must not disturb the amount" assertion fails; drop `user_edited = 1` and confirm that assertion fails. Revert both.

- [ ] **Step 5: Add the picker and wire it**

`CategoryPicker.kt` is a `ModalBottomSheet` listing `categories` ordered by `sortOrder`, each row its `iconKey` and `name`, and a tap calling back with the id. In `LedgerViewModel`:

```kotlin
    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    fun assignCategory(txnId: Long, categoryId: Long): Job = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            Databases.txnDao(context).setCategory(txnId, categoryId, System.currentTimeMillis())
        }
        // The feed invalidates itself through Room; the aggregates do not, so
        // the summary has to be re-read or the top three keeps the old answer.
        refresh().join()
    }
```

Load `_categories` inside `refresh`'s `withContext` block from `Databases.categoryDao(context).all()`.

In `LedgerScreen`, a row whose `categoryId` is the Uncategorized id shows a tappable chip that opens the sheet. Rows with a real category show the category name and are not tappable — §6's learned rules and re-categorization are out of scope, and a chip that opened a sheet which then did nothing useful would imply otherwise.

- [ ] **Step 6: Add the screen test, run everything, commit**

Assert that tapping the chip and choosing a category updates the row's rendered category name and the summary's top line. Then:

```bash
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew test lint :app:assembleDebug
ANDROID_HOME=/home/ray/Android/Sdk ./gradlew connectedDebugAndroidTest
```

```bash
git add -A
git commit
```

---

### Task 7: Prove it on a phone, and say what is still unproven

Everything above runs on fixtures. This task is the only one that can answer whether the screen is worth having.

**Files:**
- Create: `docs/device-notes.md`
- Modify: `README.md`

- [ ] **Step 1: Cut a release and install it**

Tag from the branch's merge commit once the plan is complete, as `docs/release.md` describes. Uninstall any debug build first — the signatures differ and `adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

- [ ] **Step 2: Make one small real payment and record what happened**

In `docs/device-notes.md`, with the device model and Android version at the top, record:

- did the payment produce a row in the ledger at all
- if not, what `adb logcat -s PingedParse` said
- the merchant string as the app displayed it, beside what the notification actually read
- whether the amount was right to the sen
- whether the row arrived `COMMITTED` or `PENDING`, and its `pending_reason`

That last line is the number §9 of the design says is missing. **Do not average it from one payment** — record each one, and count after a week.

- [ ] **Step 3: Check the two things fixtures cannot**

Whether the day header's subtotal matches the sum of the rows under it, by hand, on real data. And whether the month total greys out after force-stopping the app for a day — spec 10.5's state, which no test can produce.

- [ ] **Step 4: Update the README's Status section**

It currently says the capture milestone is "not yet proven on a phone". Replace it with what is now true, in the same voice: what runs, what is written and unreachable, and what has not happened at all. Do not claim the ledger is proven if Step 2 produced one payment.

- [ ] **Step 5: Commit**

```bash
git add docs/device-notes.md README.md
git commit
```

The message should state the pending ratio observed so far and how many payments it is drawn from, and should say plainly if that is too few to conclude anything.

---

## Self-Review

**Spec coverage.** Section 4.1 → Task 2. 4.2 → Task 1. 4.3 → Task 1. 4.4 → Task 3 (query) and Task 5 (the elapsed-day comparison). 4.5 → Task 1, tested by `twoCurrenciesProduceTwoLines...`. 4.6 → Task 1's KDoc plus the behavioural agreement tests. 5.1 → Task 4 Steps 1-4. 5.2 → the File Structure table. 5.3 → Task 4 Step 6 and Task 5 Step 2. 5.4 → Task 5 Steps 1 and 3. 5.5 → Task 4 Step 7. Section 6's five states → Task 5 Step 3. Section 7 → the test steps in Tasks 1, 2, 3, 5, 6. Section 8's `onResume` invariant → Task 4 Steps 7 and 11; the schema freeze → Task 1 Step 8; `:core:parse` → Global Constraints. Section 9's thin spots → Task 7, and the export risk is explicitly left for a later milestone.

**Two gaps I am naming rather than papering over.** The manual-categorization decision means §9.1's month summary shows "Uncategorized" as its top line until the user assigns something, and no task changes that — it is a consequence of the scope choice, not an oversight. And no task adds the per-outcome stage-two log discussed before this plan, so if Task 7 Step 2 finds no row, `logcat` still cannot distinguish "no rule matched" from "matched and something else went wrong"; that is a one-line diagnostic and a separate decision.

**Type consistency.** `LedgerItem`, `MonthSummary`, `DayTotal`, `CurrencyTotal`, `CategoryTotal` are defined once and used with the same field names throughout. `refresh()` returns `Job` in both ViewModels. `boundDayCount`, `dayTotals`, `monthTotals`, `monthByCategory`, `setCategory` and `feed` keep one signature each from definition to use.

**Two places the plan deliberately refuses to state a value**, both flagged in-step with the command to establish it: the Paging version (Task 2 Step 1) and the Nav3 / lifecycle coordinates (Task 4 Step 1). Writing either from memory is how a dependency table stops being trustworthy.
