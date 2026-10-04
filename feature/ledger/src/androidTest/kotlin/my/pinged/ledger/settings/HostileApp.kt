package my.pinged.ledger.settings

import android.app.Application
import java.io.File
import my.pinged.data.DatabaseFactory

/**
 * The real application, except that [getDatabasePath] refuses the app's own
 * database once [refusing] is set.
 *
 * **Where the refusal lands is the whole point.** `DatabaseKey.destroy` calls
 * `getDatabasePath` on the line after `deleteDatabase`, so an armed instance
 * makes a delete get exactly as far as destroying the ledger and no further --
 * which is where the failures `Transfers.deleteEverything` and
 * `Restore.replaceEverything` guard against actually live (a Keystore entry
 * invalidated by an OTA, a DataStore write that cannot land). Injecting it
 * there rather than simulating it is what lets
 * `TransferJob.Failed.ledgerLost` and `RestoreLedgerLostException` be
 * asserted as true claims.
 *
 * **[DatabaseFactory.NAME] only, not every name.** `Restore`'s probe opens a
 * throwaway database through `DatabaseFactory.buildAt`, and SQLCipher resolves
 * even that absolute path through this same call (see `builderAt`'s KDoc). An
 * instance refusing every name would fail the restore at its probe, before
 * anything is destroyed -- which is the opposite of the state under test.
 *
 * A switch rather than a refusal from birth: Room resolves the database file
 * through this call when it opens, so a screen-driving test has to load
 * before arming it.
 *
 * `attachBaseContext` from `init` is the only way to wrap an `Application`;
 * `AndroidViewModel` will not take a plain `ContextWrapper`.
 */
internal class HostileApp(base: Application) : Application() {
    init {
        attachBaseContext(base)
    }

    var refusing: Boolean = false

    /**
     * What the refusal throws.
     *
     * A `CancellationException` here is the shape a cancelled suspend call
     * takes inside `Wipe.everything` -- the same substitution
     * `ExportCleanupTest`'s `CancellingSink` makes for the export path, and
     * for the same reason: racing a real `cancel()` against a wipe that takes
     * a few milliseconds pins nothing repeatably.
     */
    var refusalIs: () -> Exception = { IllegalStateException("simulated failure, for the test") }

    /**
     * Called with the count of each resolution of the app's own database, from
     * one, before it is answered: for a test that has to act at an exact point
     * in a delete or a restore and let it carry on. In a restore the first is
     * `DatabaseKey.destroy`'s, with the ledger just unlinked; the second is
     * `DatabaseKey.rawKeyPassphrase` asking, for the restore's own open,
     * whether a database exists beside a key that does not.
     */
    var atResolution: ((Int) -> Unit)? = null

    private var resolutions = 0

    override fun getDatabasePath(name: String): File {
        if (name == DatabaseFactory.NAME) {
            atResolution?.invoke(++resolutions)
            if (refusing) throw refusalIs()
        }
        return super.getDatabasePath(name)
    }
}
