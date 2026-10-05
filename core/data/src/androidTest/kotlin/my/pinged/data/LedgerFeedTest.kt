package my.pinged.data

import androidx.paging.PagingSource
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
 * The feed's order and its membership. Its invalidation is `LeasedFeed`'s,
 * because Room's source alone does not guarantee it: see `FeedInvalidationTest`
 * in `:feature:ledger`.
 *
 * Order is asserted rather than assumed for two reasons. Paging makes an
 * arbitrary order an unstable one: without a tiebreaker two rows sharing
 * `occurred_at` can be returned on two pages or on none, and the symptom is a
 * duplicated or missing transaction rather than an error. And the list is
 * sectioned by `local_date`, which is not a function of `occurred_at`, so the
 * leading key decides whether a day arrives in one piece.
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

        assertEquals(
            "The feed is not newest first, so the payment a user just made is " +
                "not the one at the top of the screen they opened to see it",
            listOf(300L, 200L, 100L),
            loadFirst(10).map { it.amountSen },
        )
    }

    /**
     * A date is contiguous in the feed even when `occurred_at` says otherwise.
     *
     * `local_date` is the day the money moved in the zone it moved in (spec
     * 15.7), so it is not a function of `occurred_at`: an imported row keeps
     * the exporting device's day, and a device that flies west writes a day it
     * has already passed. Ordered by `occurred_at` alone the middle row here
     * splits the 7th in two.
     *
     * Amounts rather than ids, because what has to hold is the *grouping*: the
     * two rows of the 7th adjacent, in `occurred_at` order within the day.
     */
    @Test fun aDayStaysTogetherWhenOccurredAtDisagreesWithIt() {
        val cat = uncategorized()
        val sep6 = LocalDate(20_260_906)
        val sep7 = LocalDate(20_260_907)
        // Newest first by occurred_at: 100 (7th), 200 (6th), 300 (7th).
        db.txnDao().insert(
            sampleTxn(occurredAt = 3_000L, amountSen = 100L, categoryId = cat, localDate = sep7),
        )
        db.txnDao().insert(
            sampleTxn(occurredAt = 2_000L, amountSen = 200L, categoryId = cat, localDate = sep6),
        )
        db.txnDao().insert(
            sampleTxn(occurredAt = 1_000L, amountSen = 300L, categoryId = cat, localDate = sep7),
        )

        assertEquals(
            "The 7th is split in two by the 6th. A day-sectioned list gets two " +
                "headings for one date and prints that date's whole subtotal " +
                "under each of them",
            listOf(100L, 300L, 200L),
            loadFirst(10).map { it.amountSen },
        )
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

        assertEquals(
            "Rows sharing a second came back in an order the ids do not fix",
            ids.sortedDescending(), whole,
        )
        assertEquals(
            "The first page disagrees with the same rows read whole, which " +
                "under paging is a transaction shown twice or not at all",
            whole.take(3), firstThree,
        )
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
}
