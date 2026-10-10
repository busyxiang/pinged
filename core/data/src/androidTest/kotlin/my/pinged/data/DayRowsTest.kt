package my.pinged.data

import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.TxnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TxnDao.dayRows` is the feed cut to one `local_date` (#69): the same rows,
 * names and order as `feed()` shows for that day, and nothing from any other.
 *
 * Checked against `feed()` itself rather than against a list typed out here, so
 * a clause that drifts between the two -- a join, the `REJECTED` filter, the
 * tiebreaker -- fails this whichever side it drifted on.
 */
@RunWith(AndroidJUnit4::class)
class DayRowsTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private fun feed(): List<FeedRow> = runBlocking {
        val page = db.txnDao().feed().load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 100, placeholdersEnabled = false),
        )
        (page as PagingSource.LoadResult.Page).data
    }

    @Test fun aDaysRowsAreTheFeedsRowsForThatDayInTheFeedsOrder() {
        val cat = db.categoryDao().requireUncategorizedId()
        val txns = db.txnDao()
        // The day before, the day, the day after: the day's rows must not be
        // the whole table.
        txns.insert(sampleTxn(occurredAt = 1_000L, amountSen = 1L, categoryId = cat, localDate = SEP_6))
        txns.insert(sampleTxn(occurredAt = 9_000L, amountSen = 9L, categoryId = cat, localDate = SEP_8))
        // On the 7th: two sharing a second (the tiebreaker), a pending one, an
        // excluded one, a rejected one, and one whose clock says the 8th
        // (`local_date` decides, spec 15.7).
        txns.insert(sampleTxn(occurredAt = 5_000L, amountSen = 51L, categoryId = cat, localDate = SEP_7))
        txns.insert(sampleTxn(occurredAt = 5_000L, amountSen = 52L, categoryId = cat, localDate = SEP_7))
        txns.insert(
            sampleTxn(occurredAt = 4_000L, amountSen = 40L, categoryId = cat, localDate = SEP_7, state = TxnState.PENDING),
        )
        txns.insert(sampleTxn(occurredAt = 3_000L, amountSen = 30L, categoryId = cat, localDate = SEP_7, isExcluded = true))
        txns.insert(
            sampleTxn(occurredAt = 2_000L, amountSen = 20L, categoryId = cat, localDate = SEP_7, state = TxnState.REJECTED),
        )
        txns.insert(sampleTxn(occurredAt = 99_000L, amountSen = 99L, categoryId = cat, localDate = SEP_7))
        // A name set on the merchant every row carries, so the joins are in
        // what is compared.
        db.merchantIdentityDao().setName("A", "Renamed")

        val expected = feed().filter { it.txn.localDate == SEP_7 }
        val day = db.txnDao().dayRows(SEP_7)

        assertEquals(
            "The fixture is wrong: the feed should hold five rows on the 7th",
            listOf(99L, 52L, 51L, 40L, 30L),
            expected.map { it.txn.amountSen },
        )
        assertEquals("The day's rows are not the feed's rows for that day, in its order", expected, day)
        assertTrue("The merchant's name was not joined", day.all { it.displayName == "Renamed" })
    }

    @Test fun aDayWithNoRowsHasNone() {
        val cat = db.categoryDao().requireUncategorizedId()
        db.txnDao().insert(sampleTxn(occurredAt = 1_000L, amountSen = 1L, categoryId = cat, localDate = SEP_6))
        assertEquals(emptyList<FeedRow>(), db.txnDao().dayRows(SEP_7))
    }

    private companion object {
        val SEP_6 = LocalDate(20_260_906)
        val SEP_7 = LocalDate(20_260_907)
        val SEP_8 = LocalDate(20_260_908)
    }
}
