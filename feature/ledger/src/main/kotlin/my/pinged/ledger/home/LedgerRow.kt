package my.pinged.ledger.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.LocalDate
import my.pinged.ledger.entry.EditTarget
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.R
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason
import my.pinged.ui.money
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dashedOutline

/**
 * A ledger row as every screen that lists transactions draws it (§9.1, #69):
 * the Ledger's feed and a single day's list draw the same row, with the same
 * two offers on it.
 *
 * What may be offered is decided here rather than by the caller, so the two
 * screens cannot disagree about it. Spec 6.4: a row with no merchant has
 * nothing to rename or merge, so it is offered no sheet. The chooser is
 * offered only when [canCategorize] -- there is something to choose -- and
 * the row is not excluded: an excluded row is out of every total (§7.3), so a
 * category assigned to it changes no number on any screen. Each extra gate
 * would otherwise be a control that takes a tap and does nothing.
 *
 * [uncategorized] is a fact about the row, and nothing else: the row §7.1's
 * gate could not file. A null [uncategorizedId] -- the seed row is gone --
 * equals no row's `category_id`, which is the honest answer there: nothing
 * knows which rows are uncategorized, so no row is claimed to be.
 *
 * [onOpenEntry] is spec 9.4's edit screen (#105), offered on a manual
 * entry's body. [onOpenDetail] is a captured row's detail screen (#153),
 * offered on its body whatever its state: what Pinged read is worth seeing on
 * a held row too, and the screen offers only the answers the row can take.
 * Either is null where the screen routes nowhere.
 *
 * [greyed] draws the amount in the untrusted grey (#73), for a screen whose
 * every figure greys in a month with a day not captured: Breakdown's (#342).
 */
@Composable
internal fun LedgerRow(
    row: FeedRow,
    categoriesById: Map<Long, Category>,
    uncategorizedId: Long?,
    canCategorize: Boolean,
    onCategorize: (txnId: Long) -> Unit,
    onOpenMerchant: (MerchantOpening) -> Unit,
    onOpenReview: (txnId: Long) -> Unit,
    onOpenEntry: ((EditTarget) -> Unit)? = null,
    onOpenDetail: ((EditTarget) -> Unit)? = null,
    greyed: Boolean = false,
) {
    val txn = row.txn
    val manual = txn.rawCaptureId == null
    TxnRow(
        txn = txn,
        name = row.displayName,
        onOpen = (if (manual) onOpenEntry else onOpenDetail)?.let { open -> { open(EditTarget(txn.id, txn.createdAt)) } },
        openLabel = if (manual) EDIT_ENTRY else OPEN_DETAIL,
        onOpenMerchant = txn.merchantKey?.let { key -> { onOpenMerchant(MerchantOpening(key, row.displayName ?: key, txn.localDate)) } },
        category = categoriesById[txn.categoryId],
        uncategorized = txn.categoryId == uncategorizedId,
        onCategorize = if (canCategorize && !txn.isExcluded) {
            { onCategorize(txn.id) }
        } else {
            null
        },
        onOpenReview = { onOpenReview(txn.id) },
        greyed = greyed,
    )
}

/**
 * What the chooser offers: every category but `Uncategorized`, which would
 * read as the "never mind" tap and is not a no-op -- a save writes the row's
 * category, and a one-off writes `user_edited = 1`, which §5.5 excludes from
 * the re-parse comparison for good, so one stray tap would opt that
 * transaction out of every future pack fix. Removed from the list rather than
 * refused at the write, so the whole class goes with it. The row's own
 * category is still offered, because saving it again is how a one-off becomes
 * a rule. Both halves come off one [ChooserRead], which is what keeps the
 * filter from being handed a new list beside a stale id.
 */
internal fun chooserChoices(chooser: ChooserRead): List<Category> =
    chooser.categories.filter { it.id != chooser.uncategorizedId }

/**
 * The category chooser for [row], drawn by a screen that holds the row and a
 * [ChooserRead] (#45, #48, #49).
 *
 * The sheet picks a category and then saves it, and the save either teaches
 * or does not, by the "Always call this" switch. With it on the save writes
 * the merchant's learned rule, moves the merchant's past payments when none
 * was set by hand, and leaves the row `user_edited = 0`, filed by the rule;
 * with it off the save is a one-off, `user_edited = 1`, the record that a
 * person decided this one payment (§5.5).
 *
 * The caller draws it only when [chooserChoices] is not empty: a sheet whose
 * every row was filtered out still draws its heading and its handle. [onClose]
 * is called on a save, before [actions] writes it, as on a dismiss.
 * [addCategory] is the New category tile's write, null for no tile.
 */
@Composable
internal fun RowCategoryChooser(
    row: FeedRow,
    chooser: ChooserRead,
    actions: CategoryActions,
    onClose: () -> Unit,
    addCategory: (suspend (name: String, iconKey: String) -> Long?)? = actions::addCategory,
) {
    val busy by actions.busy.collectAsState()
    val choices = remember(chooser.categories, chooser.uncategorizedId) { chooserChoices(chooser) }
    val categoriesById = remember(chooser.categories) { chooser.categories.associateBy { it.id } }
    // One key, so the two halves cannot come from different rows' keys.
    val filing = chooser.filer.file(row.txn.merchantKey)
    val startsOn = teachSwitchDefault(
        hasKey = row.identityKey != null,
        categoryId = row.txn.categoryId,
        userEdited = row.txn.userEdited,
        uncategorizedId = chooser.uncategorizedId,
        filing = filing,
    )
    // Merged: this key is aliased away, or other keys are aliased to it.
    // Either way the one rule on the identity files them all.
    val mergedRuleCategory = filing.learned
        ?.takeIf { row.txn.merchantKey?.let(chooser.filer.learned::isMerged) == true }
        ?.let { ruleId -> categoriesById[ruleId]?.name }
    CategoryPicker(
        categories = choices,
        subject = ChooserSubject(
            bill = row.txn.merchantRaw ?: row.displayName ?: "Unknown merchant",
            amount = money(row.txn.amountSen, row.txn.currency, symbol = false),
        ),
        teach = startsOn?.let { TeachOffer(row.displayName, it, mergedRuleCategory) },
        initial = row.txn.categoryId.takeIf { it != chooser.uncategorizedId },
        preview = { categoryId -> actions.retroPreview(row.txn.id, categoryId) },
        onSave = { categoryId, teach ->
            onClose()
            actions.assignCategory(row.txn.id, categoryId, teach)
        },
        onDismiss = onClose,
        allNames = chooser.categories.map { it.name },
        onAdd = addCategory,
        busy = busy,
    )
}

/**
 * One transaction. Spec 9.1's merchant, source and amount.
 *
 * **A `PENDING` row and an excluded row are both drawn and both badged.**
 * Neither counts toward any total (§8), and there is no review inbox in this
 * milestone (§9.2 is not built), so a `PENDING` row this list hid would be
 * money in the database and nowhere on screen at all.
 *
 * An excluded row is struck through and badged, and greyed to [Muted] -- not to
 * [Faint], which measures 3.12:1 on this ground and is reserved by its own
 * documentation for a component's states rather than for text. The
 * strikethrough and the badge are what carry the meaning anyway, so a reader
 * who sees no colour still reads it (WCAG 1.4.1).
 *
 * The small print under the merchant is the artboard's "Groceries · Touch 'n
 * Go": the category the money is filed under, then the app it was read from,
 * or [ENTERED_BY_HAND] for a manual entry.
 * On an [uncategorized] row the name is dropped -- "Uncategorized" is where the
 * row is filed, not what it is about, and the row's own mark already says so --
 * and the chip stands in its place when there is one. On an excluded row the
 * category is dropped too: §7.3 takes the row out of every total, so which
 * category it is filed under is a fact about nothing. The leading slot is
 * [RowIcon].
 *
 * **[uncategorized] is the row's state and [onCategorize] is the offer, and
 * they must stay separate.** Collapsed into one flag, a screen with nothing to
 * offer draws a genuinely uncategorized row as a categorized one:
 * "Uncategorized · GrabPay" in the small print, under the muted `circle-dashed`
 * glyph instead of the accent mark.
 */
@Composable
private fun TxnRow(
    txn: Txn,
    name: String?,
    onOpen: (() -> Unit)?,
    openLabel: String,
    onOpenMerchant: (() -> Unit)?,
    category: Category?,
    uncategorized: Boolean,
    onCategorize: (() -> Unit)?,
    onOpenReview: () -> Unit,
    greyed: Boolean = false,
) {
    val excluded = txn.isExcluded
    Row(
        Modifier
            .fillMaxWidth()
            .rowGestures(onOpen, openLabel, onOpenMerchant)
            // The board's 11dp between rows, split so the 8 below keeps the
            // category line's target at WCAG 2.5.8's 24dp (see [rowGestures]).
            .padding(start = 20.dp, end = 20.dp, top = 3.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowIcon(excluded = excluded, uncategorized = uncategorized, iconKey = category?.iconKey)
        Column(Modifier.weight(1f)) {
            Text(
                // Spec 6.4's resolved name. `merchant_key` is not offered as a
                // fallback: it is the grouping identity, uppercased and
                // stripped, not a name.
                name ?: "Unknown merchant",
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                color = if (excluded) Muted else Ink,
                // Not struck through, though the amount is: the number is the
                // thing §7.3 says does not count, and striking both reads as
                // deleted, which is exactly what an excluded row is not.
                modifier = Modifier.fillMaxWidth().rowBody(),
            )
            // Spec 9.1: a manual entry says how it reached the ledger, never
            // how it was paid. `raw_capture_id IS NULL` is what a manual
            // entry is (spec 4), whatever its source columns hold.
            val source = if (txn.rawCaptureId == null) ENTERED_BY_HAND else txn.sourceLabel ?: txn.sourcePackage
            // One `Text`, so the line reads as one line to a screen reader
            // rather than as fragments. The leading separator belongs only to
            // the chip: in front of a source with no category before it, "·"
            // is punctuation for a name that is not there.
            val name = category?.name.takeUnless { uncategorized || excluded }
            val line = listOfNotNull(name, source).joinToString(Separator)
            // The chip is the marker on an uncategorized row; on a categorised
            // one the line itself opens the sheet, as one control so the line
            // still reads as one line to a screen reader.
            val chip = onCategorize != null && uncategorized
            val lineOpensSheet = onCategorize != null && !uncategorized && name != null
            // No row at all when there is neither chip nor line, rather than an
            // empty one: `source_package` and `source_label` are both nullable
            // -- an imported row can have neither -- and the 2.dp would then be
            // a gap under the merchant holding a line that is not there.
            if (chip || line.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (chip) {
                        CategoryChip(onClick = onCategorize!!)
                    }
                    if (line.isNotEmpty()) {
                        Text(
                            (if (chip) Separator else "") + line,
                            // Karla: a category and a source are names, and
                            // names are body copy. 12/16 rather than the
                            // artboard's 11.5, the size and leading of the
                            // chip it sits beside.
                            fontFamily = Body,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = Muted,
                            modifier = if (lineOpensSheet) {
                                Modifier
                                    .clip(RoundedCornerShape(2.dp))
                                    .clickable(role = Role.Button, onClick = onCategorize!!)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
            if (txn.state == TxnState.PENDING) {
                // The reason, not just the state, so the row says which of
                // §7.1's gates fired before the inbox is opened. §9.1: the
                // badge is a way into the inbox, on this row's card.
                Badge(awaitingReviewBadge(txn.pendingReason?.name), Stamp, onClick = onOpenReview)
            }
            if (excluded) {
                Badge(notSpendingBadge(txn.exclusionReason), Muted)
            }
        }
        Text(
            // A refund is signed in every total (it reduces its day), so it is
            // signed here too: two rows of the same size, one money out and one
            // money back, are otherwise identical on screen.
            (if (txn.direction == Direction.REFUND) "+" else "") +
                money(txn.amountSen, txn.currency, symbol = false),
            style = MonoNumerals.copy(fontSize = 15.sp),
            color = if (excluded || greyed) Muted else Ink,
            textDecoration = if (excluded) TextDecoration.LineThrough else null,
        )
    }
}

/** A manual entry's source in its small print (spec 9.1): `Food · Entered by hand`. */
const val ENTERED_BY_HAND = "Entered by hand"

/** The chip's label, named so the screen and its test cannot spell it differently. */
internal const val CATEGORY_CHIP = "+ CATEGORY"

/** The accessibility action a row offers for spec 6.4's sheet. */
internal const val EDIT_MERCHANT = "Edit merchant"

/** The accessibility action a manual entry's row offers for its edit screen (#105). */
internal const val EDIT_ENTRY = "Edit payment"

/** The accessibility action a captured row offers for its detail screen (#153). */
internal const val OPEN_DETAIL = "Show what Pinged read"

/**
 * The row body's gestures: a tap is [onOpen], a manual entry's edit screen
 * (#105) or a captured row's detail screen (#153), named [openLabel]; a
 * long press is [onOpenMerchant], spec 6.4's merchant sheet.
 *
 * The sheet is a long press, not a tap, so that it cannot be mistaken for
 * the uncategorized chip inside the same row. Both go through one
 * `pointerInput` rather than `combinedClickable`, which would make every
 * row a tap target, one on a screen that routes the tap nowhere included.
 * The chip, the small print and the badge are clickables of their own: they
 * consume their tap, so it never reaches [onOpen]. The semantics actions are
 * what a screen reader offers instead, since a long press is a gesture
 * TalkBack does not pass through as one.
 *
 * **Those three are laid out at their drawn size, and their touch target is
 * the free space around them** (#186, superseding #178's 44dp for them).
 * The rule for a control inside a list row: it takes the space no other
 * target holds, and never less than WCAG 2.5.8's 24dp. Compose supplies it:
 * a clickable smaller than `ViewConfiguration.minimumTouchTargetSize`, 48dp
 * on Android, takes a near miss up to that size around it, and as a
 * descendant it gets the tap before this row does. It stops at a direct hit
 * on another target -- the next row's tap below, [rowBody] on the name above
 * -- and at 48dp in all. So the category line's target is its 16dp and the
 * row's 8dp padding below, 24dp -- the floor exactly, so the 8 cannot shrink
 * -- and the 2dp gap above goes to whichever of it and the name is nearer. `RowTouchTargetTest` taps outside each drawn
 * control and reaches it, reaches the row once the minimum is zeroed, and
 * taps the name and reaches the row. Grown to 44dp in layout instead, a
 * categorised row measured 85dp against 57dp.
 */
private fun Modifier.rowGestures(onOpen: (() -> Unit)?, openLabel: String, onOpenMerchant: (() -> Unit)?): Modifier =
    if (onOpen == null && onOpenMerchant == null) this else this
        .pointerInput(onOpen, onOpenMerchant) {
            detectTapGestures(
                onTap = onOpen?.let { open -> { open() } },
                onLongPress = onOpenMerchant?.let { open -> { open() } },
            )
        }
        .semantics {
            if (onOpen != null) onClick(label = openLabel) { onOpen(); true }
            if (onOpenMerchant != null) onLongClick(label = EDIT_MERCHANT) { onOpenMerchant(); true }
        }

/**
 * The merchant name as a direct hit, which handles nothing itself: the tap
 * goes on to [rowGestures], its ancestor. Without it the name is no target,
 * and the category line's 48dp near miss reaches 16dp up into it: a tap on
 * the name's centre, about 11.5dp above the line, opened the category sheet
 * (#186). Full width, because a short name leaves the rest of its line bare.
 */
private fun Modifier.rowBody(): Modifier = pointerInput(Unit) {}

/**
 * The artboard's "+ CATEGORY": the one control on a row, and the only thing on
 * this screen that writes.
 *
 * Drawn in [Stamp], the accent this app reserves for the state that wants
 * acting on, inside the theme's [dashedOutline].
 *
 * [Role.Button] so it reports as one: `SourcesScreen`'s `Header` records what a
 * tappable-looking thing with no semantics cost. Search's result rows draw it
 * too (#273).
 */
@Composable
internal fun CategoryChip(onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .dashedOutline(Stamp)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(CATEGORY_CHIP, style = MonoLabel, color = Stamp)
    }
}

/** What the row's leading slot draws, and whether it draws it in the accent. */
internal data class RowMark(@DrawableRes val icon: Int, val accent: Boolean)

/**
 * Which mark the row's leading slot holds, or null for an empty slot.
 *
 * A function rather than a `when` inside [RowIcon], for the reason
 * [dayHeaderBetween] is one: a decorative icon carries no `contentDescription`,
 * so through a composition this rule can only be asserted on a picture.
 * `RowMarkRuleTest` asserts it directly.
 *
 * **The order of the first two branches is the rule, and nothing upstream
 * enforces it.** [uncategorized] is the row's own state, so both arguments
 * arrive true for a row that is excluded *and* unfiled -- and it must read as
 * not counted: [TxnRow] is handed no action for such a row, and an accent "add
 * a category" mark over a chip that is not drawn would point at a control that
 * is not there. `RowMarkRuleTest` asserts the pair directly, because that state
 * has no separate spelling on screen to assert on.
 *
 * Only the third branch is the category's own icon, resolved from the category
 * row at render time as §4 requires.
 */
internal fun rowMark(excluded: Boolean, uncategorized: Boolean, iconKey: String?): RowMark? =
    when {
        excluded -> RowMark(R.drawable.ic_row_not_counted, accent = false)
        uncategorized -> RowMark(R.drawable.ic_row_uncategorized, accent = true)
        else -> iconKey?.let(::categoryIcon)?.let { RowMark(it, accent = false) }
    }

/**
 * The row's leading 18dp slot, drawn at [Muted] like the artboard's.
 *
 * The slot keeps its width whatever [rowMark] answers, so the merchant names
 * below stay on one left edge. A key this build does not bundle draws
 * **nothing** rather than a stand-in -- see [categoryIcon].
 *
 * Decorative, so no `contentDescription`: everything it encodes is already
 * written beside it -- the category in the line under the merchant, the
 * exclusion in the badge below that -- and announcing it would read the row
 * twice.
 */
@Composable
private fun RowIcon(excluded: Boolean, uncategorized: Boolean, iconKey: String?) {
    val mark = rowMark(excluded, uncategorized, iconKey)
    Box(Modifier.size(18.dp)) {
        if (mark != null) {
            Icon(
                painter = painterResource(mark.icon),
                contentDescription = null,
                // The artboard draws every row's glyph at Muted and the
                // uncategorized one in the accent, pairing it with the chip
                // below so the two read as one invitation.
                tint = if (mark.accent) Stamp else Muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * A framed mono label, in the colour of whatever it is qualifying.
 *
 * With [onClick] it is a button, laid out at its frame's size, its target
 * the free space around it as [rowGestures] sets out. The 4dp above it is
 * outside the click, as the plain badge's is: flush under the category
 * line, the badge's upper near miss is a direct hit on the line.
 */
@Composable
private fun Badge(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MonoLabel,
        color = color,
        modifier = Modifier
            .then(
                if (onClick == null) {
                    Modifier.padding(top = 4.dp)
                } else {
                    Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .clickable(role = Role.Button, onClickLabel = OPEN_REVIEW, onClick = onClick)
                },
            )
            .border(1.dp, color, RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A held row's badge, §9.1: `AWAITING REVIEW · RULE REVIEW`. */
internal fun awaitingReviewBadge(reason: String?): String = "AWAITING REVIEW" + Separator + words(reason)

/**
 * An excluded row's badge, §7.3: `NOT SPENDING · CARD PAYMENT`. Each reason
 * has its own words rather than its name, because `USER` printed as copy
 * named a code concept (#214); the wording for it is
 * `design/DetailExcluded.dc.html`'s.
 */
internal fun notSpendingBadge(reason: ExclusionReason?): String = "NOT SPENDING" + Separator + when (reason) {
    ExclusionReason.TRANSFER -> "TRANSFER"
    ExclusionReason.CARD_PAYMENT -> "CARD PAYMENT"
    ExclusionReason.ATM_WITHDRAWAL -> "ATM WITHDRAWAL"
    ExclusionReason.USER -> "YOU LEFT IT OUT"
    null -> words(null)
}

/** What a held row's badge says it does, to a screen reader. */
internal const val OPEN_REVIEW = "Open the review inbox"

/** `RULE_REVIEW` as "RULE REVIEW", and an absent reason as nothing. */
internal fun words(name: String?): String = name?.replace('_', ' ') ?: "REASON NOT RECORDED"
