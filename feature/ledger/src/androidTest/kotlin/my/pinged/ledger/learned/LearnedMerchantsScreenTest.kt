package my.pinged.ledger.learned

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.dao.LearnedMerchant
import my.pinged.data.dao.RejectRuleRow
import my.pinged.data.dao.TaughtRuleRow
import my.pinged.data.entity.UserTemplateRule
import my.pinged.parse.Direction
import my.pinged.data.entity.ParseStatus
import my.pinged.ledger.refreshed
import my.pinged.ledger.transfer.bulkCapture
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
        compose.onNodeWithContentDescription(DELETE).performClick()
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

        model.read.refreshed()

        val state = model.read.state.value.toState()
        assertEquals(true, state.loaded)
        assertEquals(listOf("MR DIY", "ZUS COFFEE"), state.items.map { it.name })
    }

    @Test fun deleteForgetsTheRuleAndTheListDrawsWithoutIt() = runBlocking {
        taught("ZUS COFFEE", "MR DIY")
        val model = LearnedViewModel(app)
        model.read.refreshed()

        model.delete(model.read.state.value.toState().items.first { it.identity == "MR DIY" }).join()

        assertEquals(listOf("ZUS COFFEE"), model.read.state.value.toState().items.map { it.name })
        assertEquals("Delete changed a transaction", 2, Databases.txnDao(app).countAll())
        assertEquals(null, Databases.merchantRuleDao(app).learnedCategoryFor("MR DIY"))
    }

    @Test fun anUnopenableDatabaseIsStorageUnavailableNotEmpty() = runBlocking {
        app.corruptTheDatabase()
        val model = LearnedViewModel(app)

        model.read.refreshed()

        assertEquals(true, model.read.state.value.toState().storageUnavailable)
    }

    // ---- spec 5.7's IGNORED tab -------------------------------------------------

    private val fdRule = RejectRuleRow(
        id = 3L,
        sourcePackage = "com.maybank2u.life",
        sampleText = "Your FD of RM5,000.00 has matured.",
        createdAt = 1L,
        label = "Maybank MAE",
        hidden = 2,
    )

    @Test fun theTabsCountBothListsAndIgnoredShowsTheRuleWordForWord() {
        compose.setContent {
            LearnedContent(LearnedState(loaded = true, items = listOf(mrDiy), ignored = listOf(fdRule)), onBack = {}, onDelete = {})
        }
        compose.onNodeWithText("MERCHANTS 1").assertIsDisplayed()
        compose.onNodeWithText("Hardware shop").assertIsDisplayed()

        compose.onNodeWithText("IGNORED 1").performScrollTo().performClick()

        compose.onNodeWithText("Your FD of RM5,000.00 has matured.").assertIsDisplayed()
        compose.onNodeWithText("MAYBANK MAE \u00B7 HIDES 2").assertIsDisplayed()
        compose.onNodeWithText("Hardware shop").assertDoesNotExist()
    }

    @Test fun anEmptyIgnoredListSaysHowToMakeOne() {
        compose.setContent { LearnedContent(LearnedState(loaded = true), onBack = {}, onDelete = {}, initialTab = TaughtTab.IGNORED) }
        compose.onNodeWithText(NOTHING_IGNORED).assertIsDisplayed()
        compose.onNodeWithText(NOTHING_TAUGHT).assertDoesNotExist()
    }

    @Test fun deletingAnIgnoredRuleReportsItsOwnRow() {
        val deleted = mutableListOf<Long>()
        compose.setContent {
            LearnedContent(
                LearnedState(loaded = true, ignored = listOf(fdRule)),
                onBack = {},
                onDelete = {},
                onDeleteIgnored = { deleted += it.id },
                initialTab = TaughtTab.IGNORED,
            )
        }
        compose.onNodeWithContentDescription(DELETE).performScrollTo().performClick()
        assertEquals(listOf(3L), deleted)
    }

    /** Spec 5.7: deleting a rule returns its captures to the unread list. */
    @Test fun theHolderDeletesARuleAndItsCapturesAreUnreadAgain() = runBlocking {
        app.discardTheDatabase()
        val captures = Databases.rawCaptureDao(app)
        val first = captures.insert(bulkCapture(1).copy(parseStatus = ParseStatus.UNMATCHED))
        captures.insert(bulkCapture(2).copy(parseStatus = ParseStatus.UNMATCHED))
        val rejects = Databases.userRejectRuleDao(app)
        rejects.neverATransaction(first, now = 2_000L)
        assertEquals(0, rejects.unreadCount())
        val model = LearnedViewModel(app)
        model.read.refreshed()
        assertEquals(listOf(2), model.read.state.value.toState().ignored.map { it.hidden })

        model.deleteIgnored(model.read.state.value.toState().ignored.single()).join()

        assertEquals(emptyList<RejectRuleRow>(), model.read.state.value.toState().ignored)
        assertEquals(2, rejects.unreadCount())
    }

    // ---- spec 5.8's MESSAGES tab ------------------------------------------------

    private val kioskRule = TaughtRuleRow(
        id = 5L,
        sourcePackage = "com.maybank2u.life",
        sampleText = "Kiosk debit RM3.00 from account 1234",
        direction = Direction.EXPENSE,
        hasMerchantGroup = false,
        matchCount = 2,
        label = "Maybank MAE",
    )

    @Test fun messagesShowsTheTaughtMessageWordForWordAndWhatItReads() {
        compose.setContent {
            LearnedContent(LearnedState(loaded = true, items = listOf(mrDiy), messages = listOf(kioskRule)), onBack = {}, onDelete = {})
        }
        compose.onNodeWithText("MESSAGES 1").performScrollTo().performClick()

        compose.onNodeWithText("Kiosk debit RM3.00 from account 1234").assertIsDisplayed()
        compose.onNodeWithText("MAYBANK MAE \u00B7 MONEY OUT \u00B7 NO MERCHANT \u00B7 READ 2").assertIsDisplayed()
        compose.onNodeWithText("Hardware shop").assertDoesNotExist()
    }

    @Test fun anEmptyMessagesListSaysHowToTeachOne() {
        compose.setContent { LearnedContent(LearnedState(loaded = true), onBack = {}, onDelete = {}, initialTab = TaughtTab.MESSAGES) }
        compose.onNodeWithText(NO_MESSAGES).assertIsDisplayed()
    }

    /** Spec 5.8: deleting a taught message leaves the transactions it wrote. */
    @Test fun theHolderDeletesATaughtMessageAndLeavesItsTransactions() = runBlocking {
        app.discardTheDatabase()
        val taught = Databases.userTemplateRuleDao(app)
        taught.teach(
            UserTemplateRule(
                sourcePackage = "com.maybank2u.life",
                pattern = "^Kiosk debit RM(?<amount>[0-9]+)$",
                direction = Direction.EXPENSE,
                hasMerchantGroup = false,
                sampleText = "Kiosk debit RM3",
                createdAt = 1L,
            ),
            unread = emptyList(),
        )
        val txns = Databases.txnDao(app).countAll()
        val model = LearnedViewModel(app)
        model.read.refreshed()
        assertEquals(1, model.read.state.value.toState().messages.size)

        model.deleteMessage(model.read.state.value.toState().messages.single()).join()

        assertEquals(emptyList<TaughtRuleRow>(), model.read.state.value.toState().messages)
        assertEquals(0, taught.countAll())
        assertEquals(txns, Databases.txnDao(app).countAll())
    }
}
