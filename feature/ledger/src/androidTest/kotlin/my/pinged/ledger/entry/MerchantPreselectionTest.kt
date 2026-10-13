package my.pinged.ledger.entry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RuleOrigin
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.4's pre-selection (#102) against the real encrypted database: the
 * category a typed merchant is already filed under -- the learned rule for its
 * identity, else the shipped dictionary's -- and that a pre-selection left
 * alone saves rule-filed, so a later teaching moves it, while one overridden
 * by a tap stays put.
 */
@RunWith(AndroidJUnit4::class)
class MerchantPreselectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        scope.cancel()
        context.discardTheDatabase()
    }

    @Test fun theDictionaryPreselectsWhenNoRuleIsLearned() = runBlocking {
        context.freshLedger()
        assertEquals(
            "The dictionary's 99 SPEEDMART did not pre-select Groceries",
            categoryId("Groceries"),
            entries().preselection("99 Speedmart").await(),
        )
    }

    @Test fun aLearnedRuleBeatsTheDictionary() = runBlocking {
        context.freshLedger()
        teach("99 SPEEDMART", "Food & Drinks")
        assertEquals(
            "The dictionary's Groceries was pre-selected over the user's own rule",
            categoryId("Food & Drinks"),
            entries().preselection("99 Speedmart").await(),
        )
    }

    @Test fun anUnknownOrBlankMerchantPreselectsNothing() = runBlocking {
        context.freshLedger()
        val entries = entries()
        assertNull("A merchant neither rule nor dictionary knows pre-selected a category", entries.preselection("Warung Pak Ali").await())
        assertNull("No merchant pre-selected a category", entries.preselection("").await())
    }

    @Test fun aRuleOnTheMergedIntoKeyPreselectsForTheOtherKey() = runBlocking {
        context.freshLedger()
        teach("WARUNG PAK ALI", "Food & Drinks")
        Databases.merchantIdentityDao(context).merge("PAK ALI CORNER", "WARUNG PAK ALI", "Warung Pak Ali")
        assertEquals(
            "A key merged into a taught merchant did not pre-select that merchant's category",
            categoryId("Food & Drinks"),
            entries().preselection("Pak Ali Corner").await(),
        )
    }

    @Test fun aPreselectionLeftAloneSavesRuleFiledAndTeachingMovesIt() = runBlocking {
        val dao = context.freshLedger()
        val entries = entries()
        val groceries = categoryId("Groceries")
        val draft = ManualDraft(amountSen = 1_250L, merchant = "99 Speedmart")
            .preselected("99 Speedmart", entries.preselection("99 Speedmart").await())
        assertEquals(CategoryChoice(groceries, ChosenBy.PRESELECTED), draft.category)

        val id = entries.save(draft).await()!!
        assertEquals(groceries, dao.byId(id)!!.categoryId)
        assertFalse("A pre-selection left alone saved as a one-off", dao.byId(id)!!.userEdited)

        teachFromACapture("99 SPEEDMART", to = categoryId("Transport"))

        assertEquals("Teaching the merchant did not move a rule-filed entry", categoryId("Transport"), dao.byId(id)!!.categoryId)
    }

    @Test fun anOverriddenPreselectionSavesAsAOneOffAndStaysPut() = runBlocking {
        val dao = context.freshLedger()
        val food = categoryId("Food & Drinks")
        val draft = ManualDraft(amountSen = 1_250L, merchant = "99 Speedmart")
            .preselected("99 Speedmart", entries().preselection("99 Speedmart").await())
            .tapped(food)

        val id = entries().save(draft).await()!!
        assertEquals(food, dao.byId(id)!!.categoryId)

        teachFromACapture("99 SPEEDMART", to = categoryId("Transport"))

        assertEquals("Teaching the merchant moved an overridden pre-selection", food, dao.byId(id)!!.categoryId)
    }

    /** A learned rule for [key], as the chooser's "Always call this" stores one. */
    private fun teach(key: String, category: String) {
        Databases.merchantRuleDao(context).insert(
            MerchantRule(
                matchType = MatchType.EXACT,
                pattern = key,
                merchantDisplay = key,
                categoryId = categoryId(category),
                origin = RuleOrigin.LEARNED,
                priority = MerchantRuleDao.LEARNED_PRIORITY,
            ),
        )
    }

    /** Teaches [key] [to] from a captured payment of it, as the chooser's "Always call this" does. */
    private fun teachFromACapture(key: String, to: Long) {
        val captured = Databases.txnDao(context).insert(
            ledgerTxn(amountSen = 900L, occurredAt = NOON - 3_600_000L, categoryId = categoryId("Groceries"))
                .copy(merchantRaw = key, merchantDisplay = key, merchantKey = key),
        )
        Databases.merchantRuleDao(context).teachAndFix(captured, to, NOON)
    }

    private fun categoryId(name: String): Long = Databases.categoryDao(context).all().first { it.name == name }.id

    private fun entries() = ManualEntries(context = { context }, scope = scope, clock = { NOON })

    private companion object {
        val NOON: Long = java.time.LocalDate.of(2026, 8, 15).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
