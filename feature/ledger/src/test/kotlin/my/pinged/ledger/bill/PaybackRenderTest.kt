package my.pinged.ledger.bill

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.LocalDate
import java.time.ZoneId
import my.pinged.data.Seed
import my.pinged.data.dao.BillChoice
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.detail.DetailRead
import my.pinged.ledger.detail.DetailScreenContent
import my.pinged.ledger.entry.ManualDraft
import my.pinged.ledger.entry.ManualEntryScreenContent
import my.pinged.ledger.entry.ManualEntryState
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.review.ReviewCard
import my.pinged.ledger.review.ReviewInboxScreenContent
import my.pinged.parse.Direction
import my.pinged.read.LedgerAccess
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The linking screens of #392 drawn against their boards, at the boards'
 * 390x844: the bill chooser and its two states, the inbox's received-money
 * cards, a refund's and a payback's detail, and manual entry's payback and
 * its date check. The README's September scenario, and its typed-by-hand
 * August. Constructed data only: a golden is a picture of its input, and the
 * repository is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class PaybackRenderTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private fun at(day: LocalDate, hour: Int, minute: Int) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val yuenKee = billChoice(5L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 3_200L)
    private val foodpanda = billChoice(6L, "foodpanda", 6_400L, LocalDate.of(2026, 9, 14), paidBackSen = 6_450L)
    private val sriMelayu = billChoice(7L, "Restoran Sri Melayu Masakan Kampung Baru", 6_400L, LocalDate.of(2026, 9, 8))
    private val tealive = billChoice(8L, "Tealive", 890L, SEP_20)
    private val mydin = billChoice(9L, "Mydin USJ", 4_160L, LocalDate.of(2026, 9, 19), category = "Groceries")
    private val mydinJuly = billChoice(10L, "Mydin USJ", 6_400L, LocalDate.of(2026, 7, 2), category = "Groceries")
    private val lotus = billChoice(11L, "Lotus’s Kepong", 6_400L, LocalDate.of(2026, 5, 19), category = "Groceries")
    private val arjun = BillFor("Arjun Nair", 3_200L, "MYR", SEP_20)

    private fun chooser(query: String? = null, found: List<BillChoice> = emptyList()) {
        draw {
            BillChooser(
                payback = arjun,
                current = yuenKee.txn.id,
                offer = { q ->
                    if (q.isEmpty()) BillOffer.Recent(listOf(yuenKee, foodpanda, sriMelayu), listOf(tealive, mydin)) else BillOffer.Found(q, found, capped = false)
                },
                onPick = {},
                onDismiss = {},
            )
        }
        if (query != null) {
            compose.onNodeWithTag(BILL_SEARCH).performTextInput(query)
            compose.mainClock.advanceTimeBy(SEARCH_PAUSE_MS + 100)
        }
        compose.waitForIdle()
    }

    @Test fun billChooser() {
        chooser()
        capture("bill_chooser")
    }

    @Test fun billChooserSearch() {
        chooser("64", listOf(foodpanda, sriMelayu, mydinJuly, lotus))
        compose.onNodeWithText("4 BILLS, ALL HISTORY").assertExists()
        capture("bill_chooser_search")
    }

    @Test fun billChooserEmpty() {
        chooser("kfc")
        compose.onNodeWithText(nothingLine(SEP_20, "kfc")).assertExists()
        capture("bill_chooser_empty")
    }

    private fun card(id: Long, name: String, sen: Long, rule: String, title: String, body: String, category: String = Seed.UNCATEGORIZED, bill: BillChoice? = null) =
        ReviewCard(
            txn = ledgerTxn(amountSen = sen, occurredAt = at(SEP_20, 13, 12), state = TxnState.PENDING, pendingReason = PendingReason.RULE_REVIEW, id = id)
                .copy(direction = Direction.REFUND, merchantRaw = name.uppercase(), merchantDisplay = name),
            category = category,
            title = title,
            body = body,
            matchedRuleId = rule,
            bill = bill,
        )

    @Test fun inboxPayback() {
        val tng = "Touch 'n Go eWallet"
        val read = "RM 32.00 received from ARJUN NAIR for AISYAH RAHMAN."
        draw {
            ReviewInboxScreenContent(
                cards = listOf(
                    card(52L, "Arjun Nair", 3_200L, "tng-received-v1", tng, read),
                    card(53L, "Arjun Nair", 3_200L, "tng-received-v1", tng, read, bill = yuenKee),
                ),
                ledger = LedgerAccess.Readable,
                onBack = {},
                onAnswer = { _, _ -> },
            )
        }
        capture("inbox_payback")
    }

    @Test fun inboxMoneyBack() {
        val mae = "Maybank2u: Scan & Pay"
        val read = "You've received RM 27.95 from AHMAD BIN ABDULLAH. REF: 000000000Q."
        draw {
            ReviewInboxScreenContent(
                cards = listOf(
                    card(41L, "Ahmad Bin Abdullah", 2_795L, "mae-received-v1", mae, read),
                    card(42L, "Ahmad Bin Abdullah", 2_795L, "mae-received-v1", mae, read, category = "Food & Drinks"),
                ),
                ledger = LedgerAccess.Readable,
                onBack = {},
                onAnswer = { _, _ -> },
            )
        }
        capture("inbox_money_back")
    }

    private fun detail(
        name: String,
        sen: Long,
        at: Long,
        category: String,
        rule: String,
        source: String,
        title: String,
        body: String,
        bill: BillChoice? = null,
    ) {
        val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 1)
        val txn = ledgerTxn(amountSen = sen, occurredAt = at, id = 52L)
            .copy(direction = Direction.REFUND, merchantRaw = name.uppercase(), merchantDisplay = name, sourceLabel = source, billTxnId = bill?.txn?.id)
        val row = DetailRow(FeedRow(txn, txn.merchantKey, name), category, title, body, null, rule, 7)
        draw {
            DetailScreenContent(
                DetailRead(row, ChooserRead(categories = listOf(food)), bill = bill),
                ledger = LedgerAccess.Readable,
                onBack = {},
                categories = FakeCategoryActions(),
                merchants = FakeMerchantActions(),
                zone = zone,
            )
        }
    }

    @Test fun detailRefund() {
        detail(
            "Ahmad Bin Abdullah", 2_795L, at(LocalDate.of(2026, 9, 24), 21, 3), Seed.UNCATEGORIZED, "MAE-RECEIVED-V1", "Maybank MAE",
            "Maybank2u: Scan & Pay", "You've received RM 27.95 from AHMAD BIN ABDULLAH. REF: 000000000Q.",
        )
        capture("detail_refund")
    }

    @Test fun detailRefundFiled() {
        detail(
            "Domino’s", 2_490L, at(LocalDate.of(2026, 9, 12), 19, 48), "Food & Drinks", "MAE-REFUND-MY-V1", "Maybank MAE",
            "Maybank2u", "Bayaran balik RM24.90 daripada DOMINO'S PIZZA telah dikreditkan ke akaun anda.",
        )
        capture("detail_refund_filed")
    }

    @Test fun detailPayback() {
        detail(
            "Arjun Nair", 3_200L, at(SEP_20, 13, 12), Seed.UNCATEGORIZED, "TNG-RECEIVED-V1", "Touch 'n Go",
            "Touch 'n Go eWallet", "RM 32.00 received from ARJUN NAIR for AISYAH RAHMAN.",
            bill = billChoice(5L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 6_400L),
        )
        capture("detail_payback")
    }

    private val aug15: LocalDate = LocalDate.of(2026, 8, 15)
    private val aug17: LocalDate = LocalDate.of(2026, 8, 17)

    private fun entry(state: ManualEntryState) {
        draw {
            ManualEntryScreenContent(state = state, onAmount = {}, onMerchant = {}, onNote = {}, onSave = {}, onBack = {}, onSaved = {})
        }
    }

    @Test fun cashPayback() {
        entry(
            ManualEntryState(
                draft = ManualDraft(amountSen = 2_000L, merchant = "Siti Hajar", direction = Direction.REFUND, bill = 20L),
                today = aug17,
                // Daniel Lim's RM20.00 is in; Siti Hajar's is the one being added.
                bill = billChoice(20L, "Warung Pak Din", 6_000L, aug15, paidBackSen = 2_000L),
            ),
        )
        capture("cash_payback")
    }

    @Test fun cashPaybackEdit() {
        val stored = ManualDraft(amountSen = 2_000L, merchant = "Siti Hajar", direction = Direction.REFUND, bill = 20L, day = aug17)
        entry(
            ManualEntryState(
                draft = stored.copy(day = LocalDate.of(2026, 8, 14)),
                today = LocalDate.of(2026, 8, 30),
                editing = 31L,
                loaded = stored,
                bill = billChoice(20L, "Warung Pak Din", 6_000L, aug15, paidBackSen = 4_000L),
            ),
        )
        capture("cash_payback_edit")
    }

    private fun draw(content: @Composable () -> Unit) {
        compose.setContent { PingedTheme { content() } }
    }

    /**
     * Every window, so a sheet is drawn over the screen it opens on, into
     * the committed golden for [name], from the module directory as
     * `BreakdownRenderTest`'s `golden` resolves it.
     */
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$name.png")
    }
}
