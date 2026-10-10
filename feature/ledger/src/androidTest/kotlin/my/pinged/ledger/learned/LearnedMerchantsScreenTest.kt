package my.pinged.ledger.learned

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.dao.LearnedMerchant
import my.pinged.ledger.corruptTheDatabase
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #50: what the learned-merchants screen draws, and that its delete
 * reaches the database through the holder.
 */
@RunWith(AndroidJUnit4::class)
class LearnedMerchantsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @After fun leaveNothingBehind() = app.discardTheDatabase()

    private val mrDiy = LearnedMerchant("MR DIY", "Hardware shop", 4L, "Shopping", payments = 3)

    // ---- the drawing -------------------------------------------------------

    @Test fun aRowShowsItsNameCategoryAndLivePaymentCount() {
        compose.setContent {
            LearnedContent(LearnedState(loaded = true, items = listOf(mrDiy)), onBack = {}, onDelete = {})
        }
        compose.onNodeWithText("Hardware shop").assertIsDisplayed()
        compose.onNodeWithText("Shopping").assertIsDisplayed()
        compose.onNodeWithText("PAYMENTS 3").assertIsDisplayed()
        compose.onNodeWithText(NOTHING_TAUGHT).assertDoesNotExist()
    }

    @Test fun anEmptyListSaysHowToTeachOne() {
        compose.setContent { LearnedContent(LearnedState(loaded = true), onBack = {}, onDelete = {}) }
        compose.onNodeWithText("Nothing taught yet. Pick a category on a payment and leave 'Always call this' on.")
            .assertIsDisplayed()
    }

    @Test fun nothingIsClaimedBeforeTheFirstRead() {
        compose.setContent { LearnedContent(LearnedState(), onBack = {}, onDelete = {}) }
        compose.onNodeWithText(NOTHING_TAUGHT).assertDoesNotExist()
    }

    @Test fun anUnreadableDatabaseIsNotAnEmptyList() {
        compose.setContent {
            LearnedContent(LearnedState(loaded = true, storageUnavailable = true), onBack = {}, onDelete = {})
        }
        compose.onNodeWithText(CANNOT_READ_YOUR_DATA).assertIsDisplayed()
        compose.onNodeWithText(NOTHING_TAUGHT).assertDoesNotExist()
    }

    @Test fun deleteReportsTheIdentityOfItsOwnRow() {
        val deleted = mutableListOf<String>()
        compose.setContent {
            LearnedContent(LearnedState(loaded = true, items = listOf(mrDiy)), onBack = {}, onDelete = { deleted += it.identity })
        }
        compose.onNodeWithText(DELETE).performClick()
        assertEquals(listOf("MR DIY"), deleted)
    }

    // ---- the holder, against the real database -------------------------------

    private fun taught(vararg keys: String) {
        app.discardTheDatabase()
        val db = Databases.shared(app)
        val uncategorized = db.categoryDao().requireUncategorizedId()
        val shopping = db.categoryDao().all().first { it.name == "Shopping" }.id
        keys.forEachIndexed { i, key ->
            val id = db.txnDao().insert(
                ledgerTxn(amountSen = 1_000L, occurredAt = 1_000L + i, categoryId = uncategorized)
                    .copy(id = 0, merchantRaw = key, merchantDisplay = key, merchantKey = key),
            )
            db.merchantRuleDao().teach(id, shopping, 5_000L)
        }
    }

    @Test fun theHolderReadsTheLedgersRulesAZ() = runBlocking {
        taught("ZUS COFFEE", "MR DIY")
        val model = LearnedViewModel(app)

        model.refresh().join()

        val state = model.state.value
        assertEquals(true, state.loaded)
        assertEquals(listOf("MR DIY", "ZUS COFFEE"), state.items.map { it.name })
    }

    @Test fun deleteForgetsTheRuleAndTheListDrawsWithoutIt() = runBlocking {
        taught("ZUS COFFEE", "MR DIY")
        val model = LearnedViewModel(app)
        model.refresh().join()

        model.delete(model.state.value.items.first { it.identity == "MR DIY" }).join()

        assertEquals(listOf("ZUS COFFEE"), model.state.value.items.map { it.name })
        assertEquals("Delete changed a transaction", 2, Databases.txnDao(app).countAll())
        assertEquals(null, Databases.merchantRuleDao(app).learnedCategoryFor("MR DIY"))
    }

    @Test fun anUnopenableDatabaseIsStorageUnavailableNotEmpty() = runBlocking {
        app.corruptTheDatabase()
        val model = LearnedViewModel(app)

        model.refresh().join()

        assertEquals(true, model.state.value.storageUnavailable)
    }
}
