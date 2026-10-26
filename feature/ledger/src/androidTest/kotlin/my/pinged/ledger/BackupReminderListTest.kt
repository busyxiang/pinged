package my.pinged.ledger

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildAt
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import my.pinged.ledger.home.BACKUP_REMINDER
import my.pinged.ledger.home.BackupReminder
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the feed puts Spending's backup reminder (#252): the list's first
 * item, so it scrolls away with it. Here and not under Robolectric with the
 * rest of `home.BackupReminderTest`, because the feed is paged and that
 * test's KDoc says why a page does not land there. Opens no database.
 */
@RunWith(AndroidJUnit4::class)
class BackupReminderListTest {
    @get:Rule val compose = createComposeRule()

    @Test fun itIsTheListsFirstItem() {
        feed(BackupReminder(damaged = false, onAction = {}, onDismiss = {}))

        compose.onNode(hasText(BACKUP_REMINDER) and hasAnyAncestor(hasScrollAction())).assertExists()
        compose.onNode(hasScrollAction()).onChildAt(0)
            .assert(hasText(BACKUP_REMINDER) or hasAnyDescendant(hasText(BACKUP_REMINDER)))
    }

    @Test fun noReminderDrawsNothing() {
        feed(null)

        assertEquals(
            "A reminder was drawn with none handed in",
            0,
            compose.onAllNodesWithText(BACKUP_REMINDER).fetchSemanticsNodes().size,
        )
    }

    private fun feed(reminder: BackupReminder?) {
        val txn = ledgerTxn(amountSen = 1_250L, id = 7L)
        val items = listOf(LedgerItem.DayHeader(txn.localDate, firstRowId = 7L), LedgerItem.Row(txn))
        val flow = flowOf(
            PagingData.from(
                items,
                LoadStates(
                    refresh = LoadState.NotLoading(true),
                    prepend = LoadState.NotLoading(true),
                    append = LoadState.NotLoading(true),
                ),
            ),
        )
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = flow.collectAsLazyPagingItems(),
                    read = LedgerRead(),
                    storageUnavailable = false,
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                    onOpenSettings = {},
                    backupReminder = reminder,
                )
            }
        }
        // Until the page lands the screen draws "reading", with no list and
        // so no reminder either way.
        compose.boundedWaitUntil(TIMEOUT) {
            compose.onAllNodesWithText(MERCHANT).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
