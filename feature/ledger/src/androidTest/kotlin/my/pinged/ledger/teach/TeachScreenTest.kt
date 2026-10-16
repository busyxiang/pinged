package my.pinged.ledger.teach

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.dao.UnreadCapture
import my.pinged.data.entity.ParseStatus
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.transfer.bulkCapture
import my.pinged.parse.Direction
import my.pinged.parse.TaughtTemplate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 5.8's Teach screen: the two taps the artboard draws, the preview they
 * produce, and the holder's save reaching the database.
 */
@RunWith(AndroidJUnit4::class)
class TeachScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @After fun leaveNothingBehind() = app.discardTheDatabase()

    /** The artboard's notification. */
    private val hongLeong = UnreadCapture(
        id = 9L,
        sourcePackage = "my.com.hongleong.connect",
        // 30 September 2026, 9.14 in Kuala Lumpur.
        postedAt = 1790730840000L,
        title = null,
        text = "Transaksi berjaya. RM88.00 telah ditolak dari akaun anda 1234.",
        bigText = null,
        label = "Hong Leong Connect",
    )

    /** The screen over a state the test holds, as the holder would update it. */
    private fun draw(saved: MutableList<Direction> = mutableListOf()) = compose.setContent {
        var state by remember {
            mutableStateOf(
                TeachState(
                    loaded = true,
                    capture = hongLeong,
                    selection = TeachSelection(TaughtTemplate.words(null, hongLeong.text, null)),
                ),
            )
        }
        TeachContent(
            state = state,
            onBack = {},
            onTap = { state = state.copy(selection = state.selection?.tap(it)) },
            onNoMerchant = { state = state.copy(selection = state.selection?.noMerchant()) },
            onDirection = { state = state.copy(direction = it) },
            onSave = { saved += state.direction },
            zone = ZoneId.of("Asia/Kuala_Lumpur"),
        )
    }

    private fun tapWord(word: String) = compose.onNode(hasText(word) and hasClickAction()).performScrollTo().performClick()

    @Test fun theHeaderNamesTheAppAndWhenItPosted() {
        draw()
        // The header, like the Save band, stays put while the rest scrolls.
        compose.onNodeWithText(TEACH_TITLE).assertIsDisplayed()
        compose.onNodeWithText("HONG LEONG CONNECT · 30 SEP, 9.14 AM").assertIsDisplayed()
        compose.onNodeWithText(stepLabel(TeachSelection.Step.AMOUNT)).performScrollTo()
    }

    /** The artboard, tap for tap: the amount, then no merchant, then a refund. */
    @Test fun twoTapsGiveTheArtboardsPreviewAndSaveWhatWasChosen() {
        val saved = mutableListOf<Direction>()
        draw(saved)
        compose.onNodeWithText(SAVE).assertIsNotEnabled()

        tapWord("RM88.00")
        compose.onNodeWithText(stepLabel(TeachSelection.Step.MERCHANT)).performScrollTo()
        compose.onNodeWithTag(TEACH_PREVIEW).performScrollTo().assertTextEquals(
            "Transaksi berjaya. RM amount  telah ditolak dari akaun anda  any number .",
        )
        compose.onNodeWithText(SAVE).assertIsNotEnabled()

        compose.onNodeWithText(NO_MERCHANT).performScrollTo().performClick()
        compose.onNodeWithText(A_REFUND).performScrollTo().performClick()
        compose.onNodeWithText(SAVE).assertIsEnabled().performClick()

        assertEquals(listOf(Direction.REFUND), saved)
    }

    @Test fun aWordWithNoAmountInItIsNotTakenForTheAmount() {
        draw()
        tapWord("berjaya.")
        compose.onNodeWithText(stepLabel(TeachSelection.Step.AMOUNT)).performScrollTo()
        compose.onNodeWithTag(TEACH_PREVIEW).performScrollTo().assertTextEquals(TAP_FIRST)
    }

    @Test fun aCaptureThatLeftTheListSaysThereIsNothingToTeach() {
        compose.setContent { TeachContent(TeachState(loaded = true), {}, {}, {}, {}, {}) }
        compose.onNodeWithText(GONE).assertIsDisplayed()
    }

    // ---- the holder, against the real database -------------------------------

    @Test fun savingWritesTheRuleAndHandsItsUnreadCapturesBackToStageTwo() = runBlocking {
        app.discardTheDatabase()
        val captures = Databases.rawCaptureDao(app)
        // bulkCapture(n)'s text differs only in its digits, so both are one shape.
        val taughtOn = captures.insert(bulkCapture(1).copy(parseStatus = ParseStatus.UNMATCHED))
        val sameShape = captures.insert(bulkCapture(2).copy(parseStatus = ParseStatus.UNMATCHED))
        val otherShape = captures.insert(bulkCapture(3).copy(parseStatus = ParseStatus.UNMATCHED, text = "Your statement is ready"))
        val kicked = mutableListOf<Unit>()
        val model = TeachViewModel(app, taughtOn, runStageTwo = { kicked += Unit })
        withTimeout(5_000) { model.state.first { it.loaded } }
        val words = requireNotNull(model.state.value.selection).words.words
        check("RM1.00" in words) { "bulkCapture's amount moved: $words" }

        model.tap(words.indexOf("RM1.00"))
        model.noMerchant()
        model.save(now = 7_000L).join()

        val rule = Databases.userTemplateRuleDao(app).all().single()
        assertEquals(Direction.EXPENSE, rule.direction)
        assertEquals(false, rule.hasMerchantGroup)
        assertEquals(0, rule.matchCount)
        assertEquals(ParseStatus.NEW, captures.byId(taughtOn).parseStatus)
        assertEquals(ParseStatus.NEW, captures.byId(sameShape).parseStatus)
        assertEquals(ParseStatus.UNMATCHED, captures.byId(otherShape).parseStatus)
        assertEquals(1, kicked.size)
        assertTrue(model.state.value.saved)
    }
}
