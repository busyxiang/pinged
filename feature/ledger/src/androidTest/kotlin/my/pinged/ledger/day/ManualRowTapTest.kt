package my.pinged.ledger.day

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import my.pinged.data.Databases
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.home.CATEGORY_CHIP
import my.pinged.ledger.home.ENTERED_BY_HAND
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Separator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A manual entry's row body opens its edit screen (#105), and the controls
 * inside the row keep their own taps: the category chip and the small print
 * open the chooser, as on any row, and never the edit screen as well.
 * Drawn by the real `LedgerRow` on the `Day` screen, which the Ledger shares.
 */
@RunWith(AndroidJUnit4::class)
class ManualRowTapTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val opened = mutableListOf<Long>()

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test fun theChipOpensTheChooserAndNotTheEditScreen() {
        val dao = context.freshLedger()
        dao.insert(manual(UNCATEGORIZED_MERCHANT, Databases.categoryDao(context).requireUncategorizedId()))
        screen()

        waitFor(CATEGORY_CHIP)
        compose.onNodeWithText(CATEGORY_CHIP).performScrollTo().performClick()

        waitFor(PICKER_HEADING)
        assertEquals("The chip's tap also opened the edit screen", emptyList<Long>(), opened)
    }

    @Test fun theSmallPrintOpensTheChooserAndNotTheEditScreen() {
        val dao = context.freshLedger()
        dao.insert(manual(FILED_MERCHANT, Databases.categoryDao(context).all().first { it.name == FOOD }.id))
        screen()

        val line = FOOD + Separator + ENTERED_BY_HAND
        waitFor(line)
        compose.onNodeWithText(line).performScrollTo().performClick()

        waitFor(PICKER_HEADING)
        assertEquals("The small print's tap also opened the edit screen", emptyList<Long>(), opened)
    }

    @Test fun theBodyOpensTheEditScreenOnItsRow() {
        val dao = context.freshLedger()
        val id = dao.insert(manual(FILED_MERCHANT, Databases.categoryDao(context).all().first { it.name == FOOD }.id))
        screen()

        waitFor(FILED_MERCHANT)
        compose.onNodeWithText(FILED_MERCHANT).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("A tap on the merchant did not open the entry's edit screen", listOf(id), opened)
    }

    private fun manual(merchant: String, categoryId: Long) =
        ledgerTxn(amountSen = 1_250L, occurredAt = NOON, categoryId = categoryId, rawCaptureId = null)
            .copy(sourcePackage = null, sourceLabel = null, merchantRaw = merchant, merchantDisplay = merchant, merchantKey = merchant.uppercase())

    private fun screen() {
        val viewModel = DayViewModel(context.applicationContext as Application, DAY)
        compose.setContent { PingedTheme { DayScreen(viewModel, onBack = {}, onOpenEntry = { opened += it.id }) } }
    }

    private fun waitFor(text: String) {
        val appeared = runCatching { compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() } }.isSuccess
        assertTrue("Waited ${TIMEOUT}ms for '$text'", appeared)
    }

    private companion object {
        val DAY: LocalDate = LocalDate.now().minusDays(3)
        val NOON: Long = DAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        const val FOOD = "Food & Drinks"
        const val FILED_MERCHANT = "Kopi Pagi"
        const val UNCATEGORIZED_MERCHANT = "Pasar Malam"
    }
}
