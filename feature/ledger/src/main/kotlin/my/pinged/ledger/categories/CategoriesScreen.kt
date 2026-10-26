package my.pinged.ledger.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.dao.CategoryUse
import my.pinged.ledger.ChangedLine
import my.pinged.ledger.ListNotReadYet
import my.pinged.ledger.home.CategorySheet
import my.pinged.ledger.home.NEW_CATEGORY
import my.pinged.ledger.home.categoryIcon
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.grouped
import my.pinged.read.rememberReadState
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.REPLACING_YOUR_DATA
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PlusMark
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dashedOutline
import my.pinged.ui.theme.dottedRule

/** The settings row's label and this screen's title. */
const val CATEGORIES = "Categories"

/** The back chevron, to TalkBack. */
const val CATEGORIES_BACK = "Back to Settings"

/** Uncategorized's row says, after its count, why it opens nothing. */
internal const val PROTECTED_NOTE = "CANNOT BE RENAMED OR DELETED"

/** A row's count, in the words the sheet's reach uses. */
internal fun transactionsLabel(n: Int): String = if (n == 1) "1 TRANSACTION" else "${grouped(n)} $TRANSACTIONS"

private const val TRANSACTIONS = "TRANSACTIONS"

/** The folded row's words, for [n] categories nothing is filed under. */
internal fun emptyFoldLabel(n: Int): String = "${grouped(n)} WITH NOTHING IN THEM YET"

/**
 * `design/Categories.dc.html`'s three parts, from `CategoryDao.uses()`'s
 * `sort_order` list: what is used, most used first; the protected rows,
 * listed whatever they hold since they open nothing and so cannot sit in a
 * fold that opens to rows; and the rest, still in `sort_order`.
 *
 * A stable sort, so a tie keeps `sort_order` and a list with equal counts
 * reads as it did before anything was filed.
 */
internal class Arranged(items: List<CategoryUse>) {
    private val open = items.filterNot { it.category.isProtected }
    val used: List<CategoryUse> = open.filter { it.transactions > 0 }.sortedByDescending { it.transactions }
    val closed: List<CategoryUse> = items.filter { it.category.isProtected }
    val empty: List<CategoryUse> = open.filter { it.transactions == 0 }
}

/**
 * Spec 9.5's "Categories: add, rename, delete-if-unused" (#155), pushed from
 * its row in Settings, drawn as `design/Categories.dc.html` (#233). Every
 * category with what is filed there, as [Arranged] orders it, a tap opening
 * spec 4's sheet, and "New category" at the foot.
 *
 * Read on each foreground, so a payment filed elsewhere shows in the counts
 * on return, and again when a restore or a delete replaces the ledger while it
 * is open.
 */
@Composable
fun CategoriesScreen(viewModel: CategoriesViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // Reads on each resume and on `Databases.rewrites`, through `rememberReadState`.
    val read = viewModel.read.rememberReadState()
    val busy by viewModel.busy.collectAsState()
    CategoriesContent(
        state = read.toState(),
        onBack = onBack,
        onAdd = viewModel::add,
        onEdit = viewModel::edit,
        onDelete = viewModel::delete,
        modifier = modifier,
        busy = busy,
    )
}

/**
 * [CategoriesScreen] without its holder, so a test can draw any state.
 *
 * Which sheet is open is kept as an id and found among [state]'s rows, so a
 * sheet after a process death opens on what the database now says, and one
 * whose category has gone opens nothing.
 *
 * While [busy] runs a ledger replacement the list says so at its top and
 * New category is off (#331); a row still opens its sheet, which says so
 * too and saves and deletes nothing.
 */
@Composable
internal fun CategoriesContent(
    state: CategoriesState,
    onBack: () -> Unit,
    onAdd: (name: String, iconKey: String) -> Unit,
    onEdit: (categoryId: Long, name: String, iconKey: String) -> Unit,
    onDelete: (categoryId: Long) -> Unit,
    modifier: Modifier = Modifier,
    busy: Operation? = null,
) {
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var unfolded by rememberSaveable { mutableStateOf(false) }
    val usable = state.loaded && !state.storageUnavailable && !state.replacing
    val arranged = Arranged(state.items)

    Column(modifier.fillMaxSize().background(Paper)) {
        Header(count = if (usable) state.items.size else null, reading = !state.loaded, onBack = onBack)
        if (!state.loaded) {
            ListNotReadYet(caption = "$NOT_READ_YET $TRANSACTIONS", modifier = Modifier.weight(1f))
            return@Column
        }
        LazyColumn(Modifier.weight(1f).padding(top = 14.dp)) {
            if (usable && busy != null) item { ChangedLine(busy, PaddingValues(start = 20.dp, end = 20.dp, bottom = 8.dp)) }
            item {
                val notice = when {
                    state.replacing -> REPLACING_YOUR_DATA
                    state.storageUnavailable -> CANNOT_READ_YOUR_DATA
                    else -> null
                }
                if (notice != null) {
                    Text(notice, fontFamily = Body, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            items(arranged.used, key = { it.category.id }) { use ->
                CategoryRow(use, onOpen = { editing = use.category.id })
            }
            items(arranged.closed, key = { it.category.id }) { use -> CategoryRow(use, onOpen = null) }
            if (arranged.empty.isNotEmpty()) {
                item(key = FOLD_KEY) { Fold(arranged.empty, unfolded) { unfolded = !unfolded } }
                if (unfolded) {
                    items(arranged.empty, key = { it.category.id }) { use ->
                        CategoryRow(use, onOpen = { editing = use.category.id })
                    }
                }
            }
        }
        // Pinned below the list, as the board draws it, so the offer is on
        // screen however long the list grows.
        if (usable) NewCategoryOffer(enabled = busy == null) { adding = true }
    }

    val open = editing?.let { id -> state.items.firstOrNull { it.category.id == id } }
    if (open != null) {
        CategorySheet(
            existing = open.category,
            transactions = open.transactions,
            rules = open.rules,
            otherNames = state.items.filter { it.category.id != open.category.id }.map { it.category.name },
            onSave = { name, icon ->
                editing = null
                onEdit(open.category.id, name, icon)
            },
            onDelete = {
                editing = null
                onDelete(open.category.id)
            },
            onDismiss = { editing = null },
            busy = busy,
        )
    }
    if (adding) {
        CategorySheet(
            existing = null,
            transactions = 0,
            rules = 0,
            otherNames = state.items.map { it.category.name },
            onSave = { name, icon ->
                adding = false
                onAdd(name, icon)
            },
            onDelete = null,
            onDismiss = { adding = false },
            busy = busy,
        )
    }
}

/**
 * One category: its icon, its name, and how many transactions are filed
 * there, the reach a rename will have. [onOpen] null is Uncategorized, which
 * spec 4 lets no one rename, re-icon or delete: no chevron, no tap, and the
 * count followed by why.
 */
@Composable
private fun CategoryRow(use: CategoryUse, onOpen: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .dottedRule(atTop = false)
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = categoryIcon(use.category.iconKey)
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(use.category.name, fontFamily = Body, fontSize = 15.sp, color = if (onOpen == null) Muted else Ink)
            Text(
                transactionsLabel(use.transactions) + if (onOpen == null) Separator + PROTECTED_NOTE else "",
                style = MonoLabel,
                color = Muted,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        if (onOpen != null) {
            // Faint: a mark, not text, so 1.4.11's 3:1 is its bar, as Settings' rows draw it.
            Chevron(pointsRight = true, size = 17.dp, strokeWidth = 1.7.dp, tint = Faint)
        }
    }
}

/**
 * The categories nothing is filed under, folded into one row that names
 * them, so a fresh install's fifteen do not bury the few in use. Open, the
 * names give way to their own rows below it, each opening its sheet.
 */
@Composable
private fun Fold(empty: List<CategoryUse>, unfolded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .heightIn(min = 44.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(emptyFoldLabel(empty.size), style = MonoLabel, color = Muted, modifier = Modifier.weight(1f))
            // A chevron turned to point down, and up once open.
            Box(Modifier.rotate(if (unfolded) -90f else 90f)) {
                Chevron(pointsRight = true, size = 15.dp, strokeWidth = 1.8.dp, tint = Muted)
            }
        }
        if (!unfolded) {
            Text(
                // Uppercased in the string, as Sources' header is, so what is
                // read out is what is drawn.
                empty.joinToString(Separator) { it.category.name.uppercase() },
                style = MonoLabel,
                color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/** The foot, a dashed [Stamp] box: an offer, as the ledger's "+ CATEGORY" chip is; [Muted] and off unless [enabled]. */
@Composable
private fun NewCategoryOffer(enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) Stamp else Muted
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 26.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .dashedOutline(ink)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
    ) {
        PlusMark(size = 17.dp, strokeWidth = 1.9.dp, tint = ink)
        Text(NEW_CATEGORY, fontFamily = Body, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = ink)
    }
}

/**
 * The back chevron beside the serif title, and under it how many categories
 * there are and in what order. [count] null until a read has said, so a list
 * not read yet claims no number: while [reading], the count is a dash.
 */
@Composable
private fun Header(count: Int?, reading: Boolean, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 20.dp), verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .size(44.dp)
                .semantics { contentDescription = CATEGORIES_BACK },
            contentAlignment = Alignment.Center,
        ) {
            Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
        }
        Column(Modifier.padding(bottom = 4.dp)) {
            Text(CATEGORIES, fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 25.sp, color = Ink)
            val order = @Composable { n: String -> Text("$n$Separator$MOST_USED_FIRST", style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 2.dp)) }
            when {
                count != null -> order(grouped(count))
                reading -> ReadingLayout { order(NOT_READ_YET) }
            }
        }
    }
}

private const val MOST_USED_FIRST = "MOST USED FIRST"

/** Not a category id: those are positive, and this is a string. */
private const val FOLD_KEY = "fold"
