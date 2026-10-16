package my.pinged.ledger.unread

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.dao.UnreadCapture
import my.pinged.data.entity.ParseStatus
import my.pinged.ledger.corruptTheDatabase
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.transfer.bulkCapture
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 5.6's unread list: what a card draws, that its two controls report
 * their own capture, and that the holder's "never a transaction" reaches the
 * database and the list redraws without what it hid.
 */
@RunWith(AndroidJUnit4::class)
class UnreadScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @After fun leaveNothingBehind() = app.discardTheDatabase()

    private val fd = UnreadCapture(
        id = 7L,
        sourcePackage = "com.maybank2u.life",
        // 28 September 2026, 11.31 in Kuala Lumpur.
        postedAt = 1790566260000L,
        title = "Maybank",
        text = "Your FD has matured.",
        bigText = "Your FD of RM5,000.00 has matured. View details in MAE.",
        label = "Maybank MAE",
    )

    private fun draw(state: UnreadState, onNever: (UnreadCapture) -> Unit = {}, onCopy: (UnreadCapture) -> Unit = {}) =
        compose.setContent {
            UnreadContent(state, onBack = {}, onNeverATransaction = onNever, onCopy = onCopy, onEnd = {}, zone = KL)
        }

    @Test fun aCardShowsItsAppItsTimeAndWhatPingedSawWordForWord() {
        draw(UnreadState(loaded = true, items = listOf(fd)))
        compose.onNodeWithText("MAYBANK MAE").assertIsDisplayed()
        compose.onNodeWithText("28 SEP, 11.31 AM").assertIsDisplayed()
        compose.onNodeWithText("Maybank").assertIsDisplayed()
        // The big text, which is what a template reads, and not the shorter text.
        compose.onNodeWithText("Your FD of RM5,000.00 has matured. View details in MAE.").assertIsDisplayed()
        compose.onNodeWithText("Your FD has matured.").assertDoesNotExist()
        compose.onNodeWithText(NOTHING_UNREAD).assertDoesNotExist()
    }

    @Test fun anAppNoLongerListedIsNamedByItsPackage() {
        draw(UnreadState(loaded = true, items = listOf(fd.copy(label = null))))
        compose.onNodeWithText("COM.MAYBANK2U.LIFE").assertIsDisplayed()
    }

    @Test fun eachControlReportsItsOwnCard() {
        val never = mutableListOf<Long>()
        val copied = mutableListOf<Long>()
        draw(UnreadState(loaded = true, items = listOf(fd)), onNever = { never += it.id }, onCopy = { copied += it.id })
        compose.onNodeWithText(NEVER_A_TRANSACTION).performScrollTo().performClick()
        compose.onNodeWithContentDescription(COPY_AS_FIXTURE).performScrollTo().performClick()
        assertEquals(listOf(7L), never)
        assertEquals(listOf(7L), copied)
    }

    @Test fun noTeachButtonIsDrawnUntilTheTeachScreenExists() {
        draw(UnreadState(loaded = true, items = listOf(fd)))
        compose.onNodeWithText("Teach Pinged this").assertDoesNotExist()
    }

    @Test fun anEmptyListSaysSoOnlyOnceItHasBeenRead() {
        draw(UnreadState())
        compose.onNodeWithText(NOTHING_UNREAD).assertDoesNotExist()
    }

    @Test fun anEmptyReadSaysNothingIsUnread() {
        draw(UnreadState(loaded = true))
        compose.onNodeWithText(NOTHING_UNREAD).assertIsDisplayed()
    }

    @Test fun anUnreadableDatabaseIsNotAnEmptyList() {
        draw(UnreadState(loaded = true, storageUnavailable = true))
        compose.onNodeWithText(CANNOT_READ_YOUR_DATA).assertIsDisplayed()
        compose.onNodeWithText(NOTHING_UNREAD).assertDoesNotExist()
    }

    // ---- the holder, against the real database -------------------------------

    /** [count] unmatched captures of one shape, the merchant number being digits. */
    private fun unmatched(count: Int, from: Int = 1) {
        val captures = Databases.rawCaptureDao(app)
        for (i in from until from + count) captures.insert(bulkCapture(i).copy(parseStatus = ParseStatus.UNMATCHED))
    }

    @Test fun neverATransactionHidesTheShapeAndTheListRedrawsWithoutIt() = runBlocking {
        app.discardTheDatabase()
        unmatched(2)
        val other = Databases.rawCaptureDao(app).insert(
            bulkCapture(3).copy(parseStatus = ParseStatus.UNMATCHED, text = "Your statement is ready"),
        )
        val model = UnreadViewModel(app)
        model.refresh().join()
        assertEquals(3, model.state.value.items.size)

        model.neverATransaction(model.state.value.items.first { it.id != other }).join()

        assertEquals(listOf(other), model.state.value.items.map { it.id })
        assertEquals(1, Databases.userRejectRuleDao(app).countAll())
    }

    @Test fun theHolderReadsAPageAndThenTheNext() = runBlocking {
        app.discardTheDatabase()
        unmatched(60)
        val model = UnreadViewModel(app)

        model.refresh().join()
        assertEquals(50, model.state.value.items.size)
        assertEquals(true, model.state.value.more)

        model.loadMore().join()
        val ids = model.state.value.items.map { it.id }
        assertEquals(60, ids.size)
        assertEquals("newest first, nothing twice", ids.sortedDescending(), ids)
        assertEquals(false, model.state.value.more)
    }

    /** The screen asks for the next page itself, once its last card composes. */
    @Test fun scrollingToTheEndLoadsTheNextPage() {
        app.discardTheDatabase()
        unmatched(55)
        val model = UnreadViewModel(app)
        compose.setContent { UnreadScreen(model, onBack = {}) }
        compose.waitUntil(5_000) { model.state.value.items.size == 50 }
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(FOOTER))
        compose.waitUntil(5_000) { model.state.value.items.size == 55 }
    }

    @Test fun anUnopenableDatabaseIsStorageUnavailableNotEmpty() = runBlocking {
        app.corruptTheDatabase()
        val model = UnreadViewModel(app)
        model.refresh().join()
        assertEquals(true, model.state.value.storageUnavailable)
    }

    private companion object {
        val KL: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    }
}
