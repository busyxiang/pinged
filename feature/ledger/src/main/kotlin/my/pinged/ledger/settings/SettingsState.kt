package my.pinged.ledger.settings

import androidx.annotation.VisibleForTesting
import my.pinged.ledger.transfer.ImportReport
import my.pinged.ledger.transfer.SalvageReport

/**
 * Section 6's four states, which decide what the screen may offer.
 *
 * [HEALTHY] is **the absence of bad news, not a clean bill of health**: the
 * check does not run at open (design 2.1), so it says only that the database
 * opened and no check has failed. The screen states when it last looked.
 */
enum class Storage {
    HEALTHY,

    /** Opened, and a check found problems. Some rows still read (design 2.3). */
    DAMAGED,

    /** `DatabaseKeyUnavailableException`: intact file, destroyed key. */
    KEY_GONE,

    /**
     * `DatabaseUnreadableException`: "a corrupt file, a full disk or a storage
     * error". The middle one is transient, which is why this state's default
     * action is to wait rather than to delete anything.
     */
    UNREADABLE,
}

/**
 * The five things [Transfers] runs, one at a time for the whole process.
 *
 * [doing] is what the screen calls one while it runs.
 */
enum class Operation(val doing: String) {
    EXPORT("Exporting"),
    CHECK("Checking"),
    RESTORE("Restoring"),
    DELETE("Deleting"),
    SALVAGE("Rescuing"),
}

/** What one transfer action is doing, so every screen state has a drawing. */
sealed interface TransferJob {
    data object Idle : TransferJob

    /** [rows] is the running count both entry points report through `onProgress`. */
    data class Running(val operation: Operation, val rows: Int) : TransferJob

    /**
     * A backup written whole. [unrecorded] is why its time could not be
     * recorded for the backup nudge, or null when it was: the file is
     * complete either way, and is kept.
     */
    data class Exported(val rows: Int, val unrecorded: String? = null) : TransferJob

    data class Restored(val report: ImportReport) : TransferJob

    data class Checked(val ok: Boolean) : TransferJob

    /** A salvage that wrote its file; [report] says what is not in it. */
    data class Salvaged(val report: SalvageReport) : TransferJob

    /** A delete that finished, cleanup and all. There is nothing to say about it. */
    data object Deleted : TransferJob

    /**
     * Not the state that moves the screen to [Storage.DAMAGED]: damage an
     * operation meets is recorded through `IntegrityStore.recordDamage`, and
     * the screen's next read says DAMAGED off that record.
     *
     * [ledgerLost] is a narrower claim than a failure, and both its writers make
     * the same one: `Wipe.everything` has already run, so unlike every other
     * [Failed] here the device is *not* as it was before this action started,
     * and nothing left on it brings the old ledger back. `restore` files it
     * for a `RestoreLedgerLostException`, where the rebuild or import after
     * the wipe failed and the chosen file is the only way back;
     * `deleteEverything` files it for anything thrown after its own wipe and
     * for the leftover that wipe returns rather than throws, where there is no
     * way back because losing the data is what the user asked for. What the
     * flag tells a screen is the part those share.
     */
    data class Failed(val message: String, val ledgerLost: Boolean = false) : TransferJob
}

/**
 * How one operation ended, kept by [Transfers] past the screen that asked for
 * it -- see [Transfers.acknowledge] for how long.
 *
 * [serial] tells two outcomes of the same [operation] apart, so a screen
 * acknowledging the one it drew cannot clear a newer one it has not.
 */
data class Outcome(val serial: Long, val operation: Operation, val job: TransferJob)

/**
 * Everything `Settings.dc.html` draws, plus the job running over it.
 *
 * [loaded] is false until the first read completes, so an empty screen is not a
 * lie -- as `SourcesState` does. [running] and [outcome] are [Transfers]'
 * rather than this screen's, mirrored in: an operation outlives the screen
 * that started it (ruling R41).
 */
data class SettingsState(
    val storage: Storage = Storage.HEALTHY,
    /**
     * Null when the count is not available, not `0` -- on a database that
     * will not fully read (any [Storage] but [Storage.HEALTHY], and a
     * [Storage.DAMAGED] one where the scan itself aborts mid-count), there is
     * no count to give. `0 ON` is a wrong answer presented with confidence,
     * and the invariant here is that no count is shown as evidence of health
     * when the database cannot support one. The row draws nothing
     * rather than a placeholder.
     */
    val sourcesOn: Int? = null,
    /**
     * What the delete sheet itemises, or null when it may not itemise
     * anything -- see [WipeCounts], and [SettingsViewModel.wipeCounts] for
     * when it is left null. Only the delete sheet reads it.
     */
    val wipeCounts: WipeCounts? = null,
    /**
     * `0` for never, and null when the store could not be read -- the row
     * then draws no value, as [sourcesOn]'s does. "NEVER" would be a claim
     * about a file nobody could open, and to a user who exported yesterday a
     * false one. The same for [lastCheckAt].
     */
    val lastExportAt: Long? = 0L,
    val lastCheckAt: Long? = 0L,
    /** [Transfers]' operation in progress, whichever screen started it. */
    val running: TransferJob.Running? = null,
    /** [Transfers]' last settled outcome not yet acknowledged. */
    val outcome: Outcome? = null,
    /** [Transfers]' lost ledger not yet acknowledged, kept past a lesser [outcome]. */
    val loss: Outcome? = null,
    /** [Transfers]' last restore or delete to reach its wipe; see [TransferState.lastWipe]. */
    val lastWipe: Outcome? = null,
    /**
     * The [lastWipe] serial [storage] was read after, `0` for none: a reading
     * whose serial is not [lastWipe]'s describes a ledger that has since been
     * replaced. See `recoveryCopy`.
     */
    val storageAsOf: Long = 0L,
    val loaded: Boolean = false,
    /**
     * How many reads this holder has published, counted up by each one
     * whatever it found, so a reader that follows settings' reads -- the
     * banner strip above it -- sees each one rather than only a change.
     */
    val reads: Long = 0L,
) {
    /** Tests only: what is running, or else what last settled, or else nothing. */
    @VisibleForTesting
    val job: TransferJob get() = running ?: outcome?.job ?: TransferJob.Idle

    /**
     * The outcome the settings screen puts in a sheet: an unseen lost ledger
     * first, whatever settled after it, then the latest outcome if it is one
     * the screen reports rather than draws elsewhere.
     */
    val reported: Outcome? get() = loss ?: outcome?.takeIf(::isReportedHere)

    /** [job], if it is [operation]'s; the export sheet draws nothing else's. */
    fun jobOf(operation: Operation): TransferJob = when {
        running?.operation == operation -> running
        outcome?.operation == operation -> outcome.job
        else -> TransferJob.Idle
    }
}

/**
 * The outcomes the settings screen reports in a sheet: a restore's or a
 * salvage's either way, a delete's or a check's only when it failed. A delete
 * that succeeded has nothing to say; a check that finished is the row value
 * beside it; an export's is the export sheet's to draw. A salvage has no sheet
 * of its own -- its action is on the recovery notice -- and what it could not
 * read is the point of reporting it.
 */
private fun isReportedHere(outcome: Outcome): Boolean = when (outcome.operation) {
    Operation.RESTORE -> outcome.job is TransferJob.Restored || outcome.job is TransferJob.Failed
    Operation.SALVAGE -> outcome.job is TransferJob.Salvaged || outcome.job is TransferJob.Failed
    Operation.DELETE, Operation.CHECK -> outcome.job is TransferJob.Failed
    Operation.EXPORT -> false
}
