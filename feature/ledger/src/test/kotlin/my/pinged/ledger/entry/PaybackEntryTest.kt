package my.pinged.ledger.entry

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import my.pinged.data.entity.Category
import my.pinged.ledger.bill.BillOffer
import my.pinged.ledger.bill.NO_BILL_PICKED
import my.pinged.ledger.bill.PAYS_BACK
import my.pinged.ledger.bill.WHICH_BILL
import my.pinged.ledger.bill.billChoice
import my.pinged.ledger.bill.billRowTag
import my.pinged.ledger.review.COUNTS_AGAINST
import my.pinged.parse.Direction
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual entry's `PAYS BACK` (spec 9.4, `design/CashPayback.dc.html`,
 * `CashPaybackEdit`), with no database: under Received only, a pick that
 * hides the chips and survives a switch to Paid hidden, and the date check in
 * both directions, which turns the chip Stamp and Save off.
 */
@RunWith(AndroidJUnit4::class)
class PaybackEntryTest {
    @get:Rule val compose = createComposeRule()

    private val picks = mutableListOf<Long?>()
    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 1)

    private fun draw(state: ManualEntryState) {
        compose.setContent {
            PingedTheme {
                ManualEntryScreenContent(
                    state = state,
                    onAmount = {}, onMerchant = {}, onNote = {}, onSave = {}, onBack = {}, onSaved = {},
                    onPickBill = { picks += it },
                    bills = { _, _ -> BillOffer.Recent(listOf(warung), emptyList()) },
                )
            }
        }
    }

    private fun received(bill: Long? = null, day: LocalDate? = null, direction: Direction = Direction.REFUND) = ManualEntryState(
        draft = ManualDraft(amountSen = 2_000L, merchant = "Siti Hajar", direction = direction, bill = bill, day = day),
        categories = listOf(food),
        today = AUG_17,
        bill = warung.takeIf { bill != null },
    )

    @Test fun receivedOffersNoBillAboveTheCategoryChips() {
        draw(received())

        compose.onNodeWithText(PAYS_BACK).performScrollTo()
        compose.onNodeWithText(NO_BILL_PICKED).assertExists()
        compose.onNodeWithText(COUNTS_AGAINST).assertExists()
        compose.onNodeWithText("Food & Drinks").assertExists()
    }

    @Test fun aPickedBillHidesTheChipsAndSaysWhereSaveCountsTheMoney() {
        draw(received(bill = WARUNG))

        compose.onNodeWithTag(BILL_LINE).performScrollTo()
        compose.onNodeWithText("Counts against Food & Drinks on 15 Aug. Your share of Warung Pak Din becomes RM20.00.").assertExists()
        compose.onAllNodesWithText(COUNTS_AGAINST).assertCountEquals(0)
        compose.onAllNodesWithText("Food & Drinks").assertCountEquals(0)
    }

    @Test fun paidKeepsThePickButHidesIt() {
        val paid = received(bill = WARUNG, direction = Direction.EXPENSE)
        draw(paid)

        compose.onAllNodesWithText(PAYS_BACK).assertCountEquals(0)
        compose.onAllNodesWithTag(BILL_LINE).assertCountEquals(0)
        compose.onNodeWithText("Food & Drinks").performScrollTo()
        assertEquals("the pick was dropped", WARUNG, paid.draft.bill)
        assertNull("a payment would save with a bill", paid.draft.paysBack)
    }

    @Test fun paysBackOpensTheChooserAndReportsThePick() {
        draw(received())

        compose.onNodeWithTag(PAYS_BACK_LINE).performScrollTo().performClick()
        compose.onNodeWithText(WHICH_BILL).assertExists()
        compose.onNodeWithTag(billRowTag(WARUNG)).performClick()

        assertEquals(listOf<Long?>(WARUNG), picks)
    }

    @Test fun aPaybackMovedBeforeItsBillIsStampedAndCannotSave() {
        val before = received(bill = WARUNG, day = AUG_17.minusDays(3))
        assertEquals("THE BILL, 15 AUG, IS AFTER THIS DATE", before.dateConflict)
        assertFalse(before.canSave)

        draw(before)
        compose.onNodeWithTag(DATE_CONFLICT).performScrollTo()
        compose.onNodeWithText("THE BILL, 15 AUG, IS AFTER THIS DATE").assertExists()
        compose.onNode(hasText(SAVE) and hasClickAction()).performScrollTo().assertIsNotEnabled()
    }

    @Test fun aPaybackOnItsBillsDayOrAfterSaves() {
        val same = received(bill = WARUNG, day = AUG_15)
        assertNull(same.dateConflict)
        assertTrue(same.canSave)
    }

    @Test fun underPaidTheHiddenBillsDateIsNoConflict() {
        assertNull(received(bill = WARUNG, day = AUG_15.minusDays(1), direction = Direction.EXPENSE).dateConflict)
    }

    @Test fun aTypedBillMovedAfterAPaybackIsStampedAndCannotSave() {
        val stored = ManualDraft(amountSen = 6_000L, day = AUG_15)
        fun bill(day: LocalDate) = ManualEntryState(
            draft = stored.copy(day = day),
            editing = 3L,
            loaded = stored,
            today = AUG_17,
            earliestPayback = AUG_16,
        )

        assertEquals("A PAYBACK, 16 AUG, IS BEFORE THIS DATE", bill(AUG_17).dateConflict)
        assertFalse(bill(AUG_17).canSave)
        assertNull(bill(AUG_16).dateConflict)
        assertTrue(bill(AUG_16).canSave)
    }

    @Test fun aTypedPaybackAlreadyCountedAgainstItsBillIsNotCountedTwice() {
        val stored = ManualDraft(amountSen = 2_000L, direction = Direction.REFUND, bill = WARUNG)
        val state = ManualEntryState(draft = stored.copy(amountSen = 2_500L), editing = 3L, loaded = stored, bill = warung)

        assertEquals(2_000L, state.countedSen)
        assertEquals("a pick of another bill counts nothing yet", 0L, state.copy(draft = state.draft.copy(bill = 9L)).countedSen)
    }

    private companion object {
        const val WARUNG = 5L
        val AUG_15: LocalDate = LocalDate.of(2026, 8, 15)
        val AUG_16: LocalDate = LocalDate.of(2026, 8, 16)
        val AUG_17: LocalDate = LocalDate.of(2026, 8, 17)
        val warung = billChoice(WARUNG, "Warung Pak Din", 6_000L, AUG_15, paidBackSen = 2_000L)
    }
}
