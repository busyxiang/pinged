package my.pinged.ledger.entry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.TxnDao
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Correcting a manual entry (#105, spec 9.4) against the real encrypted
 * database, through [ManualEntries] as the entry screen's edit mode drives
 * it: what opens, what a Save changes writes, how a new merchant re-files,
 * and that a captured row cannot be reached.
 */
@RunWith(AndroidJUnit4::class)
class ManualEditTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        scope.cancel()
        context.discardTheDatabase()
    }

    @Test fun editModeOpensWithEveryFieldAndHowTheCategoryWasChosen() = runBlocking {
        context.freshLedger()
        val groceries = categoryId("Groceries")
        val food = categoryId("Food & Drinks")
        val ruleFiled = entries(NOON).save(speedmart(groceries)).await()!!
        val oneOff = entries(NOON).save(ManualDraft(amountSen = 800L, merchant = "Kopitam").tapped(food)).await()!!
        val backdated = entries(NOON).save(ManualDraft(amountSen = 300L, day = DAY.minusDays(3))).await()!!

        assertEquals(
            "Edit mode did not open the rule-filed entry filled in, its category a pre-selection",
            ManualDraft(amountSen = 1_250L, merchant = "99 Speedmart", note = "lunch", category = CategoryChoice(groceries, ChosenBy.PRESELECTED)),
            entries(NOON).read(ruleFiled).draft,
        )
        assertEquals(
            "Edit mode did not open a one-off's category as the chip the user tapped",
            CategoryChoice(food, ChosenBy.TAPPED),
            entries(NOON).read(oneOff).draft.category,
        )
        assertEquals(
            "Edit mode did not open a backdated entry on its day, nor an uncategorized one with no chip",
            ManualDraft(amountSen = 300L, day = DAY.minusDays(3)),
            entries(NOON).read(backdated).draft,
        )
    }

    @Test fun anAmountDateAndNoteChangeOnlyThemselvesAndUpdatedAt() = runBlocking {
        val dao = context.freshLedger()
        // Filed under Transport though the dictionary says Groceries: a
        // re-filing would show, and so would `user_edited` moving.
        val transport = categoryId("Transport")
        val id = entries(NOON).save(speedmart(transport)).await()!!
        val stored = dao.byId(id)!!
        assertFalse(stored.userEdited)

        val edited = entries(LATER).read(id).draft
            .copy(amountSen = 1_500L, note = "dinner")
            .dated(DAY.minusDays(1), DAY)
        assertEquals(true, entries(LATER).update(target(id), edited).await())

        val movedTo = millis(DAY.minusDays(1).atTime(13, 0))
        assertEquals(
            "The correction wrote more than the amount, the date, the note and updated_at",
            stored.copy(
                amountSen = 1_500L,
                occurredAt = movedTo,
                localDate = LocalDates.of(movedTo),
                note = "dinner",
                updatedAt = LATER,
            ),
            dao.byId(id),
        )
    }

    @Test fun anUnchangedDayKeepsItsTimeAndAOneOffStaysOneOff() = runBlocking {
        val dao = context.freshLedger()
        val food = categoryId("Food & Drinks")
        val id = entries(NOON).save(ManualDraft(amountSen = 800L, merchant = "99 Speedmart").tapped(food)).await()!!
        val stored = dao.byId(id)!!

        val edited = entries(LATER).read(id).draft.copy(amountSen = 900L)
        entries(LATER).update(target(id), edited).await()

        assertEquals(
            "A new amount moved the date, the category or user_edited",
            stored.copy(amountSen = 900L, updatedAt = LATER),
            dao.byId(id),
        )
    }

    @Test fun aNewMerchantRefilesARuleFiledEntry() = runBlocking {
        val dao = context.freshLedger()
        // Typed with a typo the dictionary does not know: Uncategorized, rule-filed.
        val id = entries(NOON).save(ManualDraft(amountSen = 1_250L, merchant = "99 Spedmart")).await()!!
        assertEquals(Databases.categoryDao(context).requireUncategorizedId(), dao.byId(id)!!.categoryId)

        val edited = entries(LATER).read(id).draft.copy(merchant = "99 Speedmart")
        entries(LATER).update(target(id), edited).await()

        val corrected = dao.byId(id)!!
        assertEquals("Correcting the merchant did not file the entry by the dictionary", categoryId("Groceries"), corrected.categoryId)
        assertFalse("Re-filing froze the category as a one-off", corrected.userEdited)
        assertEquals("99 SPEEDMART", corrected.merchantKey)
    }

    @Test fun aNewMerchantRefilesByTheLearnedRuleFirstAndToUncategorizedWithNone() = runBlocking {
        val dao = context.freshLedger()
        val id = entries(NOON).save(speedmart(categoryId("Groceries"))).await()!!
        val transport = categoryId("Transport")
        Databases.txnDao(context).insert(
            ledgerTxn(amountSen = 900L, occurredAt = NOON - HOUR, categoryId = transport)
                .copy(merchantRaw = "GRAB", merchantDisplay = "Grab", merchantKey = "GRAB"),
        ).let { Databases.merchantRuleDao(context).teachAndFix(it, transport, NOON) }

        entries(LATER).update(target(id), entries(LATER).read(id).draft.copy(merchant = "Grab")).await()
        assertEquals("A learned rule did not file the corrected merchant", transport, dao.byId(id)!!.categoryId)

        entries(LATER).update(target(id), entries(LATER).read(id).draft.copy(merchant = "")).await()
        assertEquals(
            "A merchant removed left a rule's category behind",
            Databases.categoryDao(context).requireUncategorizedId(),
            dao.byId(id)!!.categoryId,
        )
    }

    @Test fun aNewMerchantKeepsAOneOffsCategory() = runBlocking {
        val dao = context.freshLedger()
        val food = categoryId("Food & Drinks")
        val id = entries(NOON).save(ManualDraft(amountSen = 1_250L, merchant = "99 Spedmart").tapped(food)).await()!!

        entries(LATER).update(target(id), entries(LATER).read(id).draft.copy(merchant = "99 Speedmart")).await()

        val corrected = dao.byId(id)!!
        assertEquals("Correcting the merchant re-filed a one-off", food, corrected.categoryId)
        assertEquals(true, corrected.userEdited)
        assertEquals("99 SPEEDMART", corrected.merchantKey)
    }

    @Test fun aChipTappedInTheEditIsAOneOff() = runBlocking {
        val dao = context.freshLedger()
        val id = entries(NOON).save(speedmart(categoryId("Groceries"))).await()!!
        val food = categoryId("Food & Drinks")

        entries(LATER).update(target(id), entries(LATER).read(id).draft.tapped(food)).await()

        assertEquals(food, dao.byId(id)!!.categoryId)
        assertEquals("A chip tapped in the edit saved rule-filed", true, dao.byId(id)!!.userEdited)
    }

    @Test fun theUpdateRefusesACapturedRow() = runBlocking {
        val dao: TxnDao = context.freshLedger()
        val captured = dao.insert(ledgerTxn(amountSen = 1_250L, occurredAt = NOON - HOUR))
        val before = dao.byId(captured)!!

        assertEquals("A captured row opened in edit mode", Loaded.Gone, entries(LATER).load(captured).await())
        val written = entries(LATER).update(
            EditTarget(captured, before.createdAt),
            ManualDraft(amountSen = 9_900L, day = DAY.minusDays(2), merchant = "Kopitiam", note = "typed").tapped(categoryId("Transport")),
        ).await()

        assertFalse("The update reported writing a captured row", written)
        assertEquals("The update changed a captured row", before, dao.byId(captured))
    }

    @Test fun aRecheckDatesAnEditOnItsOwnDayAndNeverMatchesItself() = runBlocking {
        val dao = context.freshLedger()
        val id = entries(NOON).save(ManualDraft(amountSen = 1_250L)).await()!!
        // Five minutes after the entry, three hours before the edit.
        dao.insert(ledgerTxn(amountSen = 1_300L, occurredAt = NOON + 5 * MINUTE))
        val edited = entries(LATER + 2 * HOUR).read(id).draft

        assertNull(
            "The entry being corrected was taken for a capture of itself",
            entries(LATER + 2 * HOUR).nearbyCapture(edited, editing = id).await(),
        )
        assertNotNull(
            "A new amount on an unchanged day was not checked around when it was paid",
            entries(LATER + 2 * HOUR).nearbyCapture(edited.copy(amountSen = 1_300L), editing = id).await(),
        )
    }

    /** Groceries by the dictionary, left alone: rule-filed, unless [categoryId] says otherwise and is tapped. */
    private suspend fun speedmart(categoryId: Long): ManualDraft {
        val draft = ManualDraft(amountSen = 1_250L, merchant = "99 Speedmart", note = "lunch")
        val filed = entries(NOON).preselection("99 Speedmart").await()
        return if (filed == categoryId) draft.preselected("99 Speedmart", filed) else rulefiledUnder(draft, categoryId)
    }

    /** [draft] saved rule-filed under [categoryId], which no rule gives: the pre-selection is forced. */
    private fun rulefiledUnder(draft: ManualDraft, categoryId: Long) =
        draft.copy(category = CategoryChoice(categoryId, ChosenBy.PRESELECTED))

    private fun categoryId(name: String): Long = Databases.categoryDao(context).all().first { it.name == name }.id

    /** [id]'s entry as edit mode opens it; the test fails if it is not there. */
    private suspend fun ManualEntries.read(id: Long): Loaded.Entry = load(id).await() as Loaded.Entry

    private fun target(id: Long) = EditTarget(id, Databases.txnDao(context).byId(id)!!.createdAt)

    private fun entries(now: Long) = ManualEntries(context = { context }, scope = scope, clock = { now })

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 8, 15)
        val NOON: Long = millis(DAY.atTime(12, 0))
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        val LATER: Long = NOON + HOUR

        fun millis(at: java.time.LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
