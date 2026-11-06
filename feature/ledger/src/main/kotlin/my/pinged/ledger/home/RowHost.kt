package my.pinged.ledger.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category

/**
 * The screen side of [RowActions] for one screen that lists ledger rows (#377):
 * which row the category chooser is open on, whether there is anything to
 * choose, and the chooser and spec 6.4's merchant sheet themselves, drawn by
 * [Sheets].
 *
 * **[Sheets] is a sibling of the list, never an item inside it.** A
 * `ModalBottomSheet` emitted from inside a `LazyColumn` item scrolls out of
 * the viewport and is disposed with the item that owns it.
 *
 * **The row being categorized is saved as its id, and found again among the
 * rows read**, through the screen's `findRow`. What the chooser needs of the
 * row (its key, its category, whether a person set it) is a read of the
 * database, and after a process death it would be a read of a ledger that may
 * have moved. A row not among those read draws no chooser; it opens once a
 * read brings the row back.
 *
 * [canCategorize] says only that the chooser has something to offer. Which
 * rows show the offer is each screen's own rule, ANDed with it at the row.
 * The chooser is gated on it as well as on a row being chosen: a tap cannot
 * reach the chooser with nothing to offer, since the offer is gated on the
 * same list, but a restore can -- the id comes back from `rememberSaveable`
 * before the first read has delivered a category, and a sheet whose every row
 * was filtered out still draws its heading and its handle.
 *
 * The chooser's [ChooserRead] is the screen's own, read in the transaction
 * that read the rows it marks (`RowActions.readChooser`): a rename or delete
 * in Settings between two reads would leave a row naming a category the
 * chooser lacks. The host reads nothing itself.
 */
@Stable
internal class RowHost(
    private val categorizing: MutableState<Long?>,
    private val chooser: State<ChooserRead?>,
    private val findRow: State<(Long) -> FeedRow?>,
    private val categories: State<CategoryActions>,
    private val merchants: State<MerchantActions?>,
    private val offerNewCategory: State<Boolean>,
) {
    private val choices by derivedStateOf { chooser.value?.let(::chooserChoices).orEmpty() }

    /** The chooser has something to offer: not that any one row may offer it. */
    val canCategorize: Boolean by derivedStateOf { choices.isNotEmpty() }

    /**
     * The chooser's categories by id, which the rows are named from. One map
     * per read, because a `LazyColumn` recomposes as it scrolls and rebuilding
     * a sixteen-entry map per frame is work for nothing.
     */
    val categoriesById: Map<Long, Category> by derivedStateOf {
        chooser.value?.categories.orEmpty().associateBy { it.id }
    }

    /** Open the chooser on the row [txnId]. */
    fun categorize(txnId: Long) {
        categorizing.value = txnId
    }

    /** The chooser, while a row among those read is being categorized, and the merchant sheet, while one is open. */
    @Composable
    fun Sheets() {
        val id = categorizing.value
        val chooser = chooser.value
        val row = id?.let { findRow.value(it) }
        if (row != null && chooser != null && canCategorize) {
            val actions = categories.value
            RowCategoryChooser(
                row = row,
                chooser = chooser,
                actions = actions,
                onClose = { categorizing.value = null },
                addCategory = if (offerNewCategory.value) actions::addCategory else null,
            )
        }
        // Its state is the holder's, not saved here, for the reason the
        // chooser's row is found again: it is a read of the database.
        merchants.value?.let { MerchantSheet(it) }
    }
}

/**
 * The [RowHost] for a screen whose read published [chooser] and whose rows
 * [findRow] finds by id. [merchants] null draws no merchant sheet (Search's
 * rows open none); [offerNewCategory] false draws the chooser without its New
 * category tile (Detail's, which #307 left undecided).
 */
@Composable
internal fun rememberRowHost(
    chooser: ChooserRead?,
    findRow: (txnId: Long) -> FeedRow?,
    categories: CategoryActions,
    merchants: MerchantActions?,
    offerNewCategory: Boolean = true,
): RowHost {
    val categorizing = rememberSaveable { mutableStateOf<Long?>(null) }
    val chooserState = rememberUpdatedState(chooser)
    val findRowState = rememberUpdatedState(findRow)
    val categoriesState = rememberUpdatedState(categories)
    val merchantsState = rememberUpdatedState(merchants)
    val offerState = rememberUpdatedState(offerNewCategory)
    return remember(categorizing) {
        RowHost(categorizing, chooserState, findRowState, categoriesState, merchantsState, offerState)
    }
}
