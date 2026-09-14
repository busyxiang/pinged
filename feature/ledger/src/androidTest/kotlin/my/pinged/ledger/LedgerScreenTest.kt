package my.pinged.ledger

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.Category
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.home.CATEGORY_CHIP
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreen
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.home.MonthSummary
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ledger.theme.Separator
import my.pinged.ledger.theme.PingedTheme
import my.pinged.parse.ExclusionReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The five states spec 9.1's list has to tell apart, and the two numbers on it
 * that must not be summed from what happens to be loaded.
 *
 * Two kinds of test in one file, deliberately. The states are driven through
 * `LedgerScreenContent`, because three of them -- an unreadable ledger, a first
 * read still in flight, a day header whose aggregate has not landed -- cannot
 * be produced by putting rows in a database and waiting. The numbers are driven
 * through `LedgerViewModel` against the real encrypted database, because what
 * they assert is that the screen reads the aggregate rather than the rows, and
 * a stand-in aggregate would prove that about the stand-in.
 */
@RunWith(AndroidJUnit4::class)
class LedgerScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Leaves nothing of this class's fixtures for the next class in the run;
     * see [discardTheDatabase] for why that is the fixture and not the tidying.
     *
     * What is *not* undone is `CaptureHealth`'s durable `storage_unavailable`
     * flag: it is `internal` to `:feature:capture` and unreachable from here,
     * it has no reader in this test APK -- `SourcesState.storageUnavailable` is
     * a live failure from `guarded`, never the stored flag -- and the next
     * successful open clears it.
     */
    @After fun leaveNothingBehind() = context.discardTheDatabase()

    // ---- the five states, through the stateless content ------------------

    @Test fun theEmptyStateSaysNothingCapturedAndDrawsNoNumber() {
        content(items = emptyList(), refresh = LoadState.NotLoading(true))

        compose.waitForText(NOTHING_CAPTURED, "The empty ledger does not say so")

        val numbers = compose.allText().filter { AMOUNT.containsMatchIn(it) }
        assertTrue(
            "The empty ledger drew an amount: $numbers. Nothing has been " +
                "captured, so every number on this screen would be a claim " +
                "about money that no read has supported",
            numbers.isEmpty(),
        )
    }

    @Test fun theTopBarAndTheEmptyStateBothRouteToTheAllowList() {
        val opened = AtomicInteger(0)
        content(
            items = emptyList(),
            refresh = LoadState.NotLoading(true),
            onOpenSources = { opened.incrementAndGet() },
        )
        compose.waitForText(NOTHING_CAPTURED, "The empty ledger does not say so")

        compose.onNodeWithText("SOURCES").performClick()
        compose.onNodeWithText("CHOOSE CAPTURE SOURCES").performClick()

        assertEquals(
            "A route to the allow-list did not fire. Until this screen existed " +
                "nothing in the app pushed Sources, so its entry never composed",
            2,
            opened.get(),
        )
    }

    @Test fun aFirstLoadStillInFlightIsNotDrawnAsEmpty() {
        content(items = emptyList(), refresh = LoadState.Loading)
        compose.waitForIdle()

        assertTrue(
            "A load that has not returned was drawn as an empty ledger, which " +
                "tells the user they have captured nothing when nothing has " +
                "been read yet",
            compose.onAllNodesWithTextSafely(NOTHING_CAPTURED) == 0,
        )
        assertEquals(
            "A first read in flight did not say READING once, so the state " +
                "between 'not read yet' and 'nothing there' is not on screen",
            1, compose.onAllNodesWithTextSafely("READING"),
        )
    }

    @Test fun anUnreadableLedgerIsNotDrawnAsEmpty() {
        content(
            items = emptyList(),
            refresh = LoadState.NotLoading(true),
            storageUnavailable = true,
        )
        compose.waitForText(
            CANNOT_READ_YOUR_DATA,
            "An unreadable ledger did not say it could not be read",
        )

        assertTrue(
            "An unreadable ledger was drawn as an empty one. The list is empty " +
                "in both, so the ordering of the two branches is the whole " +
                "difference between 'you spent nothing' and 'this cannot be read'",
            compose.onAllNodesWithTextSafely(NOTHING_CAPTURED) == 0,
        )
    }

    @Test fun aFeedThatWillNotLoadIsNotDrawnAsEmpty() {
        // The other way the same fact arrives: `ReopeningFeed` turns a
        // database that will not open into a refresh error rather than a throw
        // out of Paging's fetcher, and the screen has to read that as
        // unreadable too. `storageUnavailable` is false here on purpose -- the
        // aggregate's own read may not have failed yet.
        content(
            items = emptyList(),
            refresh = LoadState.Error(IllegalStateException("no key")),
        )
        compose.waitForText(
            CANNOT_READ_YOUR_DATA,
            "A feed whose refresh failed did not say the ledger could not " +
                "be read",
        )

        assertTrue(
            "A feed that could not be loaded was drawn as an empty ledger",
            compose.onAllNodesWithTextSafely(NOTHING_CAPTURED) == 0,
        )
    }

    /**
     * Spec 11.1 through the whole screen, on a database that genuinely will not
     * open.
     *
     * [aFeedThatWillNotLoadIsNotDrawnAsEmpty] hands the screen a hand-built
     * `LoadState.Error` and so falsifies the screen's *branch*. This falsifies
     * the two things that produce the state: `LedgerViewModel.items`' single
     * open attempt and `feed`'s answering `UnreadableFeed` from its failure,
     * without which the throw reaches the composition out of Paging's fetcher,
     * and `refresh`'s `storageUnavailable` wiring, which every state test above
     * sets by hand instead.
     *
     * See [corruptTheDatabase] for why that is the failure being produced.
     */
    @Test fun aDatabaseThatWillNotOpenIsDrawnAsUnreadableAndFlagged() {
        context.corruptTheDatabase()

        val viewModel = ledgerViewModel()
        compose.setContent {
            PingedTheme { LedgerScreen(viewModel = viewModel, onOpenSources = {}) }
        }

        compose.waitForText(
            CANNOT_READ_YOUR_DATA,
            "A ledger whose database will not open was not drawn as unreadable. " +
                "Either the screen said something else, or nothing was drawn at " +
                "all -- Paging calls the source factory inside its fetcher, so a " +
                "throw there arrives in the composition",
        )

        runBlocking { withTimeout(TIMEOUT) { viewModel.refresh().join() } }
        assertTrue(
            "The aggregate read failed and the holder does not report storage " +
                "unavailable, so the one flag the screen and the banner both " +
                "read stays false while nothing can be read",
            viewModel.storageUnavailable.value,
        )
    }

    @Test fun aDayHeaderWhoseAggregateHasNotLandedDrawsNoNumber() {
        val row = LedgerItem.Row(ledgerTxn(amountSen = 1_234L, localDate = SEP_7))
        content(
            items = listOf(LedgerItem.DayHeader(SEP_7, row.txn.id), row),
            refresh = LoadState.NotLoading(true),
        )
        compose.waitForText("12.34", "The row was never drawn")

        assertEquals(
            "The day header drew a number while its aggregate was absent. " +
                "Exactly one 12.34 belongs on this screen -- the row's -- and a " +
                "second one is a subtotal summed from the rows that happen to " +
                "be loaded",
            1,
            compose.onAllNodesWithTextSafely("12.34"),
        )
        assertEquals(
            "The day header is not spelled the way the artboard spells it. " +
                "20260907 is a Monday, and this is hardcoded rather than built " +
                "from the screen's own formatter -- which is what let " +
                "Locale.ROOT's \"M09\" reach a device",
            1,
            compose.onAllNodesWithTextSafely("MON 7 SEP"),
        )
        val zeros = compose.allText().filter { it.contains("0.00") }
        assertTrue(
            "The day header drew a zero for an absent subtotal: $zeros. Absent " +
                "means the aggregate has not landed, or the day holds nothing " +
                "that counts; a zero is a false claim in the first and " +
                "contradicts the rows in the second",
            zeros.isEmpty(),
        )
    }

    @Test fun theDoNotTrustLabelIsDrawnWhenTheSummaryIsUntrustworthy() {
        val row = LedgerItem.Row(ledgerTxn(amountSen = 1_234L, localDate = SEP_7))
        content(
            items = listOf(LedgerItem.DayHeader(SEP_7, row.txn.id), row),
            refresh = LoadState.NotLoading(true),
            summary = MonthSummary(
                month = FIXED_MONTH,
                totals = listOf(CurrencyTotal("MYR", 120_450L)),
                top = emptyList(),
                trustworthy = false,
            ),
        )
        compose.waitForText(
            DO_NOT_TRUST,
            "A month with gaps in capture was drawn without the do-not-trust " +
                "label, so a number the app cannot stand behind is presented " +
                "as one it can",
        )

        assertEquals(
            "The month label does not name the month the summary covers. " +
                "Hardcoded, not derived: Locale.ROOT spells this month \"M09\"",
            1,
            compose.onAllNodesWithTextSafely("SEPTEMBER 2026"),
        )
        assertEquals(
            "The total was hidden rather than labelled. Hiding it loses " +
                "information the user has; the label is what stops the number " +
                "being believed",
            1,
            compose.onAllNodesWithTextSafely("RM1,204.50"),
        )
    }

    /**
     * The same §8 rule in the state where the label matters most: gaps in
     * capture, and nothing counted.
     *
     * "NOTHING COUNTED THIS MONTH" is a worded zero, and §8 says a gap in
     * capture is never drawn as one. This state is reachable rather than
     * theoretical: the feed carries every non-`REJECTED` row from every month,
     * so `itemCount` is positive -- the list branch, not the empty one -- while
     * *this* month's totals are empty, which is every month whose rows are all
     * pending or excluded, and every month in its first minutes.
     */
    @Test fun theDoNotTrustLabelStandsOverAMonthWithNothingCounted() {
        val row = LedgerItem.Row(ledgerTxn(amountSen = 1_234L, localDate = SEP_7))
        content(
            items = listOf(LedgerItem.DayHeader(SEP_7, row.txn.id), row),
            refresh = LoadState.NotLoading(true),
            summary = MonthSummary(
                month = FIXED_MONTH,
                totals = emptyList(),
                top = emptyList(),
                trustworthy = false,
            ),
        )
        compose.waitForText(NOTHING_COUNTED, "A month with no countable rows did not say so")

        assertEquals(
            "A month with gaps in capture and nothing counted was drawn as a " +
                "worded zero with no caveat. It is the state the caveat is most " +
                "needed in -- the user reads 'nothing counted' as 'nothing " +
                "spent', when what happened is that capture was not alive",
            1,
            compose.onAllNodesWithTextSafely(DO_NOT_TRUST),
        )
    }

    // ---- the numbers, through the view model and the real database -------

    @Test fun aCommittedTransactionIsDrawnUnderADayHeaderCarryingItsSubtotal() {
        val dao = freshLedger()
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = SEP_7_NOON, categoryId = categoryId(FOOD)))

        screenFor(FIXED_MONTH)

        compose.waitForText("RM50.00", "The month total was never drawn")
        // Three: the row, the day header's subtotal and the summary's one
        // category line. The month total reads "RM50.00" and does not match.
        assertEquals(
            "The day header, the row and the top-category line all read 50.00 " +
                "on this fixture; one missing means the header is not reading " +
                "the aggregate",
            3,
            compose.onAllNodesWithTextSafely("50.00"),
        )
        assertEquals(
            "The day header is missing or misspelled, so the rows are not " +
                "grouped under the day they fall on. Hardcoded and not built " +
                "from the screen's own formatter: an expectation derived the way " +
                "the subject derives it agrees with the subject's bugs, which is " +
                "how Locale.ROOT's \"M09\" reached a device",
            1,
            compose.onAllNodesWithTextSafely("MON 7 SEP"),
        )
    }

    @Test fun theSummaryNamesItsTopCategoryRatherThanItsId() {
        val dao = freshLedger()
        dao.insert(
            ledgerTxn(
                amountSen = 5_000L,
                occurredAt = System.currentTimeMillis(),
                categoryId = categoryId(FOOD),
            ),
        )

        screen()

        compose.waitForText("RM50.00", "The month total was never drawn")
        assertEquals(
            "The summary's top category is drawn as its category_id, or not at " +
                "all. A number is not a name, and the id is meaningless to the " +
                "person reading it",
            1,
            compose.onAllNodesWithTextSafely(FOOD),
        )
    }

    @Test fun aPendingRowIsDrawnAndTheMonthTotalExcludesIt() {
        val dao = freshLedger()
        val now = System.currentTimeMillis()
        val food = categoryId(FOOD)
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = now, categoryId = food))
        dao.insert(
            ledgerTxn(
                amountSen = 7_000L,
                occurredAt = now,
                categoryId = food,
                state = TxnState.PENDING,
                pendingReason = PendingReason.RULE_REVIEW,
            ),
        )

        screen()

        compose.waitForText(
            "70.00",
            "The PENDING row is not on screen at all, and there is no review " +
                "inbox in this milestone for it to be anywhere else",
        )
        assertEquals(
            "The PENDING row is not on screen. There is no review inbox in this " +
                "milestone, so a row this list hides is money in the database " +
                "and nowhere at all",
            1,
            compose.onAllNodesWithTextSafely("70.00"),
        )
        assertEquals(
            "The PENDING row is on screen without saying why it needs review, " +
                "which is the only place the user can see which of 7.1's gates " +
                "fired",
            1,
            compose.onAllNodesWithTextSafely("PENDING · RULE REVIEW"),
        )
        assertEquals(
            "The month total counted the PENDING row: 8's rule is that pending " +
                "and excluded rows never enter a total, and 50.00 is the whole " +
                "of what counts here",
            1,
            compose.onAllNodesWithTextSafely("RM50.00"),
        )
    }

    @Test fun anExcludedRowIsDrawnBadgedAndOutOfTheMonthTotal() {
        val dao = freshLedger()
        val now = System.currentTimeMillis()
        val food = categoryId(FOOD)
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = now, categoryId = food))
        dao.insert(
            ledgerTxn(
                amountSen = 10_000L,
                occurredAt = now,
                categoryId = food,
                isExcluded = true,
                exclusionReason = ExclusionReason.TRANSFER,
            ),
        )

        screen()

        compose.waitForText("100.00", "The excluded row is not on screen at all")
        assertEquals(
            "An excluded row must stay visible: 7.3 keeps it so the user can see " +
                "that money moved without being spent",
            1,
            compose.onAllNodesWithTextSafely("NOT SPENDING · TRANSFER"),
        )
        assertEquals(
            "The month total counted an excluded row",
            1,
            compose.onAllNodesWithTextSafely("RM50.00"),
        )
    }

    /**
     * §7.3 keeps an excluded row and takes it out of every total, so a category
     * assigned to one moves no number on any screen. The chip would take the
     * tap, write `user_edited`, and show nothing for it.
     *
     * Two rows, not one: both uncategorized, one excluded. Asserting "no chip"
     * against a screen with only the excluded row would pass just as well if
     * the chip had stopped being drawn at all, which is the mistake this file
     * has made before. One chip is the answer only if the gate discriminates.
     */
    @Test fun theChipIsOfferedOnAnUncategorizedRowButNotOnAnExcludedOne() {
        val dao = freshLedger()
        val now = System.currentTimeMillis()
        val uncategorized = categoryId(UNCATEGORIZED)
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = now, categoryId = uncategorized))
        dao.insert(
            ledgerTxn(
                amountSen = 10_000L,
                occurredAt = now,
                categoryId = uncategorized,
                isExcluded = true,
                exclusionReason = ExclusionReason.TRANSFER,
            ),
        )

        screen()

        compose.waitForText(
            "NOT SPENDING" + Separator + "TRANSFER",
            "The excluded row is not on screen, so there is nothing to withhold a chip from",
        )
        assertEquals(
            "Both uncategorized rows were offered the chip, or neither was. " +
                "The excluded row is in no total, so assigning it a category " +
                "changes nothing the user can see",
            1,
            compose.onAllNodesWithTextSafely(CATEGORY_CHIP),
        )
    }

    /**
     * The fixture is the test.
     *
     * With a handful of rows the first page holds the whole day, so a header
     * that summed the rows it can see would print the right number and this
     * would pass either way. Paging's initial load is three pages -- 120 rows
     * at `LedgerViewModel.PAGE_SIZE` -- so the day has to be longer than that
     * before the boundary exists at all: 130 rows of RM1.00 on one day, of
     * which 120 are loaded when the assertion runs. The aggregate says 130.00
     * and the loaded rows say 120.00, so the two answers are different strings
     * and the wrong one is nameable.
     *
     * [screenFor] hands back the loaded count, and this test asserts on it: a
     * fixture that stopped straddling -- a wider initial load, a smaller
     * fixture -- would otherwise turn this green without anything having been
     * proved.
     */
    @Test fun aDayStraddlingAPageBoundaryStillShowsTheWholeDaysSubtotal() {
        val dao = freshLedger()
        val food = categoryId(FOOD)
        dao.insertAll(
            (1..ROWS_IN_A_STRADDLED_DAY).map {
                // A second apart, so the feed's order is total, and pinned to
                // one `local_date` rather than left to the derivation: what this
                // fixture is about is a page boundary inside one day, and a
                // fixture that splits into two days is not that fixture.
                ledgerTxn(
                    amountSen = 100L,
                    occurredAt = SEP_7_NOON - it * 1_000L,
                    localDate = SEP_7,
                    categoryId = food,
                )
            },
        )

        val loaded = AtomicInteger(0)
        screenFor(FIXED_MONTH, loaded)

        // The straddle itself. One day, so one header: loaded rows are
        // `loaded - 1`, and the message prints what it saw. Measured on the
        // Pixel_10a AVD, API 37: 121 items, so 120 of the 130 rows.
        assertTrue(
            "The whole day is loaded (${loaded.get()} items for " +
                "$ROWS_IN_A_STRADDLED_DAY rows), so no page boundary falls " +
                "inside it and this test cannot tell a subtotal read from the " +
                "aggregate from one summed off the rows on screen",
            loaded.get() - 1 < ROWS_IN_A_STRADDLED_DAY,
        )

        compose.waitForText(
            "130.00",
            "The day header does not carry the whole day's subtotal",
        )
        // Two: the header's subtotal and the summary's one category line. The
        // month total is "RM130.00" and does not match. One means the header is
        // not showing the day, and the category line alone would satisfy the
        // wait above.
        assertEquals(
            "The day header is not showing the whole day",
            2,
            compose.onAllNodesWithTextSafely("130.00"),
        )
        assertEquals(
            "The day header shows the total of the rows Paging has loaded, not " +
                "the day's. It is short by whatever is on the next page, and " +
                "short silently -- the number looks like a number",
            0,
            compose.onAllNodesWithTextSafely("120.00"),
        )
    }

    /**
     * One day, one header, even when the two rows fall on different clock days.
     *
     * `local_date` is the day the money was spent in the zone it was spent in
     * (15.7) and an imported row keeps the exporting device's, so the column
     * and the day of `occurred_at` are two different questions. This fixture
     * makes them disagree, and it is what a separator comparing the clock
     * instead of the column gets wrong -- by splitting one day into two
     * headers, each carrying the whole day's subtotal.
     *
     * The header key carries the row id (`LedgerItem.DayHeader.firstRowId`), so
     * a split day renders as two headings and the count below is what reports
     * them. Keyed by its date alone it collides in `LazyColumn`'s key map
     * instead, and the failure arrives as `IllegalArgumentException: Key
     * "day-20260907" was already used` out of measure.
     */
    @Test fun twoRowsSharingALocalDateGetOneDayHeaderThoughTheirClockDaysDiffer() {
        val dao = freshLedger()
        val food = categoryId(FOOD)
        dao.insert(
            ledgerTxn(amountSen = 1_100L, occurredAt = SEP_7_NOON, localDate = SEP_7, categoryId = food),
        )
        dao.insert(
            ledgerTxn(
                amountSen = 2_200L,
                occurredAt = SEP_7_NOON - 40 * 60 * 60 * 1_000L,
                localDate = SEP_7,
                categoryId = food,
            ),
        )

        screenFor(FIXED_MONTH)

        compose.waitForText("22.00", "The second row was never drawn")
        assertEquals(
            "Two headers for one local_date. The separator is comparing the day " +
                "of occurred_at rather than the stored column",
            1,
            compose.onAllNodesWithTextSafely("MON 7 SEP"),
        )
    }

    /**
     * **A date is drawn once, and its subtotal is drawn once.**
     *
     * The fixture is the X, Y, X sequence that used to reach the screen: three
     * rows an hour apart by `occurred_at`, the middle one dated the day
     * before. `local_date` is the day the money moved in the zone it moved in
     * (15.7), so an imported row or a flight west puts a date after itself.
     *
     * `TxnDao.feed` leads on `local_date`, so the day arrives in one piece.
     * Ordered `occurred_at DESC` it does not, and **both headings then print
     * the 7th's whole subtotal** -- 44.00 under one and 44.00 under the other,
     * with nothing on screen adding to either.
     *
     * The counts are the assertion, not the presence: presence was already
     * true while the number was being printed twice.
     */
    @Test fun aDateSplitByAnotherDayIsDrawnOnceWithOneSubtotal() {
        val dao = freshLedger()
        val food = categoryId(FOOD)
        dao.insert(
            ledgerTxn(amountSen = 1_100L, occurredAt = SEP_7_NOON, localDate = SEP_7, categoryId = food),
        )
        dao.insert(
            ledgerTxn(
                amountSen = 2_200L,
                occurredAt = SEP_7_NOON - 60 * 60 * 1_000L,
                localDate = SEP_6,
                categoryId = food,
            ),
        )
        dao.insert(
            ledgerTxn(
                amountSen = 3_300L,
                occurredAt = SEP_7_NOON - 2 * 60 * 60 * 1_000L,
                localDate = SEP_7,
                categoryId = food,
            ),
        )

        screenFor(FIXED_MONTH)

        compose.waitForText(
            "33.00",
            "The third row was never drawn -- which is what a duplicate list " +
                "key looks like when it does not throw outright",
        )
        assertEquals(
            // 11.00 + 33.00, from the fixture -- never from the aggregate the
            // screen read, and never from the formatter under test.
            "The 7th's subtotal is not drawn exactly once. Twice means the feed " +
                "split the day and both headings claimed the whole of it; none " +
                "means the aggregate did not land",
            1,
            compose.onAllNodesWithTextSafely("44.00"),
        )
        assertEquals(
            "The 7th was drawn under two headings. Each of them looks " +
                "subtotals[SEP_7] up, so the day's whole total is printed twice " +
                "with nothing on screen summing to either copy",
            1,
            compose.onAllNodesWithTextSafely("MON 7 SEP"),
        )
        assertEquals(
            "The interrupting day is not drawn exactly once",
            1,
            compose.onAllNodesWithTextSafely("SUN 6 SEP"),
        )
    }

    // ---- the chip, which is the one control that writes ------------------

    /**
     * §9.1's chip, end to end: the tap, the write, and both numbers that have
     * to move with it.
     *
     * **Through [screenFor], and that is the whole design of this test.**
     * `LedgerScreenContent` is stateless, so a test that hands it one page of
     * items, taps, and then hands it a different page proves only that the
     * test can change its own input. Here the tap goes through the real view
     * model into the real encrypted database: Room invalidates the
     * `PagingSource` and the row redraws itself, and the summary moves only
     * because `assignCategory` re-reads the aggregates, which nothing
     * invalidates.
     *
     * The expected strings are literals from the seed and the fixture, never
     * built by the formatter under test.
     *
     * **Dated in [DEAD_MONTH] rather than in [FIXED_MONTH]**, and that is the
     * only thing that makes `assignCategory`'s month parameter falsifiable:
     * this month is September 2026, so a fixture dated in it would pass with
     * the parameter dropped and the post-write refresh reading `now()`.
     */
    @Test fun assigningACategoryFromTheChipMovesTheRowAndTheSummary() {
        val dao = freshLedger()
        dao.insert(
            ledgerTxn(
                amountSen = 5_000L,
                occurredAt = DEAD_MONTH_NOON,
                categoryId = categoryId(UNCATEGORIZED),
            ),
        )

        screenFor(DEAD_MONTH)

        compose.waitForText(
            UNCATEGORIZED,
            "The summary never named the category the row is filed under, so " +
                "there is nothing for the assignment to move it off",
        )
        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForText(
            PICKER_HEADING,
            "The picker did not open, or it drew no categories to choose from",
        )
        compose.onNodeWithText(FOOD).performClick()

        compose.waitForText(
            // Spelled out, not `FOOD + Separator + SOURCE_LABEL`: built from
            // the screen's own constant the expectation agrees with whatever
            // separator the screen picks. Measured by setting `Separator` to
            // " | ", which this expectation survived and now fails on.
            "$FOOD · $SOURCE_LABEL",
            "The row does not say which category it is in. The write may have " +
                "landed and the feed not redrawn -- Room invalidates the " +
                "PagingSource on the UPDATE, so a row that never changes is a " +
                "row the screen is not reading the category of",
        )
        assertEquals(
            "The summary's top category did not move. The feed invalidates " +
                "itself and the three aggregates do not, so a screen that " +
                "skips the re-read draws the new category on the row above a " +
                "total still credited to the old one",
            1,
            compose.onAllNodesWithTextSafely(FOOD),
        )
        assertEquals(
            "The summary still credits Uncategorized, which no row is in",
            0,
            compose.onAllNodesWithTextSafely(UNCATEGORIZED),
        )
        assertEquals(
            "A row that already has a category still offers the chip. §6.1's " +
                "learned rules are out of this milestone, so a second tap has " +
                "nothing to teach and the control would promise otherwise",
            0,
            compose.onAllNodesWithTextSafely(CATEGORY_CHIP),
        )
    }

    /**
     * The wiring, isolated: which row and which category the chip reports.
     *
     * [assigningACategoryFromTheChipMovesTheRowAndTheSummary] cannot see this
     * -- one row and one chosen category agree with a screen that hardcoded
     * either. The row id here is a fixture value the screen has no other way
     * to know.
     */
    @Test fun choosingFromThePickerReportsTheRowItWasOpenedFromAndTheCategoryChosen() {
        val row = LedgerItem.Row(
            ledgerTxn(amountSen = 1_234L, localDate = SEP_7, categoryId = UNCATEGORIZED_ID, id = 77L),
        )
        val assigned = mutableListOf<Pair<Long, Long>>()
        content(
            items = listOf(LedgerItem.DayHeader(SEP_7, row.txn.id), row),
            refresh = LoadState.NotLoading(true),
            categories = TWO_CATEGORIES,
            uncategorizedId = UNCATEGORIZED_ID,
            onAssign = { txnId, categoryId -> assigned += txnId to categoryId },
        )
        compose.waitForText("12.34", "The row was never drawn")

        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForText("Groceries", "The chip did not open the picker")
        compose.onNodeWithText("Groceries").performClick()

        assertEquals(
            "The chip reported the wrong row, the wrong category, or fired " +
                "more than once -- and a chip that assigns the category of a " +
                "row the user did not tap is worse than one that does nothing",
            listOf(77L to 42L),
            assigned,
        )
    }

    /**
     * No `Uncategorized` row in `category`, so no chip.
     *
     * The seed puts the row there and only an import can take it away. The
     * screen degrades rather than resolving the id through
     * `requireUncategorizedId`, for the reason
     * [aMissingUncategorizedRowLeavesTheIdNullAndTheRefreshStanding] records.
     */
    @Test fun aChipIsNotDrawnWhileTheUncategorizedIdIsUnknown() {
        val row = LedgerItem.Row(
            ledgerTxn(amountSen = 1_234L, localDate = SEP_7, categoryId = UNCATEGORIZED_ID),
        )
        content(
            items = listOf(LedgerItem.DayHeader(SEP_7, row.txn.id), row),
            refresh = LoadState.NotLoading(true),
            categories = TWO_CATEGORIES,
            uncategorizedId = null,
        )
        compose.waitForText("12.34", "The row was never drawn")

        assertEquals(
            "A chip was drawn while nothing knows which rows are " +
                "uncategorized, so it is offered on every row and assigns from " +
                "none of them",
            0,
            compose.onAllNodesWithTextSafely(CATEGORY_CHIP),
        )
    }

    /**
     * `Uncategorized` is not one of the things the sheet offers.
     *
     * The chip is drawn on an uncategorized row and nowhere else, so tapping
     * `Uncategorized` in the sheet it opens is the natural "never mind" -- and
     * it is not a no-op. `setCategory` writes `user_edited = 1`, and §5.5
     * excludes an edited transaction from the re-parse comparison entirely, so
     * that one tap opts the row out of every future pack fix while nothing on
     * screen changes.
     *
     * The category is in [categories] here under the id the screen is told is
     * the uncategorized one, so the only thing that can keep it off the sheet
     * is the filter.
     */
    @Test fun thePickerDoesNotOfferTheCategoryTheRowIsAlreadyIn() {
        val row = LedgerItem.Row(
            ledgerTxn(amountSen = 1_234L, localDate = SEP_7, categoryId = UNCATEGORIZED_ID, id = 77L),
        )
        content(
            items = listOf(LedgerItem.DayHeader(SEP_7, row.txn.id), row),
            refresh = LoadState.NotLoading(true),
            categories = TWO_CATEGORIES + SEEDED_UNCATEGORIZED,
            uncategorizedId = UNCATEGORIZED_ID,
        )
        compose.waitForText("12.34", "The row was never drawn")

        compose.onNodeWithText(CATEGORY_CHIP).performClick()
        compose.waitForText(PICKER_HEADING, "The chip did not open the picker")
        compose.waitForText(
            "Groceries",
            "The picker opened and drew none of the categories it was handed",
        )

        assertEquals(
            "The picker offers Uncategorized on a row that is already in it. " +
                "Choosing it writes user_edited = 1, and §5.5 excludes an " +
                "edited row from the re-parse comparison for good -- so the " +
                "tap that looks like backing out is the one that opts the " +
                "transaction out of every future pack fix",
            0,
            compose.onAllNodesWithTextSafely(UNCATEGORIZED),
        )
    }

    /**
     * Nothing left to offer, so no chip.
     *
     * The guard on the filter above. With `Uncategorized` the only category in
     * the table the picker has no row to draw, so a chip here would take the
     * tap and open nothing. Gated at the chip and not only at the sheet, so
     * the tap has nowhere to go rather than nowhere to land.
     *
     * Only an import can produce this table -- §4's editor is out and
     * `deleteIfUnused` refuses a protected category -- which is the same
     * reachability as [aChipIsNotDrawnWhileTheUncategorizedIdIsUnknown]'s
     * missing seed row, and the reason both degrade the same way. The sheet
     * keeps its own gate for the restore path, where `categorizing` comes back
     * from `rememberSaveable` before the first refresh has delivered a
     * category; nothing driving `LedgerScreenContent` can reach that.
     */
    @Test fun noChipIsDrawnWhenTheOnlyCategoryIsTheOneTheRowIsIn() {
        val row = LedgerItem.Row(
            ledgerTxn(amountSen = 1_234L, localDate = SEP_7, categoryId = UNCATEGORIZED_ID, id = 77L),
        )
        content(
            items = listOf(LedgerItem.DayHeader(SEP_7, row.txn.id), row),
            refresh = LoadState.NotLoading(true),
            categories = listOf(SEEDED_UNCATEGORIZED),
            uncategorizedId = UNCATEGORIZED_ID,
        )
        compose.waitForText("12.34", "The row was never drawn")

        assertEquals(
            "A chip is drawn on a row whose category is the only one there " +
                "is. The picker drops the row's own category, so the sheet " +
                "it would open has nothing in it -- and the tap sets " +
                "`categorizing` and shows the user nothing at all",
            0,
            compose.onAllNodesWithTextSafely(CATEGORY_CHIP),
        )
    }

    /**
     * A row nothing can be assigned to still reads as the uncategorized row it
     * is.
     *
     * Whether the row is uncategorized is a fact about the row -- its
     * `category_id` is the one §7.1's gate files under -- and whether the chip
     * can be offered is a different question, which `Feed` asks by handing
     * `TxnRow` an action or nothing. ANDed into one flag, a screen with
     * nothing left to offer draws the row as though it were filed, and
     * `Uncategorized` is precisely where it is not filed.
     *
     * The fixture is [noChipIsDrawnWhenTheOnlyCategoryIsTheOneTheRowIsIn]'s,
     * and this is the other half of it: that one says the chip is withheld,
     * this one says withholding it does not re-label the row. The same flag
     * feeds `rowMark`, whose glyph is decorative and so has nothing on screen
     * to assert on -- `RowMarkRuleTest` covers the rule, and this covers what
     * the screen hands it.
     *
     * The expectation is spelled out rather than built from `Separator`, for
     * the reason [assigningACategoryFromTheChipMovesTheRowAndTheSummary]
     * measures.
     *
     * Falsified on emulator-5554 by ANDing the availability back into the flag
     * -- `hasChoices && !isExcluded && categoryId == uncategorizedId` at the
     * call site: the first assertion fails naming its own reason, having found
     * one `Uncategorized · Touch 'n Go eWallet` where it expected none.
     */
    @Test fun aRowWithNothingToAssignStillReadsAsUncategorized() {
        val row = LedgerItem.Row(
            ledgerTxn(amountSen = 1_234L, localDate = SEP_7, categoryId = UNCATEGORIZED_ID, id = 77L),
        )
        content(
            items = listOf(LedgerItem.DayHeader(SEP_7, row.txn.id), row),
            refresh = LoadState.NotLoading(true),
            categories = listOf(SEEDED_UNCATEGORIZED),
            uncategorizedId = UNCATEGORIZED_ID,
        )
        compose.waitForText("12.34", "The row was never drawn")

        assertEquals(
            "The small print files the row under Uncategorized. No chip is " +
                "drawn here, so the row reads exactly like a categorized one " +
                "-- and the only category it names is the absence of one",
            0,
            compose.onAllNodesWithTextSafely("Uncategorized · Touch 'n Go eWallet"),
        )
        assertEquals(
            "The small print is not the source alone. The category name is " +
                "dropped from an uncategorized row whether or not the chip " +
                "stands in its place",
            1,
            compose.onAllNodesWithTextSafely("Touch 'n Go eWallet"),
        )
    }

    /**
     * A row with no category name and no source draws no small print at all,
     * rather than an empty line of it.
     *
     * Both `source_package` and `source_label` are nullable, so the state is
     * reachable. The assertion is geometric because the defect is: an empty
     * `Row` still takes its 2dp of top padding, and nothing in the semantics
     * tree records a layout node that draws nothing. The merchant and the
     * amount are centred on each other, so 2dp of dead space under the
     * merchant moves the two centres apart: measured at 2.50px with the row
     * emitted unconditionally and 0.50px with it gated, on Pixel_10a API 37 at
     * density 2.625 (0.95dp and 0.19dp there).
     *
     * **The bound is in pixels, and that is not a formatting choice.** The
     * 0.50px floor is exactly half a layout pixel -- the two children round to
     * opposite parities -- so it is a constant in pixels and grows in dp as
     * density falls, reaching 0.5dp at mdpi. A dp bound tight enough to catch
     * the 0.95dp signal here would therefore fail a correct implementation on
     * a low-density device. 1px sits between the two at every density.
     */
    @Test fun aRowWithNoCategoryAndNoSourceDrawsNoSmallPrintLine() {
        val bare = ledgerTxn(amountSen = 1_234L, localDate = SEP_7, categoryId = 7L)
            .copy(sourceLabel = null, sourcePackage = null)
        content(
            items = listOf(LedgerItem.DayHeader(SEP_7, bare.id), LedgerItem.Row(bare)),
            refresh = LoadState.NotLoading(true),
            // No category by that id, so the row has no name to print either.
            categories = TWO_CATEGORIES,
            uncategorizedId = null,
        )
        compose.waitForText("12.34", "The row was never drawn")

        val merchant = compose.onNodeWithText(MERCHANT).getUnclippedBoundsInRoot()
        val amount = compose.onNodeWithText("12.34").getUnclippedBoundsInRoot()
        val apart = abs(
            ((merchant.top + merchant.bottom) / 2f - (amount.top + amount.bottom) / 2f).value,
        )
        val apartPx = apart * compose.density.density

        assertTrue(
            "The amount sits ${apart}dp (${apartPx}px) off the merchant's " +
                "centre. The row has no chip, no category and no source, so " +
                "the line under the merchant is empty -- and an empty line is " +
                "still laid out with its top padding, which is the gap this " +
                "measures",
            apartPx < 1f,
        )
    }

    /**
     * A tap on a database that has stopped opening (§11.1), which is reachable:
     * the chip is drawn from a refresh that succeeded, and the key can go away
     * after it.
     *
     * The subject is that the write sits inside `CaptureStorage.guarded`.
     * `Databases.txnDao` throws from the *open*, `assignCategory` runs on
     * `viewModelScope`, and nothing in that scope catches -- so unguarded the
     * throw leaves the coroutine, which in production means the default
     * uncaught handler and `KillApplicationHandler`. `MainThreadRefreshTest`
     * measures that mechanism and records why a `CoroutineExceptionHandler`
     * would not see it.
     *
     * **What this test can observe is the flag, not the kill.** Every test in
     * this class runs inside `createComposeRule`'s
     * `AndroidComposeUiTestEnvironment`, which collects uncaught coroutine
     * exceptions itself and attaches them to the test result -- measured by
     * running this with the guard removed: the default handler was never
     * called, and the failure arrived as `Suppressed:
     * my.pinged.data.DatabaseUnreadableException` on the assertion below, with
     * the `SQLiteNotADatabaseException` as that one's `Caused by`. So the
     * assertion is the flag both the screen and the banner read, which is
     * false unless the failure was caught here.
     */
    @Test fun assigningACategoryOnADatabaseThatWillNotOpenIsReportedRatherThanThrown() {
        context.corruptTheDatabase()
        val viewModel = ledgerViewModel()

        runBlocking {
            withTimeout(TIMEOUT) { viewModel.assignCategory(1L, 1L, FIXED_MONTH).join() }
        }

        assertTrue(
            "The write failed and the one flag the screen and the banner both " +
                "read stays false, so the ledger keeps drawing as if it could " +
                "be read -- and the throw is loose in viewModelScope, where " +
                "nothing outside this test environment catches it",
            viewModel.storageUnavailable.value,
        )
    }

    /**
     * The seed's `Uncategorized` row deleted: the refresh finishes, and the id
     * it could not resolve stays null.
     *
     * The view-model half of what
     * [aChipIsNotDrawnWhileTheUncategorizedIdIsUnknown] asserts about the
     * screen, and the only thing that tells `uncategorizedIdOrNull` apart from
     * `requireUncategorizedId` here. That one throws [IllegalStateException],
     * which is outside the `DatabaseUnavailableException` family
     * `CaptureStorage.guarded` catches, so it would leave `refresh` on
     * `viewModelScope` with no aggregate read at all -- the home screen down
     * on a database that is perfectly readable, instead of a missing chip.
     *
     * Only an import can produce this state (`deleteIfUnused` refuses a
     * protected category), which is why the fixture reaches for the primitive.
     */
    @Test fun aMissingUncategorizedRowLeavesTheIdNullAndTheRefreshStanding() {
        freshLedger()
        val categories = Databases.categoryDao(context)
        categories.deleteRow(requireNotNull(categories.uncategorizedIdOrNull()))
        val viewModel = ledgerViewModel()

        runBlocking { withTimeout(TIMEOUT) { viewModel.refresh(DEAD_MONTH).join() } }

        assertTrue(
            "The refresh never reached the summary. Resolving the " +
                "uncategorized id threw, and the throw is loose in " +
                "viewModelScope -- so a missing seed row takes the whole " +
                "screen down rather than one chip",
            viewModel.read.value.summary != null,
        )
        assertEquals(
            "The screen was handed an uncategorized id that no category has, " +
                "so it would draw the chip on whichever rows happen to carry " +
                "that number and assign from none of them",
            null,
            viewModel.read.value.uncategorizedId,
        )
    }

    /**
     * One refresh publishes one snapshot, and never a mixture of two.
     *
     * The pair that costs is `categories` and `uncategorizedId`.
     * `LedgerScreenContent` builds the picker's `choices` out of both, so a
     * composition that caught the new list beside the old id offers an
     * uncategorized row the category it is already in -- and that tap is not
     * a way out: `setCategory` writes `user_edited`, which §5.5 excludes from
     * the re-parse comparison for good. Published a field at a time there is a
     * window of exactly that between two assignments.
     *
     * **An unconfined collector, because a conflating one cannot see it.** A
     * `StateFlow` drops values a collector was too slow for, so collected on
     * any other dispatcher this passes over a holder that published four
     * partial states. `Dispatchers.Unconfined` resumes in the emitting thread
     * at the assignment itself, which is what records every intermediate.
     *
     * Falsified on emulator-5554 by publishing the same four fields one at a
     * time -- `_read.value = _read.value.copy(daySubtotals = ...)` and so on,
     * which is the identical defect inside the collapsed type. The first
     * assertion fails naming two partial snapshots, the first of them
     * `categories=14 uncategorizedId=null summary=absent`: the pair the
     * picker's filter is built from, published apart.
     */
    @Test fun oneRefreshPublishesOneSnapshotAndNeverAMixtureOfTwo() {
        freshLedger()
        val viewModel = ledgerViewModel()
        val reads = Collections.synchronizedList(mutableListOf<LedgerRead>())

        runBlocking {
            val collector = launch(Dispatchers.Unconfined) {
                viewModel.read.collect { reads += it }
            }
            withTimeout(TIMEOUT) { viewModel.refresh(DEAD_MONTH).join() }
            collector.cancel()
        }

        // The initial value is the one legitimate partial state: nothing has
        // been read, and it says so in every field at once.
        val partial = reads
            .filter { it != LedgerRead() }
            .filter { it.categories.isEmpty() || it.uncategorizedId == null || it.summary == null }
            .map {
                "categories=${it.categories.size} uncategorizedId=${it.uncategorizedId} " +
                    "summary=" + (if (it.summary == null) "absent" else "present")
            }
        assertEquals(
            "A snapshot published half of one read. The screen composes against " +
                "all of these at once, so the pair categories/uncategorizedId " +
                "is enough on its own: filtered by an id that has not landed, " +
                "the picker offers the row the very category §5.5 makes " +
                "permanent",
            emptyList<String>(),
            partial,
        )
        assertTrue(
            "No published snapshot carried any categories, so the filter above " +
                "had nothing to reject and this asserts nothing",
            reads.any { it.categories.isNotEmpty() },
        )
    }

    /**
     * The `category` table is read once for the life of the holder, and the
     * three aggregates on every refresh.
     *
     * `assignCategory` refreshes, so "once per refresh" was once per chip tap:
     * fourteen rows re-read to answer a control whose whole purpose is to move
     * a row between them.
     *
     * **The staleness is the assertion, and that is deliberate.** Nothing in
     * this milestone writes `category` -- §4's editor is out and
     * `deleteIfUnused` refuses a protected row -- so the only way to show the
     * second refresh did not re-read is to change the table underneath it the
     * way only an import could, which is the primitive
     * [aMissingUncategorizedRowLeavesTheIdNullAndTheRefreshStanding] reaches
     * for too. The inserted payment is what keeps the test honest in the other
     * direction: a second refresh that read nothing at all would satisfy the
     * first two assertions and fail the third.
     *
     * Falsified on emulator-5554 by dropping the memo and reading the table on
     * every refresh: the first assertion fails naming its own reason, with the
     * expected fourteen categories against a list the deleted row is gone
     * from.
     */
    @Test fun theCategoryTableIsReadOnceAndTheAggregatesEveryTime() {
        val dao = freshLedger()
        val viewModel = ledgerViewModel()
        runBlocking { withTimeout(TIMEOUT) { viewModel.refresh(DEAD_MONTH).join() } }
        val first = viewModel.read.value
        assertTrue(
            "The first refresh published no categories, so there is nothing " +
                "for a second read to differ from",
            first.categories.isNotEmpty(),
        )

        val categories = Databases.categoryDao(context)
        categories.deleteRow(requireNotNull(categories.uncategorizedIdOrNull()))
        dao.insert(
            ledgerTxn(
                amountSen = 5_000L,
                occurredAt = DEAD_MONTH_NOON,
                categoryId = categoryId(FOOD),
            ),
        )
        runBlocking { withTimeout(TIMEOUT) { viewModel.refresh(DEAD_MONTH).join() } }

        assertEquals(
            "The second refresh re-read `category`. Nothing in this milestone " +
                "writes that table, and refresh() runs after every chip tap, so " +
                "the read is two statements per tap that cannot answer anything " +
                "new",
            first.categories,
            viewModel.read.value.categories,
        )
        assertEquals(
            "The uncategorized id was resolved again for the same reason, and " +
                "it is the other half of the same one read",
            first.uncategorizedId,
            viewModel.read.value.uncategorizedId,
        )
        assertEquals(
            "The month total does not count a payment inserted before this " +
                "refresh, so the aggregates were memoized along with the " +
                "categories -- which is the read that must not stop happening",
            listOf(5_000L),
            requireNotNull(viewModel.read.value.summary).totals.map { it.netSen },
        )
    }

    // ---- month trust, which is a fact about capture_day ------------------

    @Test fun aMonthWithNoBoundDaysAtAllIsReportedUntrustworthy() {
        freshLedger()
        val viewModel = ledgerViewModel()

        runBlocking { withTimeout(TIMEOUT) { viewModel.refresh(DEAD_MONTH).join() } }

        assertEquals(
            "A month the app was not alive for is reported as trustworthy. The " +
                "total is then wrong, looks right, and the user has no way to " +
                "suspect it",
            false,
            requireNotNull(viewModel.read.value.summary).trustworthy,
        )
    }

    /**
     * A past month, fully bound, is trustworthy -- and the month is **28 days**
     * for a reason that is about falsification rather than about February.
     *
     * The mutation `elapsed = LocalDate.now().dayOfMonth` unconditionally --
     * dropping the past-month branch -- reads a 31-day dead month bound for all
     * 31 days as trustworthy on every possible day of any month, so a January
     * fixture cannot see it. Twenty-eight bound days are short of today's number
     * on the 29th, 30th and 31st, which is exactly where
     * [aMonthOneDayShortOfFullyBoundIsReportedUntrustworthy] stops seeing it.
     * The pair covers every day of the year.
     */
    @Test fun aMonthWithEveryDayBoundIsReportedTrustworthy() {
        freshLedger()
        bindDays(SHORT_DEAD_MONTH, 1..SHORT_DEAD_MONTH.lengthOfMonth())
        val viewModel = ledgerViewModel()

        runBlocking { withTimeout(TIMEOUT) { viewModel.refresh(SHORT_DEAD_MONTH).join() } }

        assertEquals(
            "Every day of the month is recorded as bound and the total is still " +
                "labelled do-not-trust, which teaches the user to ignore the label",
            true,
            requireNotNull(viewModel.read.value.summary).trustworthy,
        )
    }

    /**
     * A month over, missing one day, is a month with a gap.
     *
     * The guard on the `else now.lengthOfMonth()` half of the elapsed-day rule:
     * a month that is not the current one has elapsed entirely, so the number of
     * days that must be bound is its length and not today's date. Thirty of
     * thirty-one is short by one under the rule, and long by plenty under the
     * mutation `elapsed = LocalDate.now().dayOfMonth`, which then calls this
     * month trustworthy on any day but the 31st -- see
     * [aMonthWithEveryDayBoundIsReportedTrustworthy] for the 31st.
     */
    @Test fun aMonthOneDayShortOfFullyBoundIsReportedUntrustworthy() {
        freshLedger()
        bindDays(DEAD_MONTH, 1 until DEAD_MONTH.lengthOfMonth())
        val viewModel = ledgerViewModel()

        runBlocking { withTimeout(TIMEOUT) { viewModel.refresh(DEAD_MONTH).join() } }

        assertEquals(
            "A finished month with a day nobody was listening on is reported as " +
                "trustworthy. Its elapsed length is its whole length, and the " +
                "day that is missing is a day of spending the total does not hold",
            false,
            requireNotNull(viewModel.read.value.summary).trustworthy,
        )
    }

    /**
     * The current month, bound for every day that has happened, is trustworthy.
     *
     * The only test in the file that reaches the `YearMonth.now() == now` half
     * of the elapsed-day rule, and so the only one that runs the branch
     * production always takes: `refresh()` here is called with its default
     * argument on purpose. Its subject is the invariant the comment beside that
     * branch states -- days that have not happened yet are not gaps -- which
     * every `DEAD_MONTH` fixture in this file leaves untouched, because a past
     * month takes the other branch.
     *
     * The mutation `elapsed = now.lengthOfMonth()` unconditionally is what this
     * kills, on every day of a month but its last. On the last day the two are
     * equal for the current month and equal by definition for a past one, so
     * that mutation is not merely uncaught but unobservable, and nothing here
     * pretends otherwise.
     */
    @Test fun theCurrentMonthBoundForEveryDaySoFarIsReportedTrustworthy() {
        freshLedger()
        val today = java.time.LocalDate.now()
        bindDays(YearMonth.from(today), 1..today.dayOfMonth)
        val viewModel = ledgerViewModel()

        runBlocking { withTimeout(TIMEOUT) { viewModel.refresh().join() } }

        assertEquals(
            "This month is bound for every day that has happened and is still " +
                "labelled do-not-trust. The days left in the month are not gaps " +
                "-- counting them greys out every total until the last of the " +
                "month, which is the label on for so long that it means nothing",
            true,
            requireNotNull(viewModel.read.value.summary).trustworthy,
        )
    }

    /**
     * `capture_day` rows saying the listener was bound on [days] of [month].
     *
     * The day is packed by `LocalDates`, which is where `refresh` gets the
     * bounds it compares them against. Packed here by hand it is the same
     * arithmetic written out twice, so a wrong encoding cancels and
     * [aMonthWithEveryDayBoundIsReportedTrustworthy] stays green either way;
     * `DaoTest` in `:core:data` is what pins the packing itself, against
     * literal `yyyymmdd` decimals.
     */
    private fun bindDays(month: YearMonth, days: Iterable<Int>) {
        val dao = Databases.captureDayDao(context)
        days.forEach { dao.recordListenerBound(LocalDates.of(month.atDay(it)), true) }
    }

    // ---- fixtures --------------------------------------------------------

    private fun freshLedger(): TxnDao = context.freshLedger()

    private fun categoryId(name: String): Long =
        Databases.categoryDao(context).all().single { it.name == name }.id

    private fun ledgerViewModel() =
        LedgerViewModel(context.applicationContext as Application)

    /** The whole screen, reading the real database through its own holder. */
    private fun screen() {
        val viewModel = ledgerViewModel()
        compose.setContent {
            PingedTheme { LedgerScreen(viewModel = viewModel, onOpenSources = {}) }
        }
    }

    /**
     * The screen over the real database, refreshed for the month a fixture is
     * dated in rather than for today, and settled before it returns.
     *
     * `LedgerScreenContent` and an explicit `refresh(month)`, not [screen]: the
     * stateful screen refreshes `YearMonth.now()` from its resume effect, so a
     * fixture with a **fixed** date -- the only kind whose day heading has a
     * fixed spelling, and so the only kind whose heading can be asserted
     * without re-deriving it the way the screen derives it -- would have no
     * aggregate at all. The resume effect itself is `LedgerResumeTest`'s
     * subject.
     *
     * The feed first and the aggregate second, and [loaded] is how the feed is
     * waited for: nothing may block the test thread before the composition has
     * settled, because the Compose clock is driven from it.
     */
    private fun screenFor(
        month: YearMonth,
        loaded: AtomicInteger = AtomicInteger(0),
    ): LedgerViewModel {
        val viewModel = ledgerViewModel()
        compose.setContent {
            PingedTheme {
                val items = viewModel.items.collectAsLazyPagingItems()
                val read by viewModel.read.collectAsState()
                loaded.set(items.itemCount)
                LedgerScreenContent(
                    items = items,
                    read = read,
                    storageUnavailable = false,
                    // [month], not `assignCategory`'s default: the write is
                    // followed by a refresh, and a refresh of today's month
                    // would empty the summary this fixture is dated for.
                    onAssign = { txnId, categoryId ->
                        viewModel.assignCategory(txnId, categoryId, month)
                    },
                    onOpenSources = {},
                )
            }
        }
        compose.waitUntil(TIMEOUT) { loaded.get() > 0 }
        runBlocking { withTimeout(TIMEOUT) { viewModel.refresh(month).join() } }
        return viewModel
    }

    /**
     * The stateless screen over a fixed page of items and a fixed load state.
     *
     * `PagingData.from` with explicit source load states is what makes the
     * loading and error branches reachable: nothing a test can put in a
     * database produces a refresh that is still in flight when the assertion
     * runs.
     */
    private fun content(
        items: List<LedgerItem>,
        refresh: LoadState,
        subtotals: Map<LocalDate, List<CurrencyTotal>> = emptyMap(),
        summary: MonthSummary? = null,
        storageUnavailable: Boolean = false,
        categories: List<Category> = emptyList(),
        uncategorizedId: Long? = null,
        onAssign: (Long, Long) -> Unit = { _, _ -> },
        onOpenSources: () -> Unit = {},
    ) {
        // The four read fields stay separate parameters here and are packed at
        // the last moment: each state below is named by the one field it is
        // about, and a `LedgerRead(...)` per call site would bury that.
        val read = LedgerRead(
            daySubtotals = subtotals,
            summary = summary,
            categories = categories,
            uncategorizedId = uncategorizedId,
        )
        val flow: Flow<PagingData<LedgerItem>> = flowOf(
            PagingData.from(
                items,
                LoadStates(
                    refresh = refresh,
                    prepend = LoadState.NotLoading(true),
                    append = LoadState.NotLoading(true),
                ),
            ),
        )
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = flow.collectAsLazyPagingItems(),
                    read = read,
                    storageUnavailable = storageUnavailable,
                    onAssign = onAssign,
                    onOpenSources = onOpenSources,
                )
            }
        }
    }

    /**
     * `waitUntil` a string is on screen, failing with what was expected of it.
     *
     * A bare `waitUntil` reports only `ComposeTimeoutException`, which under
     * falsification says that something did not appear and not what it was.
     */
    private fun ComposeContentTestRule.waitForText(text: String, why: String) {
        val appeared = runCatching {
            waitUntil(TIMEOUT) { onAllNodesWithTextSafely(text) > 0 }
        }.isSuccess
        assertTrue("$why -- waited ${TIMEOUT}ms for '$text'", appeared)
    }

    /** Every string the semantics tree is carrying, leaves included. */
    private fun ComposeContentTestRule.allText(): List<String> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    private companion object {
        const val NOTHING_CAPTURED = "NOTHING CAPTURED YET"
        const val NOTHING_COUNTED = "NOTHING COUNTED THIS MONTH"
        const val DO_NOT_TRUST = "DO NOT TRUST · CAPTURE HAS GAPS THIS MONTH"

        /** A seeded category, named here because the summary has to print a name. */
        const val FOOD = "Food & Drinks"

        /** The seeded category §7.1's gate files an unmatched merchant under. */
        const val UNCATEGORIZED = "Uncategorized"

        /**
         * An id no seed row can have, for the fixtures that never see a
         * database: `category.id` is `autoGenerate`, so the seeded fourteen are
         * 1..14 and nothing the screen reads can collide with this by accident.
         */
        const val UNCATEGORIZED_ID = 900L

        /**
         * What a stateless picker is offered. Two, not one: a picker that
         * reports whichever row was tapped and a picker that reports its only
         * row are the same picker until there are two.
         */
        val TWO_CATEGORIES = listOf(
            Category(id = 41L, name = "Petrol & tolls", iconKey = "fuel", sortOrder = 0),
            Category(id = 42L, name = "Groceries", iconKey = "shopping-basket", sortOrder = 1),
        )

        /**
         * The seed's own `Uncategorized`, shaped as `Seed.categories` shapes
         * it apart from the id, which is [UNCATEGORIZED_ID] because these
         * fixtures never see a database: what the picker would offer if the
         * screen handed it everything it was given.
         */
        val SEEDED_UNCATEGORIZED = Category(
            id = UNCATEGORIZED_ID,
            name = UNCATEGORIZED,
            iconKey = "circle-dashed",
            sortOrder = 13,
            isProtected = true,
        )

        /**
         * The day the dated fixtures fall on, spelled "MON 7 SEP".
         *
         * Fixed rather than today, because a heading's expected spelling has to
         * be a literal: the suite once built it with the screen's own formatter
         * and therefore agreed with `Locale.ROOT`'s "M09" all the way onto a
         * device.
         */
        val SEP_7 = LocalDate(20_260_907)

        /** The day before [SEP_7], "SUN 6 SEP", for fixtures that need two days. */
        val SEP_6 = LocalDate(20_260_906)

        /** The month [SEP_7] falls in, which the dated fixtures are refreshed for. */
        val FIXED_MONTH: YearMonth = YearMonth.of(2026, 9)

        /**
         * Midday on [SEP_7] in the device's zone, so `occurred_at` and
         * `local_date` agree in any zone the emulator is set to -- and so a
         * fixture can walk a couple of hours either side of it without
         * crossing a day.
         */
        val SEP_7_NOON: Long = java.time.LocalDate.of(2026, 9, 7)
            .atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        /**
         * Longer than Paging's initial load, which is three pages: at
         * `LedgerViewModel.PAGE_SIZE` that is 120 rows, so this is the smallest
         * round number that puts a page boundary inside one day.
         */
        const val ROWS_IN_A_STRADDLED_DAY = 130

        /** A month entirely in the past, so its elapsed length is its length. 31 days. */
        val DEAD_MONTH: YearMonth = YearMonth.of(2020, 1)

        /**
         * Midday on 7 January 2020, inside [DEAD_MONTH], in the device's zone
         * so `occurred_at` and `local_date` agree wherever the emulator is set.
         *
         * A fixture in a month that is over is what lets a test see whether a
         * read was scoped to the month it was asked for or to today's.
         */
        val DEAD_MONTH_NOON: Long = java.time.LocalDate.of(2020, 1, 7)
            .atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        /**
         * Also entirely past, and **28 days** -- shorter than any day-of-month
         * can be, which is what makes `aMonthWithEveryDayBoundIsReportedTrustworthy`
         * able to see a mutation that reads the current day for a finished month.
         */
        val SHORT_DEAD_MONTH: YearMonth = YearMonth.of(2021, 2)

        /** Any figure with sen on it. `[0-9]`, never a shorthand class. */
        val AMOUNT = Regex("[0-9]+\\.[0-9]{2}")
    }
}
