package my.pinged.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.capture.Corrections
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.LearnedMerchant
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.corrections.ACCEPT as CORRECTION_ACCEPT
import my.pinged.ledger.corrections.CorrectionsContent
import my.pinged.ledger.corrections.CorrectionsState
import my.pinged.ledger.corrections.KEEP as CORRECTION_KEEP
import my.pinged.ledger.entry.ADD_ANYWAY
import my.pinged.ledger.entry.CONFIRM_DELETE
import my.pinged.ledger.entry.DELETE_CANCEL
import my.pinged.ledger.entry.DELETE_PAYMENT
import my.pinged.ledger.entry.ManualDraft
import my.pinged.ledger.entry.ManualEntryScreenContent
import my.pinged.ledger.entry.ManualEntryState
import my.pinged.ledger.entry.NEARBY_CANCEL
import my.pinged.ledger.entry.NearbyCapture
import my.pinged.ledger.entry.SAVE
import my.pinged.ledger.home.CategoryPicker
import my.pinged.ledger.home.ChooserSubject
import my.pinged.ledger.home.Empty
import my.pinged.ledger.home.LedgerRow
import my.pinged.ledger.home.OPEN_SETTINGS
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ledger.learned.LearnedContent
import my.pinged.ledger.learned.LearnedState
import my.pinged.ledger.licences.LicencesContent
import my.pinged.ledger.review.AcceptAll
import my.pinged.ledger.review.ReviewAnswer
import my.pinged.ledger.review.ReviewCard
import my.pinged.ledger.review.ReviewInboxScreenContent
import my.pinged.ledger.review.UNDO
import my.pinged.ledger.review.Undoable
import my.pinged.ledger.review.VERBATIM
import my.pinged.ledger.review.transferQuestion
import my.pinged.ledger.settings.ExportSheet
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.Outcome
import my.pinged.ledger.settings.OutcomeSheet
import my.pinged.ledger.settings.RecoveryNotice
import my.pinged.ledger.settings.RestoreConfirmSheet
import my.pinged.ledger.settings.SettingsState
import my.pinged.ledger.settings.Storage
import my.pinged.ledger.settings.TransferJob
import my.pinged.ledger.sources.CaptureBanner
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Mono
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Quote
import my.pinged.ui.theme.RESCUE
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Separator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Spec 8's type roles (#166), read off the style each text was laid out in:
 * Instrument Serif for totals and screen titles only, Karla for copy and
 * buttons, Plex Mono for numerals, uppercase labels and the notification
 * quoted word for word -- and no mono label under [MonoLabel]'s 12sp floor
 * without a comment saying why.
 *
 * The family a `Text` is handed is not always the one it lays out in: a
 * Material `Button` supplies `labelLarge`, which is the mono, to a `Text` that
 * names no family. So each check reads the [TextLayoutResult]'s input style,
 * which is what was drawn.
 */
@RunWith(AndroidJUnit4::class)
class TypeRolesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theReviewCardQuotesTheNotificationInMono() {
        review(transfer)
        listOf(TITLE, BODY).forEach {
            assertEquals("The verbatim box draws \"$it\" outside the mono", Mono, styleOf(it).fontFamily)
        }
    }

    /**
     * The quoted notification sits on its own ground, as the artboard fills
     * it: the colour most of the box is painted in, since the text is sparse
     * and the outline is one dp.
     */
    @Test fun theVerbatimBoxIsFilledWithTheQuoteGround() {
        review(transfer)
        val pixels = verbatimBox()
        val counts = HashMap<Int, Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            counts.merge(pixels[x, y].toArgb(), 1, Int::plus)
        }
        val most = Color(counts.maxBy { it.value }.key)
        assertEquals("The verbatim box is mostly painted in $most, not the Quote ground", Quote, most)
    }

    /**
     * Dotted, not solid: along the top edge the outline must leave gaps where
     * the fill shows through. A solid border paints the whole run in [Rule].
     */
    @Test fun theVerbatimBoxIsOutlinedInDots() {
        review(transfer)
        val pixels = verbatimBox()
        val density = compose.density.density
        val row = (density / 2).toInt()
        val inset = (4 * density).toInt()
        val run = (inset until pixels.width - inset).map { pixels[it, row] }
        val gaps = run.count { distance(it, Quote) < distance(it, Rule) }
        val dots = run.size - gaps
        assertTrue(
            "The verbatim box's top edge has $dots outline pixels and $gaps gaps; a dotted rule has both",
            dots > run.size / 5 && gaps > run.size / 5,
        )
    }

    @Test fun theTransferQuestionIsBodyCopy() {
        review(transfer)
        assertEquals(
            "The transfer question is drawn in a family other than Karla; the serif is for totals and titles",
            Body,
            styleOf(transferQuestion(transfer)).fontFamily,
        )
    }

    @Test fun theReviewCardsAnswersAreKarlaButtons() {
        review(transfer)
        listOf(ReviewAnswer.LEAVE_OUT, ReviewAnswer.KEEP_AS_EXPENSE, ReviewAnswer.NOT_A_PAYMENT).forEach {
            assertButton(it.label)
        }
    }

    @Test fun aPlainCardsAnswersAndAcceptAllAreKarlaButtons() {
        review(plain)
        listOf(ReviewAnswer.ACCEPT, ReviewAnswer.NOT_A_PAYMENT).forEach { assertButton(it.label) }
        assertButton(requireNotNull(AcceptAll.of(listOf(plain))).label)
    }

    @Test fun theLedgerRowsSubLineIsBodyCopy() {
        compose.setContent {
            PingedTheme {
                LedgerRow(
                    row = FeedRow(ledgerTxn(amountSen = 1_250L, id = 7L), identityKey = null, displayName = MERCHANT),
                    categoriesById = mapOf(1L to groceries),
                    uncategorizedId = null,
                    canCategorize = false,
                    onCategorize = {},
                    onOpenMerchant = {},
                    onOpenReview = {},
                )
            }
        }
        val line = groceries.name + Separator + SOURCE_LABEL
        assertEquals("The row's sub-line \"$line\" is not drawn in Karla", Body, styleOf(line).fontFamily)
    }

    @Test fun cashSaveIsAKarlaButton() {
        compose.setContent {
            PingedTheme {
                ManualEntryScreenContent(
                    state = ManualEntryState(),
                    onAmount = {},
                    onMerchant = {},
                    onNote = {},
                    onSave = {},
                    onBack = {},
                    onSaved = {},
                )
            }
        }
        assertButton(SAVE)
    }

    @Test fun theChoosersSaveIsAKarlaButton() {
        compose.setContent {
            PingedTheme {
                CategoryPicker(
                    categories = listOf(groceries),
                    subject = ChooserSubject("TNG*99SPEEDMART", "32.00"),
                    teach = null,
                    initial = groceries.id,
                    preview = { RetroPreview(0, emptyList()) },
                    onSave = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        assertButton(SAVE_LABEL)
    }

    @Test fun exportIsAKarlaButton() {
        compose.setContent {
            PingedTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ExportSheet(SettingsState(loaded = true), onPick = {}, onCancel = {}, onDismiss = {})
                }
            }
        }
        assertButton("Export")
    }

    @Test fun theRestoreConfirmationsAnswersAreKarlaButtons() {
        compose.setContent { PingedTheme { RestoreConfirmSheet(onConfirm = {}, onDismiss = {}) } }
        assertButton("Keep what's here")
        assertButton("Continue")
    }

    @Test fun theNearbyWarningsAnswersAreKarlaButtons() {
        entry(ManualEntryState(draft = ManualDraft(amountSen = 1_250L), nearby = nearby))
        assertButton(ADD_ANYWAY)
        assertButton(NEARBY_CANCEL)
    }

    @Test fun deletingAnEntryAsksWithKarlaButtons() {
        val stored = ManualDraft(amountSen = 1_250L)
        entry(ManualEntryState(draft = stored, editing = 7L, loaded = stored))
        assertButton(DELETE_PAYMENT)
        compose.onNodeWithText(DELETE_PAYMENT).performScrollTo().performClick()
        assertButton(CONFIRM_DELETE)
        assertButton(DELETE_CANCEL)
    }

    @Test fun theInboxsUndoIsAKarlaButton() {
        compose.setContent {
            PingedTheme {
                ReviewInboxScreenContent(
                    cards = emptyList(),
                    storageUnavailable = false,
                    busy = null,
                    onBack = {},
                    onAnswer = { _, _ -> },
                    undoable = Undoable("ACCEPTED · RM650.00", emptyList()),
                )
            }
        }
        assertButton(UNDO)
    }

    @Test fun aCorrectionsAnswersAndBackAreKarlaButtons() {
        val before = ledgerTxn(amountSen = 226_276L, state = TxnState.PENDING, pendingReason = PendingReason.OVER_THRESHOLD, id = 7L)
        val correction = Corrections.Correction(
            captureId = 70L,
            fromPackVersion = 11,
            toPackVersion = 12,
            previousRuleId = "mae-scan-pay-v1",
            ruleId = "mae-card-bill-v1",
            before = before,
            after = before.copy(pendingReason = PendingReason.TRANSFER_SUSPECT),
            changed = setOf(Corrections.Field.STATUS),
        )
        compose.setContent {
            PingedTheme {
                CorrectionsContent(
                    CorrectionsState(loaded = true, items = listOf(correction), packVersion = 12),
                    onBack = {}, onAccept = {}, onDecline = {},
                )
            }
        }
        assertButton(CORRECTION_ACCEPT)
        assertButton(CORRECTION_KEEP)
        assertButton(BACK)
    }

    /** Delete is the board's trash icon, a mark rather than a word, so only Back has a face to read. */
    @Test fun aLearnedMerchantsBackIsAKarlaButton() {
        val merchant = LearnedMerchant("MR DIY", "Hardware shop", 4L, "Shopping", payments = 3)
        compose.setContent {
            PingedTheme { LearnedContent(LearnedState(loaded = true, items = listOf(merchant)), onBack = {}, onDelete = {}) }
        }
        assertButton(BACK)
    }

    @Test fun theLicencesBackIsAKarlaButton() {
        compose.setContent { PingedTheme { LicencesContent(emptyList(), onBack = {}) } }
        assertButton(BACK)
    }

    @Test fun aSettledTransfersOkIsAKarlaButton() {
        compose.setContent {
            PingedTheme { OutcomeSheet(Outcome(1L, Operation.RESTORE, TransferJob.Failed("The file is not a backup.")), onDismiss = {}) }
        }
        assertButton("OK")
    }

    @Test fun aDamagedLedgersRescueIsAKarlaButton() {
        compose.setContent {
            PingedTheme { RecoveryNotice(SettingsState(storage = Storage.DAMAGED, loaded = true), onRescue = {}) }
        }
        assertButton(RESCUE)
    }

    @Test fun anEmptyLedgersOpenSettingsIsAKarlaButton() {
        compose.setContent { PingedTheme { Empty(onOpenSettings = {}) } }
        assertButton(OPEN_SETTINGS)
    }

    @Test fun theCaptureBannersLabelIsNotUnderTheMonoFloor() {
        val label = "CAPTURE STOPPED" + Separator + "3 DAYS AGO"
        compose.setContent { PingedTheme { CaptureBanner(label = label, body = "Pinged has not heard anything.") } }
        val size = styleOf(label).fontSize
        assertTrue(
            "The banner's label is drawn at $size, under MonoLabel's ${MonoLabel.fontSize} floor",
            size.value >= MonoLabel.fontSize.value,
        )
    }

    /**
     * Spec 8's buttons: Karla at weight 500, in sentence case, as every
     * artboard draws them. Capitals are the mono's labels, not a button's;
     * an abbreviation such as `OK` is a word of its own.
     */
    private fun assertButton(label: String) {
        assertTrue("The button \"$label\" is set in capitals", label.any { it.isLowerCase() } || label.length <= 2)
        val style = styleOf(label)
        assertEquals("The button \"$label\" is not drawn in Karla", Body, style.fontFamily)
        assertEquals("The button \"$label\" is not drawn at weight 500", FontWeight.Medium, style.fontWeight)
    }

    private fun entry(state: ManualEntryState) = compose.setContent {
        PingedTheme {
            ManualEntryScreenContent(state = state, onAmount = {}, onMerchant = {}, onNote = {}, onSave = {}, onBack = {}, onSaved = {})
        }
    }

    private fun review(card: ReviewCard) = compose.setContent {
        PingedTheme {
            ReviewInboxScreenContent(cards = listOf(card), storageUnavailable = false, busy = null, onBack = {}, onAnswer = { _, _ -> })
        }
    }

    private fun verbatimBox(): PixelMap = compose.onNodeWithTag(VERBATIM).performScrollTo().captureToImage().toPixelMap()

    private fun distance(a: Color, b: Color): Float =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    /** The style [text] was laid out in: one node, matched whole. */
    private fun styleOf(text: String): TextStyle {
        val matched = compose.onNodeWithText(text, useUnmergedTree = true)
        // Brought on screen where it can be, for CI's short screen; not every
        // surface here sits in a scrolling parent.
        if (hasAnyAncestor(hasScrollAction()).matches(matched.fetchSemanticsNode())) matched.performScrollTo()
        val node = matched.fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action) {
            "\"$text\" exposes no text-layout action to read its style from"
        }(layouts)
        return layouts.single().layoutInput.style
    }

    private companion object {
        const val TITLE = "TNG eWallet"
        const val BODY = "You've reloaded RM100.00 to your eWallet.\nRef: 000000"

        const val BACK = "Back"

        val nearby = NearbyCapture(amountSen = 1_250L, source = SOURCE_LABEL, occurredAt = 1_000L, awaitingReview = false)

        val groceries = Category(id = 1L, name = "Groceries", iconKey = "shopping-basket", sortOrder = 0)

        val plain = ReviewCard(
            txn = ledgerTxn(
                amountSen = 65_000L,
                state = TxnState.PENDING,
                pendingReason = PendingReason.OVER_THRESHOLD,
                id = 42L,
            ),
            title = TITLE,
            body = BODY,
        )

        val transfer = ReviewCard(
            txn = ledgerTxn(
                amountSen = 10_000L,
                state = TxnState.PENDING,
                pendingReason = PendingReason.TRANSFER_SUSPECT,
                id = 41L,
            ),
            title = TITLE,
            body = BODY,
        )
    }
}
