package my.pinged.ledger.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.data.dao.CategoryUse
import my.pinged.ledger.home.CategorySheet
import my.pinged.ledger.home.NEW_CATEGORY
import my.pinged.ledger.home.categoryIcon
import my.pinged.ledger.settings.grouped
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PlusMark
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedRule

/** The settings row's label and this screen's title. */
const val CATEGORIES = "Categories"

/** Uncategorized's row says why it opens nothing. */
internal const val PROTECTED_NOTE = "PINGED FILES WHAT IT CANNOT PLACE HERE"

/** A row's count, in the words the sheet's reach uses. */
internal fun transactionsLabel(n: Int): String = if (n == 1) "1 TRANSACTION" else "${grouped(n)} TRANSACTIONS"

/**
 * Spec 9.5's "Categories: add, rename, delete-if-unused" (#155), pushed from
 * its row in Settings. Every category with what is filed there, a tap opening
 * spec 4's sheet, and "New category" at the foot.
 *
 * Read once per foreground, as `LearnedScreen` is, so a payment filed
 * elsewhere shows in the counts on return.
 */
@Composable
fun CategoriesScreen(viewModel: CategoriesViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    CategoriesContent(
        state = state,
        onBack = onBack,
        onAdd = viewModel::add,
        onEdit = viewModel::edit,
        onDelete = viewModel::delete,
        modifier = modifier,
    )
}

/**
 * [CategoriesScreen] without its holder, so a test can draw any state.
 *
 * Which sheet is open is kept as an id and found among [state]'s rows, so a
 * sheet after a process death opens on what the database now says, and one
 * whose category has gone opens nothing.
 */
@Composable
internal fun CategoriesContent(
    state: CategoriesState,
    onBack: () -> Unit,
    onAdd: (name: String, iconKey: String) -> Unit,
    onEdit: (categoryId: Long, name: String, iconKey: String) -> Unit,
    onDelete: (categoryId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize().background(Paper)) {
        item { Header(onBack) }
        item {
            val notice = if (state.loaded && state.storageUnavailable) CANNOT_READ_YOUR_DATA else null
            if (notice != null) {
                Text(notice, fontFamily = Body, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        items(state.items, key = { it.category.id }) { use ->
            CategoryRow(use, onOpen = if (use.category.isProtected) null else ({ editing = use.category.id }))
        }
        if (state.loaded && !state.storageUnavailable) {
            item { NewCategoryRow { adding = true } }
        }
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
        )
    }
}

/**
 * One category: its icon, its name, and how many transactions are filed
 * there, the reach a rename will have. [onOpen] null is Uncategorized, which
 * spec 4 lets no one rename, re-icon or delete: no chevron, no tap, and a line
 * saying what the row is for instead.
 */
@Composable
private fun CategoryRow(use: CategoryUse, onOpen: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier)
            .dottedRule(atTop = false)
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = categoryIcon(use.category.iconKey)
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(use.category.name, fontFamily = Body, fontSize = 15.sp, color = Ink)
            Text(
                if (onOpen == null) PROTECTED_NOTE else transactionsLabel(use.transactions),
                style = MonoLabel,
                color = Muted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (onOpen != null) {
            // Faint: a mark, not text, so 1.4.11's 3:1 is its bar, as Settings' rows draw it.
            Chevron(pointsRight = true, size = 17.dp, strokeWidth = 1.7.dp, tint = Faint)
        }
    }
}

/** The foot of the list, in [Stamp] as an offer, with the chooser's plus. */
@Composable
private fun NewCategoryRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlusMark(size = 18.dp, strokeWidth = 1.6.dp, tint = Stamp)
        Text(NEW_CATEGORY, fontFamily = Body, fontSize = 15.sp, color = Stamp)
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Back",
            style = ButtonLabel.copy(fontSize = 14.sp),
            color = Muted,
            modifier = Modifier.clickable(role = Role.Button, onClick = onBack).heightIn(min = 44.dp).wrapContentHeight(),
        )
        Text(
            CATEGORIES,
            fontFamily = Display,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            color = Ink,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
