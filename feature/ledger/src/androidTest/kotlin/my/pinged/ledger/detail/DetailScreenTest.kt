package my.pinged.ledger.detail

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import my.pinged.data.Databases
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.day.DayScreen
import my.pinged.ledger.day.DayViewModel
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.entry.EditTarget
import my.pinged.ledger.freshLedger
import my.pinged.ledger.home.EDIT_MERCHANT
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.home.REMOVE_CONFIRM
import my.pinged.ledger.home.SAVE_NAME
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ledger.home.REMOVE_HEADING
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.ExclusionReason
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The transaction detail screen (#153), against a real database through its
 * real holder: what it reads, what each action writes, and which actions a
 * row is offered. The row tap that opens it is drawn by the `Day` screen's
 * real `LedgerRow`, which the Ledger shares.
 */
@RunWith(AndroidJUnit4::class)
class DetailScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test fun aCapturedRowsTapOpensItsDetailAndAManualEntrysDoesNot() {
        val dao = context.freshLedger()
        val captured = dao.insert(row(merchant = MERCHANT))
        dao.insert(row(merchant = HAND_TYPED).copy(rawCaptureId = null, sourcePackage = null, sourceLabel = null))
        var opened: EditTarget? = null
        var detailCalls = 0
        val viewModel = DayViewModel(context.applicationContext as Application, DAY)
        compose.setContent {
            PingedTheme {
                DayScreen(viewModel, onBack = {}, onOpenEntry = {}, onOpenDetail = { opened = it; detailCalls++ })
            }
        }

        waitFor(MERCHANT)
        compose.onNodeWithText(HAND_TYPED).performScrollTo().performClick()
        compose.onNodeWithText(MERCHANT).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("Only the captured row opens a detail", 1, detailCalls)
        assertEquals(EditTarget(captured, dao.byId(captured)!!.createdAt), opened)
        assertEquals("The tap wrote nothing", TxnState.COMMITTED, dao.byId(captured)?.state)
    }

    @Test fun itQuotesTheNotificationWordForWordAndNamesTheRuleThatReadIt() {
        val dao = context.freshLedger()
        val capture = capture(
            title = "Touch 'n Go eWallet",
            text = "short text",
            bigText = "Payment of RM12.50 to KOPI PAGI SDN BHD successful.\n  Balance: RM88.00",
        )
        val id = dao.insert(row(merchant = MERCHANT, raw = "TNG*KOPIPAGI").copy(rawCaptureId = capture))
        screen(id)

        waitFor(MERCHANT)
        compose.onNodeWithText("Touch 'n Go eWallet", useUnmergedTree = true).assertExists()
        compose.onNode(
            hasText("Payment of RM12.50 to KOPI PAGI SDN BHD successful.\n  Balance: RM88.00") and hasAnyAncestor(hasTestTag(VERBATIM)),
            useUnmergedTree = true,
        ).performScrollTo()
        compose.onAllNodesWithText("short text", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("RULE TNG-PAYMENT-V1 · PACK 7 · NOT EDITED").performScrollTo()
        compose.onNodeWithText(NAME_ON_THE_BILL).performScrollTo()
        compose.onNodeWithText("TNG*KOPIPAGI").assertExists()
        compose.onNodeWithTag(AMOUNT).assertTextEquals("RM12.50")
    }

    @Test fun aRowWhoseNameIsItsBillDrawsNoNameOnTheBill() {
        val dao = context.freshLedger()
        val id = dao.insert(row(merchant = MERCHANT, raw = MERCHANT).copy(rawCaptureId = capture()))
        screen(id)

        waitFor(MERCHANT)
        compose.onAllNodesWithText(NAME_ON_THE_BILL).assertCountEquals(0)
    }

    @Test fun aOneOffCategoryReadsAsEdited() {
        val dao = context.freshLedger()
        val id = dao.insert(row(merchant = MERCHANT).copy(rawCaptureId = capture(), userEdited = true))
        screen(id)

        waitFor(MERCHANT)
        compose.onNodeWithText("RULE TNG-PAYMENT-V1 · PACK 7 · EDITED").performScrollTo()
    }

    @Test fun deleteAsksThenRejectsTheRowKeepsItsCaptureAndLeaves() {
        val dao = context.freshLedger()
        val id = dao.insert(row(merchant = MERCHANT).copy(rawCaptureId = capture()))
        var left = 0
        screen(id, onBack = { left++ })

        waitFor(DELETE)
        compose.onNodeWithText(DELETE).performScrollTo().performClick()
        waitFor(REMOVE_HEADING)
        assertEquals("The tap wrote before it was confirmed", TxnState.COMMITTED, dao.byId(id)?.state)

        compose.onNodeWithText(REMOVE_CONFIRM).performClick()

        compose.boundedWaitUntil(TIMEOUT) { left == 1 }
        assertEquals(TxnState.REJECTED, dao.byId(id)?.state)
        assertTrue("The capture is the evidence and must survive", dao.byId(id)?.rawCaptureId != null)
    }

    @Test fun aDeleteOfARowThatMovedStaysAndRedrawsWhatIsStored() {
        val dao = context.freshLedger()
        val id = dao.insert(row(merchant = MERCHANT).copy(rawCaptureId = capture()))
        var left = 0
        screen(id, onBack = { left++ })

        waitFor(DELETE)
        compose.onNodeWithText(DELETE).performScrollTo().performClick()
        waitFor(REMOVE_HEADING)
        // Another writer gets there first: the inbox, or a second detail.
        assertEquals(1, dao.removeCaptured(id, 2_000L))
        compose.onNodeWithText(REMOVE_CONFIRM).performClick()

        compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodesWithText(DELETE).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertEquals("The screen left over a write that changed nothing", 0, left)
    }

    @Test fun changeCategoryFilesTheRowAndTheScreenRedraws() {
        val dao = context.freshLedger()
        val id = dao.insert(row(merchant = MERCHANT).copy(rawCaptureId = capture()))
        screen(id)

        waitFor(CHANGE_CATEGORY)
        compose.onNodeWithText(CHANGE_CATEGORY).performScrollTo().performClick()
        waitFor(PICKER_HEADING)
        compose.onNodeWithText("Groceries").performClick()
        compose.onNodeWithText(SAVE_LABEL).performClick()

        val groceries = Databases.categoryDao(context).all().single { it.name == "Groceries" }.id
        compose.boundedWaitUntil(TIMEOUT) { dao.byId(id)?.categoryId == groceries }
        waitFor("Groceries")
    }

    @Test fun thePencilOpensTheMerchantSheet() {
        val dao = context.freshLedger()
        val id = dao.insert(row(merchant = MERCHANT).copy(rawCaptureId = capture()))
        screen(id)

        waitFor(MERCHANT)
        compose.onNodeWithContentDescription(EDIT_MERCHANT).performScrollTo().performClick()
        waitFor(SAVE_NAME)
    }

    @Test fun notSpendingExcludesTheRowAndCountAsSpendingTakesItBack() {
        val dao = context.freshLedger()
        val id = dao.insert(row(merchant = MERCHANT).copy(rawCaptureId = capture()))
        screen(id)

        waitFor(NOT_SPENDING)
        compose.onNodeWithText(NOT_SPENDING).performScrollTo().performClick()
        waitFor(COUNT_AS_SPENDING)
        dao.byId(id)!!.let {
            assertTrue(it.isExcluded)
            assertEquals(ExclusionReason.USER, it.exclusionReason)
            assertFalse("Not spending is no edit of the parse", it.userEdited)
        }
        compose.onAllNodesWithText(CHANGE_CATEGORY).assertCountEquals(0)

        compose.onNodeWithText(COUNT_AS_SPENDING).performScrollTo().performClick()
        waitFor(NOT_SPENDING)
        dao.byId(id)!!.let {
            assertFalse(it.isExcluded)
            assertNull(it.exclusionReason)
        }
    }

    @Test fun aRowAwaitingReviewIsOfferedNeitherDeleteNorNotSpending() {
        val dao = context.freshLedger()
        val id = dao.insert(
            row(merchant = MERCHANT, state = TxnState.PENDING, reason = PendingReason.OVER_THRESHOLD).copy(rawCaptureId = capture()),
        )
        screen(id)

        waitFor(WHAT_PINGED_READ)
        compose.onAllNodesWithText(DELETE).assertCountEquals(0)
        compose.onAllNodesWithText(NOT_SPENDING).assertCountEquals(0)
        compose.onNodeWithText(CHANGE_CATEGORY).performScrollTo()
    }

    @Test fun aTransferThePackExcludedIsNotTheUsersToCountBack() {
        val dao = context.freshLedger()
        val id = dao.insert(
            row(merchant = MERCHANT, excluded = ExclusionReason.TRANSFER).copy(rawCaptureId = capture()),
        )
        screen(id)

        waitFor(DELETE)
        compose.onAllNodesWithText(COUNT_AS_SPENDING).assertCountEquals(0)
        compose.onAllNodesWithText(NOT_SPENDING).assertCountEquals(0)
    }

    @Test fun aNoteIsSavedAndLeavesTheRowUnedited() {
        val dao = context.freshLedger()
        val id = dao.insert(row(merchant = MERCHANT).copy(rawCaptureId = capture()))
        screen(id)

        waitFor(ADD_A_NOTE)
        compose.onAllNodesWithText(SAVE_NOTE).assertCountEquals(0)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NOTE_FIELD))).performScrollTo().performTextInput("teh tarik with Aisyah")
        compose.onNodeWithText(SAVE_NOTE).performScrollTo().performClick()

        compose.boundedWaitUntil(TIMEOUT) { dao.byId(id)?.note == "teh tarik with Aisyah" }
        assertFalse(dao.byId(id)!!.userEdited)
        compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodesWithText(SAVE_NOTE).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun theKeyboardsDoneSavesTheNote() {
        val dao = context.freshLedger()
        val id = dao.insert(row(merchant = MERCHANT).copy(rawCaptureId = capture()))
        screen(id)

        waitFor(ADD_A_NOTE)
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NOTE_FIELD)))
        field.performScrollTo().performTextInput("parking")
        field.performImeAction()

        compose.boundedWaitUntil(TIMEOUT) { dao.byId(id)?.note == "parking" }
    }

    @Test fun aRowAnotherLedgerGaveTheSameIdReadsAsGone() {
        val dao = context.freshLedger()
        val id = dao.insert(row(merchant = MERCHANT).copy(rawCaptureId = capture()))
        val viewModel = DetailViewModel(context.applicationContext as Application, id, dao.byId(id)!!.createdAt + 1)
        compose.setContent { PingedTheme { DetailScreen(viewModel, onBack = {}) } }

        waitFor(GONE)
        compose.onAllNodesWithText(MERCHANT).assertCountEquals(0)
    }

    private fun screen(id: Long, onBack: () -> Unit = {}) {
        val createdAt = Databases.txnDao(context).byId(id)!!.createdAt
        val viewModel = DetailViewModel(context.applicationContext as Application, id, createdAt)
        compose.setContent { PingedTheme { DetailScreen(viewModel, onBack = onBack) } }
    }

    private fun row(
        merchant: String,
        raw: String = merchant.uppercase(),
        state: TxnState = TxnState.COMMITTED,
        reason: PendingReason? = null,
        excluded: ExclusionReason? = null,
    ): Txn =
        ledgerTxn(
            amountSen = 1_250L,
            occurredAt = NOON,
            categoryId = Databases.categoryDao(context).requireUncategorizedId(),
            state = state,
            pendingReason = reason,
            isExcluded = excluded != null,
            exclusionReason = excluded,
        ).copy(merchantRaw = raw, merchantDisplay = merchant, merchantKey = raw)

    private fun capture(
        title: String? = "Touch 'n Go eWallet",
        text: String? = "Payment of RM12.50 to KOPI PAGI successful",
        bigText: String? = null,
    ): Long = Databases.rawCaptureDao(context).insert(
        RawCapture(
            sourcePackage = TNG,
            postedAt = NOON,
            whenMillis = null,
            capturedAt = NOON,
            sbnKey = "0|$TNG|1|${captures}|0",
            notifId = 1,
            notifTag = null,
            userHandle = 0,
            channelId = "txn",
            flags = 0,
            arrival = Arrival.POSTED,
            title = title,
            text = text,
            bigText = bigText,
            subText = null,
            extrasJson = null,
            contentHash = "detail-${captures++}",
            parseStatus = ParseStatus.MATCHED,
            matchedRuleId = "TNG-PAYMENT-V1",
            packVersion = 7,
        ),
    )

    private var captures = 0

    private fun waitFor(text: String) {
        val appeared = runCatching {
            compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        }.isSuccess
        assertTrue("Waited ${TIMEOUT}ms for '$text'", appeared)
    }

    private companion object {
        val DAY: LocalDate = LocalDate.now().minusDays(3)
        val NOON: Long = DAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        const val MERCHANT = "Kopi Pagi"
        const val HAND_TYPED = "Pasar malam"
        const val TNG = "my.com.tngdigital.ewallet"
    }
}
