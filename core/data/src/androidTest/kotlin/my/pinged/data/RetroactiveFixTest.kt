package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.CategoryCount
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #49: what the chooser's "and fix the N past ones" counts and moves, and
 * that the move is all-or-nothing, against the real encrypted database.
 *
 * Every row below is one merchant's unless it says otherwise. The tapped row is
 * `tapped`; a row's role is in its name, because the whole point of each
 * assertion is which of these the count includes.
 */
@RunWith(AndroidJUnit4::class)
class RetroactiveFixTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private val rules get() = db.merchantRuleDao()
    private val txns get() = db.txnDao()
    private val food get() = db.categoryId("Food & Drinks")
    private val shopping get() = db.categoryId("Shopping")
    private val petrol get() = db.categoryId("Transport")
    private val uncategorized get() = db.categoryDao().requireUncategorizedId()

    private var clock = 1_000L

    private fun pay(
        category: Long = uncategorized,
        key: String? = "SHOP",
        state: TxnState = TxnState.COMMITTED,
        excluded: Boolean = false,
        oneOff: Boolean = false,
    ): Long = txns.insert(
        sampleTxn(occurredAt = clock++, categoryId = category, state = state, isExcluded = excluded)
            .copy(merchantRaw = key, merchantDisplay = key, merchantKey = key, userEdited = oneOff),
    )

    private fun row(id: Long): Txn = requireNotNull(txns.byId(id))

    // ---- the count, per row state -----------------------------------------

    @Test fun nCountsEveryRowThatIsNotAOneOffAndNotAlreadyInTheChosenCategory() {
        val tapped = pay()
        pay(category = uncategorized)
        pay(category = shopping) // filed by the dictionary, the user's rule or an old rule: indistinguishable and all movable
        pay(category = petrol)
        pay(category = food) // already there
        pay(category = food, oneOff = true) // a one-off already there: neither N nor conflict

        val preview = rules.retroPreview(tapped, food)

        assertEquals(3, preview.movable)
        assertEquals(emptyList<CategoryCount>(), preview.conflicts)
    }

    @Test fun aOneOffInAnotherCategoryIsAConflictAndIsNotInN() {
        val tapped = pay()
        pay(category = uncategorized)
        pay(category = shopping, oneOff = true)
        pay(category = shopping, oneOff = true)
        pay(category = petrol, oneOff = true)

        val preview = rules.retroPreview(tapped, food)

        assertEquals(1, preview.movable)
        assertEquals(
            listOf(CategoryCount(shopping, 2), CategoryCount(petrol, 1)).sortedBy { it.categoryId },
            preview.conflicts.sortedBy { it.categoryId },
        )
    }

    @Test fun pendingExcludedAndRejectedRowsAreNeitherCountedNorAConflict() {
        val tapped = pay()
        // Each would be a conflict (a one-off elsewhere) and each would be in N
        // (not a one-off, elsewhere) if its state did not exclude it.
        for (oneOff in listOf(false, true)) {
            pay(category = shopping, state = TxnState.PENDING, oneOff = oneOff)
            pay(category = shopping, excluded = true, oneOff = oneOff)
            pay(category = shopping, state = TxnState.REJECTED, oneOff = oneOff)
        }

        val preview = rules.retroPreview(tapped, food)

        assertEquals(0, preview.movable)
        assertEquals(emptyList<CategoryCount>(), preview.conflicts)
    }

    @Test fun theTappedRowIsNotCounted() {
        val tapped = pay(category = shopping)

        assertEquals(0, rules.retroPreview(tapped, food).movable)
    }

    @Test fun anotherMerchantsRowsAreNotCounted() {
        val tapped = pay()
        pay(key = "OTHER SHOP", category = shopping)
        pay(key = "OTHER SHOP", category = shopping, oneOff = true)
        pay(key = null, category = shopping)

        val preview = rules.retroPreview(tapped, food)

        assertEquals(0, preview.movable)
        assertEquals(emptyList<CategoryCount>(), preview.conflicts)
    }

    @Test fun theCountSpansEveryKeyOfAMergedIdentity() {
        val tapped = pay(key = "MR DIY")
        pay(key = "MR DIY", category = shopping)
        pay(key = "MRDIY KL SENTRAL", category = shopping)
        pay(key = "MRDIY KL SENTRAL", category = shopping, oneOff = true)
        db.merchantIdentityDao().merge(source = "MRDIY KL SENTRAL", target = "MR DIY", targetName = "Mr DIY")

        val preview = rules.retroPreview(tapped, food)

        assertEquals("The merged-away key's row was left out of N", 2, preview.movable)
        assertEquals("The merged-away key's one-off was not a conflict", listOf(CategoryCount(shopping, 1)), preview.conflicts)
    }

    @Test fun tappingTheMergedAwayKeyCountsTheSameIdentity() {
        pay(key = "MR DIY", category = shopping)
        val tapped = pay(key = "MRDIY KL SENTRAL")
        db.merchantIdentityDao().merge(source = "MRDIY KL SENTRAL", target = "MR DIY", targetName = "Mr DIY")

        assertEquals(1, rules.retroPreview(tapped, food).movable)
    }

    // ---- the save ----------------------------------------------------------

    @Test fun aSaveWithNoConflictWritesTheRuleAndMovesEveryMovableRow() {
        val tapped = pay()
        val a = pay(category = uncategorized)
        val b = pay(category = shopping)
        val already = pay(category = food)
        val oneOffHere = pay(category = food, oneOff = true)

        val outcome = rules.teachAndFix(tapped, food, now = 9_000L)

        assertTrue(outcome.taught)
        assertEquals(2, outcome.moved)
        assertEquals(0, outcome.conflicts)
        assertEquals(food, rules.learnedCategoryFor("SHOP"))
        for (id in listOf(tapped, a, b, already, oneOffHere)) assertEquals(food, row(id).categoryId)
        assertFalse("The tapped row became a one-off", row(tapped).userEdited)
        assertTrue("A one-off already in the category lost its flag", row(oneOffHere).userEdited)
    }

    @Test fun aMovedRowStaysUneditedAndNoOtherColumnChanges() {
        val tapped = pay()
        val moved = pay(category = shopping)
        val before = row(moved)

        rules.teachAndFix(tapped, food, now = 9_000L)

        assertEquals(
            "A column other than category_id changed (updated_at included)",
            before.copy(categoryId = food),
            row(moved),
        )
        assertFalse(row(moved).userEdited)
    }

    @Test fun aConflictMovesNothingAndTheRuleStillSaves() {
        val tapped = pay()
        val uncat = pay(category = uncategorized)
        val other = pay(category = petrol)
        val confirmed = pay(category = shopping, oneOff = true)

        val outcome = rules.teachAndFix(tapped, food, now = 9_000L)

        assertTrue(outcome.taught)
        assertEquals(0, outcome.moved)
        assertEquals(1, outcome.conflicts)
        assertEquals("The rule was lost to the conflict", food, rules.learnedCategoryFor("SHOP"))
        assertEquals(food, row(tapped).categoryId)
        assertEquals("A row moved despite a conflict", uncategorized, row(uncat).categoryId)
        assertEquals(petrol, row(other).categoryId)
        assertEquals(shopping, row(confirmed).categoryId)
    }

    /**
     * The sheet counted with no conflict; a payment the user then set by hand
     * (here directly, as a capture landing and being confirmed would) arrives
     * before the tap. The save re-checks inside its own transaction.
     */
    @Test fun aConflictThatLandsBetweenTheCountAndTheSaveMovesNothingAndSavesTheRule() {
        val tapped = pay()
        val movable = pay(category = shopping)
        assertEquals("The fixture's premise: no conflict at count time", emptyList<CategoryCount>(), rules.retroPreview(tapped, food).conflicts)
        val late = pay(category = uncategorized)
        txns.setCategory(late, petrol, 5_000L)

        val outcome = rules.teachAndFix(tapped, food, now = 9_000L)

        assertEquals(0, outcome.moved)
        assertEquals(1, outcome.conflicts)
        assertEquals(food, rules.learnedCategoryFor("SHOP"))
        assertEquals("The save moved a row past a conflict it should have seen", shopping, row(movable).categoryId)
    }

    /**
     * The move's own `user_edited = 0`, apart from the check before it: a
     * caller that moved without checking must still not overwrite a person.
     */
    @Test fun theMoveItselfNeverOverwritesAOneOff() {
        val tapped = pay()
        val confirmed = pay(category = shopping, oneOff = true)
        val movable = pay(category = petrol)

        val moved = rules.moveToCategory("SHOP", tapped, food)

        assertEquals(1, moved)
        assertEquals(shopping, row(confirmed).categoryId)
        assertEquals(food, row(movable).categoryId)
    }

    @Test fun aRowWithNoMerchantKeyTeachesAndMovesNothing() {
        val tapped = pay(key = null)
        val other = pay(key = null, category = shopping)

        val outcome = rules.teachAndFix(tapped, food, now = 9_000L)

        assertFalse(outcome.taught)
        assertEquals(0, outcome.moved)
        assertEquals(0, rules.countAll())
        assertEquals("Rows with no key are not one merchant", shopping, row(other).categoryId)
    }

    @Test fun theSaveSpansBothKeysOfAMergedIdentity() {
        val tapped = pay(key = "MR DIY")
        val viaSource = pay(key = "MRDIY KL SENTRAL", category = shopping)
        db.merchantIdentityDao().merge(source = "MRDIY KL SENTRAL", target = "MR DIY", targetName = "Mr DIY")

        val outcome = rules.teachAndFix(tapped, food, now = 9_000L)

        assertEquals(1, outcome.moved)
        assertEquals(food, row(viaSource).categoryId)
    }
}
