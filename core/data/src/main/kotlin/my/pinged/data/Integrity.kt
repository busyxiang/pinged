package my.pinged.data

import android.content.Context
import android.database.sqlite.SQLiteException

/**
 * Spec 11.1's corruption check, and what separates a database worth salvaging
 * from one that is simply shut.
 *
 * **Not called at open**, which diverges from spec 11.1 deliberately. Measured
 * on an encrypted database, Pixel_10a emulator: 7ms at 1,000 captures, 121ms at
 * 10,000, 644ms at 50,000, against a 4ms open (`OpenTest`). `ParseWorker` runs
 * it weekly and the settings screen on demand.
 *
 * `integrity_check` and not `quick_check`: 121ms against 101ms at 10,000 rows,
 * and the cheaper pragma skips verifying index entries against table rows.
 */
object Integrity {
    /**
     * What the pragma said.
     *
     * **Two cases, and the absent third is the point.** "Will not open" is
     * decided before there is a pragma to run: [check] throws
     * `DatabaseKeyUnavailableException` or `DatabaseUnreadableException` from
     * its open and returns nothing. Anything that happens to the pragma
     * afterwards happened to a database that *is* open.
     */
    sealed interface Result {
        /** The pragma returned exactly "ok". */
        data object Ok : Result

        /**
         * Opened, and did not come back clean. Spec 11.1's "export whatever
         * still reads" state: one flipped byte left 2,200 of 5,000 captures
         * readable on a measured run.
         */
        data class Damaged(val firstProblem: String) : Result
    }

    /**
     * The pragma over `pinged.db`, on an instance of its own
     * ([Databases.aside]), never the one [Databases.shared] serves.
     *
     * **Why not the shared one.** Measured on emulator-5554, SQLCipher
     * 4.18.0: on a file with one damaged `raw_capture` leaf the pragma
     * returns a problem list without throwing, and its connection then
     * answers every later statement with code 26 -- so run on the shared
     * instance, the weekly check made the next notification, and every one
     * after it, fail to store. The instance here is closed when the pragma
     * returns, and the damage stays behind with it.
     *
     * A pragma that throws is [Result.Damaged], not a third state.
     * SQLCipher authenticates every page, so where the damaged byte lands
     * decides what this call does: on a page the check can walk past it
     * returns a problem list, and on one it cannot the pragma aborts with
     * `SQLiteException`. Both were observed on the same fixture at different
     * offsets. **The database is open either way, and rows before the damage
     * still read** -- so treating the abort as "unreadable" would withhold the
     * salvage offer from exactly the ledger that still has rows to give back.
     *
     * **A file that does not open is not a verdict** and throws what
     * [Databases.aside] throws: no file to check, a gate up, a key gone, or a
     * file that will not open through its key -- code 26 from a fresh
     * connection, which is the unreadable state, not damage found in an open
     * one.
     *
     * @throws DatabaseReplacedException if a delete or restore has removed the file.
     * @throws DatabaseUnavailableException if the file cannot be opened at all.
     */
    fun check(context: Context): Result = Databases.aside(context) { db ->
        try {
            db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
                val lines = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                if (lines.singleOrNull() == "ok") Result.Ok else Result.Damaged(lines.firstOrNull().orEmpty())
            }
        } catch (thrown: SQLiteException) {
            Result.Damaged(thrown.message ?: "the integrity check could not complete")
        }
    }
}
