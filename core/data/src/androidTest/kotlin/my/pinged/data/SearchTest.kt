package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 15.2's search, run through the shipped SQLCipher build, because that
 * is where FTS4 and its `unicode61` tokenizer come from: a JVM test cannot
 * see either. Every row is identified by its amount, which is unique here.
 */
@RunWith(AndroidJUnit4::class)
class SearchTest {
    private lateinit var db: PingedDatabase
    private var cat = 0L

    @Before fun setUp() {
        db = freshDatabase()
        cat = db.categoryDao().requireUncategorizedId()
    }

    @After fun tearDown() { db.close() }

    private fun add(
        amountSen: Long,
        name: String,
        raw: String = name.uppercase(),
        note: String? = null,
        date: LocalDate = SEP_7,
        categoryId: Long = cat,
        pkg: String? = GRAB_APP,
        state: TxnState = TxnState.COMMITTED,
        direction: Direction = Direction.EXPENSE,
        isExcluded: Boolean = false,
    ): Long = db.txnDao().insert(
        sampleTxn(
            amountSen = amountSen,
            occurredAt = amountSen,
            localDate = date,
            categoryId = categoryId,
            pkg = pkg,
            state = state,
            direction = direction,
            isExcluded = isExcluded,
        ).copy(merchantDisplay = name, merchantRaw = raw, merchantKey = raw, note = note),
    )

    private fun found(text: String, filter: SearchFilter = SearchFilter(), limit: Int = 100): List<Long> =
        db.txnDao().searchRows(text, filter, limit).map { it.txn.amountSen }

    @Test fun aWordFindsWhatStartsWithItAndNothingThatOnlyContainsIt() {
        add(1, "Grab")
        add(2, "GrabFood")
        add(3, "Scrabble Cafe")

        assertEquals(listOf(2L, 1L), found("gra"))
        // Spec 15.2's accepted product decision: FTS4 has no infix match,
        // which is why the field says "starts with".
        assertEquals(emptyList<Long>(), found("rab"))
    }

    @Test fun everyWordMustMatchSomewhereInTheRow() {
        add(1, "Grab", note = "airport")
        add(2, "Grab", note = "office")

        assertEquals(listOf(1L), found("grab air"))
    }

    @Test fun theRawNameAndTheNoteAreSearchedAsWellAsTheShownName() {
        add(1, "Shown", raw = "DUITNOWQR-KEDAI KOPI")
        add(2, "Other", note = "birthday cake")
        add(3, "Unrelated")

        assertEquals(listOf(1L), found("kedai"))
        assertEquals(listOf(2L), found("birth"))
    }

    @Test fun unicodeSixtyOneFoldsCaseAndAccentsBeyondAscii() {
        // `simple`, FTS4's default, folds ASCII case only: "CAFÉ" would not
        // find "café", and "cafe" would find neither.
        add(1, "CAFÉ ÉCLAIR")

        assertEquals(listOf(1L), found("café"))
        assertEquals(listOf(1L), found("cafe ecl"))
    }

    @Test fun punctuationInTheQueryNeverReachesTheParser() {
        add(1, "Touch 'n Go")

        assertEquals(listOf(1L), found("touch 'n"))
        assertEquals(emptyList<Long>(), found("\"unbalanced -NOT OR"))
    }

    @Test fun theIndexFollowsEveryWriteToTheTable() {
        val id = add(1, "Cash", note = "durian")
        val stamp = 1L

        assertEquals(listOf(1L), found("durian"))
        db.txnDao().setManualNote(id, "rambutan")
        assertEquals(emptyList<Long>(), found("durian"))
        assertEquals(listOf(1L), found("ramb"))

        db.txnDao().deleteManualEntry(id, createdAt = stamp)
        assertEquals(emptyList<Long>(), found("ramb"))
    }

    @Test fun noWordsIsEveryRowTheFiltersLeave() {
        add(1, "A")
        add(2, "B", state = TxnState.REJECTED)

        // Rejected is not a transaction, here as in the feed.
        assertEquals(listOf(1L), found("  "))
    }

    @Test fun eachFilterNarrowsAndBoundsAreInclusive() {
        val food = db.categoryId("Food & Drinks")
        add(1, "Grab", date = SEP_6)
        add(2, "Grab", date = SEP_7, categoryId = food)
        add(3, "Grab", date = SEP_8, pkg = null)
        add(4, "Grab", date = SEP_8, pkg = OTHER_APP)

        assertEquals(listOf(4L, 3L, 2L), found("grab", SearchFilter(from = SEP_7, to = SEP_8)))
        assertEquals(listOf(2L), found("grab", SearchFilter(categoryId = food)))
        assertEquals(listOf(4L), found("grab", SearchFilter(sourcePackage = OTHER_APP)))
        assertEquals(listOf(3L, 2L), found("grab", SearchFilter(minSen = 2, maxSen = 3)))
        assertEquals(
            listOf(2L),
            found("grab", SearchFilter(from = SEP_7, to = SEP_7, categoryId = food, sourcePackage = GRAB_APP, minSen = 2, maxSen = 2)),
        )
    }

    @Test fun anAmountBoundIsInRinggitAndLeavesOtherCurrenciesOut() {
        add(500, "Grab")
        db.txnDao().insert(
            sampleTxn(amountSen = 600, occurredAt = 600, localDate = SEP_7, categoryId = cat, currency = "SGD")
                .copy(merchantDisplay = "Grab", merchantRaw = "GRAB", merchantKey = "GRAB"),
        )

        assertEquals(listOf(600L, 500L), found("grab"))
        // SGD 6.00 is not "at least RM1".
        assertEquals(listOf(500L), found("grab", SearchFilter(minSen = 100)))
    }

    @Test fun theLimitCutsTheRowsAndNotTheTotals() {
        add(100, "Grab")
        add(200, "Grab")
        add(300, "Grab")

        assertEquals(listOf(300L, 200L), found("grab", limit = 2))
        assertEquals(listOf(SearchTotal("MYR", 3, 3, 3, 600)), db.txnDao().searchTotals("grab", SearchFilter()))
    }

    @Test fun theTotalCountsWhatSectionEightCountsAndARefundSubtracts() {
        add(1_000, "Grab")
        add(250, "Grab", direction = Direction.REFUND)
        add(40_000, "Grab", state = TxnState.PENDING)
        add(70_000, "Grab", isExcluded = true)
        add(900_000, "Grab", state = TxnState.REJECTED)
        add(5, "Other")
        db.txnDao().insert(
            sampleTxn(amountSen = 777, occurredAt = 777, localDate = SEP_7, categoryId = cat, currency = "SGD")
                .copy(merchantDisplay = "Grab", merchantRaw = "GRAB", merchantKey = "GRAB"),
        )

        assertEquals(
            setOf(
                // Four drawn, two of them counted, one of those a payment
                // and one a refund: 1000 - 250.
                SearchTotal("MYR", rowCount = 4, countedCount = 2, paymentCount = 1, netSen = 750),
                SearchTotal("SGD", rowCount = 1, countedCount = 1, paymentCount = 1, netSen = 777),
            ),
            db.txnDao().searchTotals("grab", SearchFilter()).toSet(),
        )
    }

    private companion object {
        const val GRAB_APP = "com.grabtaxi.passenger"
        const val OTHER_APP = "my.com.tngdigital.ewallet"
        val SEP_6 = LocalDate(20_260_906)
        val SEP_7 = LocalDate(20_260_907)
        val SEP_8 = LocalDate(20_260_908)
    }
}
