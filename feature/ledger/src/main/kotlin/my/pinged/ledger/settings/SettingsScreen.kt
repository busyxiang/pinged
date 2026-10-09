package my.pinged.ledger.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.util.Locale
import java.util.concurrent.TimeUnit
import my.pinged.ledger.learned.LEARNED_MERCHANTS
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.Chevron
import my.pinged.ledger.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ledger.theme.Display
import my.pinged.ledger.theme.EXPORT_EVERYTHING
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.Paper
import my.pinged.ledger.theme.RESCUE
import my.pinged.ledger.theme.ReceiptSheet
import my.pinged.ledger.theme.Separator
import my.pinged.ledger.theme.Stamp
import my.pinged.ledger.theme.dottedRule
import my.pinged.ledger.transfer.SalvageReport
import my.pinged.ledger.transfer.Unreadable

/**
 * Spec 9.5, drawn from `design/Settings.dc.html`.
 *
 * **Only the rows with a screen behind them are here.** The artboard's six
 * others -- capture health, unread notifications, pack import, categories, the
 * review threshold -- belong to milestones that do not exist, and dead rows are
 * worse than a short screen. `HOW THINGS GET SORTED` holds the one that does:
 * the learned merchants (#50), the artboard's "taught rules".
 *
 * No bottom navigation: the artboards draw a CHARTS tab that has no screen.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onOpenCorrections: () -> Unit,
    onOpenLearned: () -> Unit,
    onRescue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    // Once per foreground, keyed on the holder rather than `Unit` -- the same
    // choice `SourcesScreen` makes, and for the same reason: `MainActivity`
    // scopes this holder to its `NavEntry`
    // (`rememberViewModelStoreNavEntryDecorator`), so it survives a trip to
    // another destination. `load()` rather than a suspend call inline: this
    // effect body is not a coroutine scope, and `SettingsViewModel.refresh()`
    // is suspend.
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }

    var confirmingRestore by remember { mutableStateOf(false) }
    var confirmingWipe by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        Header(onBack)
        RunningNotice(state.running)
        RecoveryNotice(state, onRescue)
        SettingsRows(
            state = state,
            onOpenSources = onOpenSources,
            onOpenCorrections = onOpenCorrections,
            onOpenLearned = onOpenLearned,
            onExport = onExport,
            onRestore = { confirmingRestore = true },
            onCheck = viewModel::check,
            onDelete = { confirmingWipe = true },
        )
        Footer()
    }

    // Named before the picker is ever shown: spec 11.2 discards the device's
    // own ledger before it reads a byte of the chosen file, and a chooser
    // opened with that not yet said is a chooser the user cannot back out of
    // with full information.
    if (confirmingRestore) {
        RestoreConfirmSheet(
            onConfirm = {
                confirmingRestore = false
                onRestore()
            },
            onDismiss = { confirmingRestore = false },
        )
    }

    // The delete runs through the holder this screen already owns, the way
    // `onCheck = viewModel::check` does, rather than through a callback the
    // caller would have to wire to the same holder it already passed in.
    if (confirmingWipe) {
        val copyFirst = copyFirstFor(state.storage, onExport, onRescue)
        WipeSheet(
            counts = state.wipeCounts,
            onConfirm = {
                confirmingWipe = false
                viewModel.deleteEverything()
            },
            // Cleared before the export sheet opens, so the two are not
            // fighting for the screen: this one has said everything it has to
            // say, and the user is answering it by going to make a copy.
            onExportFirst = {
                confirmingWipe = false
                copyFirst?.second?.invoke()
            },
            exportFirstLabel = copyFirst?.first,
            onDismiss = { confirmingWipe = false },
        )
    }

    // Keyed off the outcome's own operation, which `Transfers` keeps until a
    // screen acknowledges it -- so a restore that finished after the screen
    // that started it went away is reported by whichever settings screen is
    // opened next, and an export's failure is never mistaken for a restore's.
    state.reported?.let { outcome ->
        OutcomeSheet(outcome = outcome, onDismiss = { viewModel.acknowledge(outcome) })
    }
}

/**
 * The confirmation [SettingsScreen] shows before [onConfirm] hands off to the
 * system file picker.
 *
 * Built on [ReceiptSheet] -- the app's one sheet chrome, see that KDoc --
 * and deliberately lighter than [WipeSheet]'s counts-and-typed-confirmation
 * shape: the words are the same claim `ImportJson` refuses a device holding
 * anything, so there is no merge that does not throw something away, and this
 * says it just as truthfully without the friction a delete earns.
 */
@Composable
private fun RestoreConfirmSheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ReceiptSheet(onDismissRequest = onDismiss) {
        Text("THIS DISCARDS EVERYTHING FIRST", style = MonoLabel, color = Stamp)
        Text(
            "Replace everything with this backup?",
            fontFamily = Display,
            fontSize = 24.sp,
            color = Ink,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            "Every transaction, notification and rule already on this phone " +
                "is discarded before the file you choose is read. There is no " +
                "merge -- restoring starts by making room.",
            fontFamily = Body,
            fontSize = 14.sp,
            color = Muted,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                "Keep what's here",
                style = MonoLabel,
                color = Muted,
                modifier = Modifier.clickable(role = Role.Button, onClick = onDismiss),
            )
            Text(
                "Continue",
                style = MonoLabel,
                color = Stamp,
                modifier = Modifier.clickable(role = Role.Button, onClick = onConfirm),
            )
        }
    }
}

/**
 * What a settled [TransferJob] looks like, shown until [onDismiss]: a restore
 * or a salvage that finished or failed, a delete that destroyed the ledger
 * without being able to finish clearing up, and a check that could not finish.
 *
 * Never composed off [TransferJob] alone: its title names the [Outcome]'s
 * operation, which one shared job field cannot say. Also built on
 * [ReceiptSheet], as [RestoreConfirmSheet] is.
 *
 * A [TransferJob.Failed] with [TransferJob.Failed.ledgerLost] set gets its
 * own, more serious title, and both of this sheet's callers can set it: the
 * restore whose rebuild failed after the wipe, and the delete whose wipe
 * succeeded and whose cleanup did not. In both the device is not as it was
 * before the action started, unlike every other failure shown here.
 */
@Composable
private fun OutcomeSheet(outcome: Outcome, onDismiss: () -> Unit) {
    ReceiptSheet(onDismissRequest = onDismiss) {
        when (val job = outcome.job) {
            is TransferJob.Restored -> {
                Text("BACKUP RESTORED", style = MonoLabel, color = Muted)
                Text(
                    "${grouped(job.report.rows)} rows are back on this phone.",
                    fontFamily = Body,
                    fontSize = 15.sp,
                    color = Ink,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            is TransferJob.Salvaged -> {
                Text("RESCUED WHAT COULD BE READ", style = MonoLabel, color = Muted)
                salvageSentences(job.report).forEach { sentence ->
                    Text(
                        sentence,
                        fontFamily = Body,
                        fontSize = 15.sp,
                        color = Ink,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            is TransferJob.Failed -> {
                Text(
                    when {
                        job.ledgerLost -> "YOUR LEDGER IS GONE"
                        outcome.operation == Operation.CHECK -> "CHECK DID NOT FINISH"
                        outcome.operation == Operation.SALVAGE -> "RESCUE DID NOT FINISH"
                        else -> "RESTORE DID NOT FINISH"
                    },
                    style = MonoLabel,
                    color = Stamp,
                )
                Text(
                    job.message,
                    fontFamily = Body,
                    fontSize = 15.sp,
                    color = Ink,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            else -> {}
        }
        Text(
            "OK",
            style = MonoLabel,
            color = Muted,
            modifier = Modifier
                .padding(top = 20.dp)
                .clickable(role = Role.Button, onClick = onDismiss),
        )
    }
}

/**
 * What the screen says above the rows when the database is not healthy.
 *
 * Section 6's table decides what is *offered*; this is what is *said*. Nothing
 * in [Storage.UNREADABLE] may read as "your data is gone", and nothing there may
 * name the key: a full disk lands there and clears on its own.
 *
 * On [Paper], as the rows below it are; see [SettingsRow].
 */
@Composable
internal fun RecoveryNotice(state: SettingsState, onRescue: () -> Unit) {
    val (label, body) = recoveryCopy(state) ?: return
    Column(Modifier.fillMaxWidth().background(Paper).padding(20.dp)) {
        Text(label, style = MonoLabel, color = Stamp)
        Text(body, fontFamily = Body, fontSize = 14.sp, color = Ink, modifier = Modifier.padding(top = 8.dp))
        // Section 6's one action urged first, and Damaged's only way to a
        // file: Export everything is not offered there. Only beside this
        // copy, which `recoveryCopy` withholds while anything runs.
        if (state.storage == Storage.DAMAGED) {
            Text(
                RESCUE,
                style = MonoLabel,
                color = Stamp,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .clickable(role = Role.Button, onClick = onRescue),
            )
        }
    }
}

/** The delete sheet's offer of a copy first, on a healthy ledger. */
internal const val EXPORT_FIRST = "Export it first"

/** [EXPORT_FIRST] on a damaged ledger, which has no export to offer. */
internal const val RESCUE_FIRST = "Rescue what can still be read first"

/**
 * The delete sheet's offer of a copy first, and where it leads, by section
 * 6's table: an export where the ledger is healthy, the rescue where it is
 * damaged, and **nothing where neither can work**. In [Storage.KEY_GONE] and
 * [Storage.UNREADABLE] the database will not open, so an export throws at
 * its first read and leaves a deleted file and a failure, two taps from the
 * notice that says nothing here can be read.
 */
internal fun copyFirstFor(storage: Storage, onExport: () -> Unit, onRescue: () -> Unit): Pair<String, () -> Unit>? =
    when (storage) {
        Storage.HEALTHY -> EXPORT_FIRST to onExport
        Storage.DAMAGED -> RESCUE_FIRST to onRescue
        Storage.KEY_GONE, Storage.UNREADABLE -> null
    }

/**
 * [RecoveryNotice]'s label and body for [state], or null for none.
 *
 * **Nothing while an operation runs, or while [SettingsState.storage] is
 * older than the last restore or delete.** Every sentence here describes the
 * database as a read found it, and a restore or a delete replaces it: a
 * restore from [Storage.KEY_GONE] would otherwise spend its import, and the
 * moment between publishing its result and the read after it, under "the
 * database is intact". [SettingsState.storageAsOf] says which one the read
 * came after; the result is published with `Transfers`' turn still held, so
 * a read can never have seen the ledger without seeing the result too.
 *
 * **"Nothing has been deleted" and "the database is intact" only where this
 * process has not deleted it.** Both are claims about history, which a read
 * of storage cannot make: after a restore that lost the ledger, a full disk
 * failing the read files [Storage.UNREADABLE], and the claim would stand
 * behind -- and after -- "YOUR LEDGER IS GONE". [SettingsState.lastWipe]
 * outlives acknowledging that sheet for this reason. Across process death it
 * is gone, and the claim can come back over a ledger a previous process
 * lost; the next process has no record of that loss to contradict.
 */
internal fun recoveryCopy(state: SettingsState): Pair<String, String>? {
    if (state.running != null) return null
    if (state.storageAsOf != (state.lastWipe?.serial ?: 0L)) return null
    val deletedHere = state.loss != null ||
        (state.lastWipe != null && state.lastWipe.job !is TransferJob.Restored)
    return when (state.storage) {
        Storage.HEALTHY -> null
        Storage.DAMAGED ->
            ("DAMAGED" + Separator + "SOME OF THIS CAN STILL BE READ") to
                "Part of Pinged's database is unreadable. What is still readable " +
                "can be written to a file and restored afterwards, and doing that " +
                "first loses the least."
        Storage.KEY_GONE ->
            (CANNOT_READ_YOUR_DATA + Separator + "THE KEY IS GONE") to if (deletedHere) {
                "The key that unlocks Pinged's database is not on this phone, so " +
                    "nothing in it can be read, and the key cannot be recovered -- " +
                    "Android does not let it leave the phone it was made on."
            } else {
                "The database is intact and the key that unlocks it is not on this " +
                    "phone. This happens after some device-to-device transfers, and " +
                    "the key cannot be recovered -- Android does not let it leave the " +
                    "phone it was made on. Nothing here has been deleted, and nothing " +
                    "here can be read."
            }
        Storage.UNREADABLE ->
            CANNOT_READ_YOUR_DATA to if (deletedHere) {
                "Pinged could not open its database. This is sometimes a full disk " +
                    "or a storage error, so check your phone's storage and try again " +
                    "before replacing or deleting anything."
            } else {
                "Pinged could not open its database. This is sometimes a full disk " +
                    "or a storage error rather than lost data, so check your phone's " +
                    "storage and try again before replacing or deleting anything. " +
                    "Nothing has been deleted."
            }
    }
}

/**
 * What the screen says while `Transfers` runs an operation, whichever
 * settings screen asked for it -- a restore outlives the screen that started
 * it (ruling R41), so one opened over it has to say so rather than draw an
 * ordinary ledger whose rows do nothing.
 *
 * Only what is true of the operation while it runs: a restore gates capture
 * for its wipe and import, and a notification arriving then is dropped
 * (`DatabaseBeingDeletedException`'s KDoc).
 */
@Composable
internal fun RunningNotice(running: TransferJob.Running?) {
    if (running == null) return
    val body = when (running.operation) {
        Operation.RESTORE ->
            "Pinged is replacing everything on this phone with your backup. A " +
                "notification that arrives before this finishes may not be recorded."
        Operation.DELETE -> "Pinged is deleting everything on this phone."
        Operation.EXPORT -> "Pinged is writing your backup to the file you chose."
        Operation.CHECK -> "Pinged is reading its whole database to check it."
        Operation.SALVAGE ->
            "Pinged is writing everything it can still read to the file you chose. " +
                "Where its database is damaged this goes a row at a time."
    }
    val label = running.operation.doing.uppercase() + if (running.rows > 0) Separator + "${grouped(running.rows)} ROWS" else ""
    Column(Modifier.fillMaxWidth().background(Paper).padding(20.dp)) {
        Text(label, style = MonoLabel, color = Stamp)
        Text(body, fontFamily = Body, fontSize = 14.sp, color = Ink, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * The rows alone, so a test can drive section 6's states with no database.
 *
 * **A chevron marks where a tap leads, so it is not painted uniformly.**
 * "Capture sources" pushes a screen; export and restore each hand off to a
 * system document picker -- a sheet as much as a screen is -- and deleting
 * everything is irreversible enough that it needs a confirmation surface of
 * its own before it runs. "Check my data" alone finishes on this screen,
 * updating only the value beside its own label, so it alone draws no chevron.
 *
 * **The four transfer rows do nothing while one runs.** `Transfers` would
 * refuse the request anyway; a row that looks live and is not is the
 * invisible queued tap the running notice above replaces.
 */
@Composable
internal fun SettingsRows(
    state: SettingsState,
    onOpenSources: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onCheck: () -> Unit,
    onDelete: () -> Unit,
    onOpenCorrections: () -> Unit,
    onOpenLearned: () -> Unit,
) {
    val readable = state.storage == Storage.HEALTHY || state.storage == Storage.DAMAGED
    val idle = state.running == null
    // Design 6 gives Damaged no Export: its first damaged page throws and
    // leaves nothing. The recovery notice's rescue is the way to a file there.
    val exportable = state.storage == Storage.HEALTHY

    SectionLabel("CAPTURE")
    SettingsRow(
        "Capture sources",
        // No placeholder when the count is unavailable (`SettingsState.sourcesOn`'s
        // KDoc): a blank value is honest, a substituted one is not.
        value = state.sourcesOn?.let { "${grouped(it)} ON" },
        onClick = onOpenSources,
        chevron = true,
    )
    // The section's last row -- the rest of the artboard's belong to
    // milestones that do not exist -- so it draws no divider, for the same
    // reason `Delete everything` draws none below.
    SettingsRow(
        "Corrections from pack updates",
        // Blank, not NONE, while the sweep is unfinished or unreadable; see
        // `SettingsState.correctionsPending`.
        value = state.correctionsPending?.let { if (it == 0) "NONE" else "${grouped(it)} TO REVIEW" },
        onClick = onOpenCorrections,
        chevron = true,
        divider = false,
    )

    SectionLabel("HOW THINGS GET SORTED")
    // The count is the list's length, so the two cannot disagree; see
    // `SettingsState.learnedCount`. The section's only row, so no divider.
    SettingsRow(
        LEARNED_MERCHANTS,
        value = state.learnedCount?.let { grouped(it) },
        onClick = onOpenLearned,
        chevron = true,
        divider = false,
    )

    SectionLabel("YOUR DATA")
    if (exportable) {
        SettingsRow(
            label = EXPORT_EVERYTHING,
            caption = "EVERY TRANSACTION AND THE NOTIFICATIONS BEHIND THEM",
            value = state.lastExportAt?.let { ago(it, never = "NEVER") },
            onClick = onExport,
            enabled = idle,
            chevron = true,
        )
    }
    SettingsRow(
        label = "Replace everything from a backup",
        caption = "DISCARDS WHAT IS HERE FIRST",
        onClick = onRestore,
        enabled = idle,
        chevron = true,
    )
    if (readable) {
        SettingsRow(
            label = "Check my data",
            value = state.lastCheckAt?.let { ago(it, never = "NEVER CHECKED") },
            onClick = onCheck,
            enabled = idle,
        )
    }
    // Always last in this section, so it alone never draws the divider below
    // it -- there is nothing under it to separate from.
    SettingsRow(
        label = "Delete everything",
        onClick = onDelete,
        enabled = idle,
        tint = Stamp,
        chevron = true,
        divider = false,
    )
}

/**
 * What a salvage's sheet says, a sentence each, and only what is true of the
 * file: one that silently omits rows is worse than none, because the user
 * restores it and believes it (design 5).
 *
 * - **[Unreadable.Counted] adds up**: "Recovered R of R+U; U could not be
 *   read". [Unreadable.AtMost] is a bound and never drawn as a total: "up to
 *   N", with no "of".
 * - **`txn`'s recovered figure is transactions read**, the ones left out for
 *   a lost capture among them, so it is never called rows in the file; the
 *   left-out count is its own sentence.
 * - **"Could not be read" only of what could not**: a row naming one that
 *   arrived after the rescue began says so instead ([SalvageReport]).
 * - A table that lost nothing has no line.
 * - Numbers grouped as the rest of the app prints them ([grouped]).
 */
internal fun salvageSentences(report: SalvageReport): List<String> = buildList {
    add("${count(report.rows, "row is", "rows are")} in the file.")
    for ((table, nouns) in SALVAGED_TABLES) {
        when (val loss = report.unreadable[table]) {
            null -> {}
            is Unreadable.Counted -> {
                val total = loss.recovered + loss.unread
                add(
                    "Recovered ${grouped(loss.recovered)} of ${count(total, nouns.first, nouns.second)}; " +
                        "${grouped(loss.unread)} could not be read.",
                )
            }
            is Unreadable.AtMost ->
                add("Recovered ${count(loss.recovered, nouns.first, nouns.second)}; up to ${grouped(loss.ids)} could not be read.")
        }
    }
    if (report.txnsLeftOut > 0) {
        add(
            "${count(report.txnsLeftOut, "transaction was", "transactions were")} left out, because " +
                "the notification behind each could not be read.",
        )
    }
    if (report.txnsReread > 0) {
        val one = report.txnsReread == 1
        add(
            "${count(report.txnsReread, "transaction", "transactions")} that could not be read " +
                "will be read again from ${if (one) "its notification" else "their notifications"} " +
                "after you restore this file; any ${if (one) "change" else "changes"} you made to " +
                "${if (one) "it is" else "them are"} not in the file.",
        )
    }
    if (report.txnsLeftOutSince > 0) {
        add(
            "${count(report.txnsLeftOutSince, "transaction", "transactions")} recorded after the rescue " +
                "started ${wasOrWere(report.txnsLeftOutSince)} left out, with the notification behind " +
                "${if (report.txnsLeftOutSince == 1) "it" else "them"}.",
        )
    }
    copyMarkCleared(report.linksCleared, "could not be read")
    copyMarkCleared(report.linksClearedSince, "arrived after the rescue started")
    copyMarkCleared(report.linksUnheld, "came after ${if (report.linksUnheld == 1) "it" else "them"}")
    if ("category" in report.lostWhole) add("Your categories could not be read, so the file has the standard ones.")
    categoryLost(report.txnsUncategorized, "transaction is", "transactions are", "filed under Uncategorized", "could not be read")
    categoryLost(
        report.txnsUncategorizedSince,
        "transaction is",
        "transactions are",
        "filed under Uncategorized",
        "was made after the rescue started",
    )
    categoryLost(report.rulesLeftOut, "learned merchant was", "learned merchants were", "left out", "could not be read")
    categoryLost(
        report.rulesLeftOutSince,
        "learned merchant was",
        "learned merchants were",
        "left out",
        "was made after the rescue started",
    )
    if (report.uncategorizedAdded) {
        add("The file adds an Uncategorized category, which Pinged needs and this ledger did not have.")
    }
    if ("capture_source" in report.lostWhole) {
        add("The list of apps Pinged reads could not be read, so choose them again after restoring.")
    }
    if ("capture_day" in report.lostWhole) add("Pinged's record of which days it was listening could not be read.")
    if ("merchant_alias" in report.lostWhole) {
        add("Which merchants you marked as the same shop could not be read, so mark them again after restoring.")
    }
    if ("merchant_name" in report.lostWhole) {
        add("The names you gave merchants could not be read, so they show the names the banks sent.")
    }
    add("To start again from this file, use Replace everything from a backup and choose it.")
}

/** The paged tables in the order the sheet names them, each with its noun. */
private val SALVAGED_TABLES = listOf(
    "raw_capture" to ("notification" to "notifications"),
    "txn" to ("transaction" to "transactions"),
    "merchant_rule" to ("learned merchant" to "learned merchants"),
)

/** [n] notifications kept without the copy mark naming one that [that], if any were. */
private fun MutableList<String>.copyMarkCleared(n: Int, that: String) {
    if (n > 0) {
        add("${count(n, "notification", "notifications")} marked as a copy of one that $that ${isOrAre(n)} kept, without that mark.")
    }
}

/** [n] rows that went [where] because their category [why], if any did. */
private fun MutableList<String>.categoryLost(n: Int, one: String, many: String, where: String, why: String) {
    if (n > 0) add("${count(n, one, many)} $where, because ${itsOrTheir(n)} category $why.")
}

private fun count(n: Number, one: String, many: String) = "${grouped(n)} ${if (n.toLong() == 1L) one else many}"

private fun isOrAre(n: Int) = if (n == 1) "is" else "are"

private fun wasOrWere(n: Int) = if (n == 1) "was" else "were"

private fun itsOrTheir(n: Int) = if (n == 1) "its" else "their"

/**
 * A count as this app prints one everywhere: grouped in threes with a comma,
 * whatever the device's locale, as `WipeSheet`'s counts, `SourcesScreen`'s
 * seen line and the ledger's amounts are. In [Storage.DAMAGED] the delete
 * sheet and the rescue's report sit one tap apart, and "50,000" beside
 * "49981" reads as two different ledgers.
 */
internal fun grouped(n: Number): String = String.format(Locale.ROOT, "%,d", n.toLong())

/**
 * How long ago, in the artboard's voice -- `LIVE, 2 MIN AGO` is the pattern.
 *
 * Coarse on purpose: the reader wants to know whether a copy is recent, and a
 * timestamp to the minute invites precision into what is a reminder.
 */
internal fun ago(at: Long, never: String, now: Long = System.currentTimeMillis()): String {
    if (at <= 0L) return never
    val days = TimeUnit.MILLISECONDS.toDays(now - at)
    return when {
        days <= 0L -> "TODAY"
        days == 1L -> "YESTERDAY"
        days < 30L -> "$days DAYS AGO"
        else -> "OVER A MONTH AGO"
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MonoLabel,
        color = Muted,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * One settings row, on [Paper] with a [dottedRule] underneath rather than a
 * [my.pinged.ledger.theme.Card] fill.
 *
 * `SourcesScreen` reserves `Card` for the ground *behind* its list -- the
 * gutter a lazy item cannot inherit -- and paints every row itself in
 * `Paper`, separated by the perforated rule that is this app's visual
 * signature (see `UpperRow` and `SourceListRow` there). A settings row
 * filled solid in `Card` would be a second screen inventing its own idiom
 * for the same artboard language.
 *
 * @param tint overrides [Ink] for a destructive label, and the chevron beside
 *   it, so "Delete everything" cannot be mistaken for a row like any other.
 * @param divider false only for a section's last row, which has nothing
 *   below it to separate from.
 * @param enabled false draws the row [Muted] and ignores taps.
 */
@Composable
private fun SettingsRow(
    label: String,
    caption: String? = null,
    value: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Ink,
    chevron: Boolean = false,
    divider: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Paper)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (divider) Modifier.dottedRule(atTop = false) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontFamily = Body, fontSize = 15.sp, color = if (enabled) tint else Muted)
            if (caption != null) Text(caption, style = MonoLabel, color = Muted)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (value != null) Text(value, style = MonoLabel, color = Muted)
            if (chevron) {
                Chevron(
                    pointsRight = true,
                    size = 17.dp,
                    strokeWidth = 1.7.dp,
                    tint = if (enabled && tint == Stamp) Stamp else Muted,
                )
            }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Back",
            style = MonoLabel,
            color = Muted,
            modifier = Modifier.clickable(role = Role.Button, onClick = onBack),
        )
        Text(
            "Settings",
            fontFamily = Display,
            fontWeight = FontWeight.Normal,
            fontSize = 24.sp,
            color = Ink,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** The artboard's footer. Both halves are true, and worth saying. */
@Composable
private fun Footer() {
    Text(
        "NO INTERNET PERMISSION" + Separator + "NOTHING LEAVES THIS PHONE",
        style = MonoLabel,
        color = Muted,
        modifier = Modifier.padding(20.dp),
    )
}
