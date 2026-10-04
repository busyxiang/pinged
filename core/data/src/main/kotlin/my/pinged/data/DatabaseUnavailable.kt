package my.pinged.data

/**
 * The database cannot be opened, for a reason no retry will fix on its own.
 *
 * One type for the whole family, because every caller answers it the same way:
 * record it, tell the user, and do not pretend capture is working. The two
 * cases below are indistinguishable from outside `:core:data` and were being
 * distinguished by accident -- [DatabaseKeyUnavailableException] was caught
 * everywhere and [DatabaseUnreadableException]'s underlying
 * `SQLiteNotADatabaseException` was caught nowhere.
 *
 * `IllegalStateException` for compatibility with what was here before, which
 * makes the family a subclass of `Exception`: a generic `catch (Exception)`
 * placed *inside* a guarded block swallows it before the guard sees it -- see
 * the ordering note in `ParseWorker`.
 */
sealed class DatabaseUnavailableException(message: String, cause: Throwable?) :
    IllegalStateException(message, cause)

/**
 * Spec 11.1's device-transfer state: the database file is intact and its
 * Keystore-wrapped key is gone. No amount of waiting brings it back.
 */
class DatabaseKeyUnavailableException(message: String) :
    DatabaseUnavailableException(message, null)

/**
 * The key is present and the file will not open through it.
 *
 * `DatabaseKey`'s own note says a wrong key and a corrupt page one are
 * byte-for-byte the same failure, so this is also where a genuinely corrupt
 * file lands -- along with a full disk and an IO error, both of which reach
 * the same `android.database.sqlite.SQLiteException` and mean the same thing
 * to a caller: there is nowhere to put a capture right now. A storage error
 * reading or writing the key file itself (`DatabaseKey.writeDurably`) lands
 * here too, for the same reason.
 */
class DatabaseUnreadableException(message: String, cause: Throwable) :
    DatabaseUnavailableException(message, cause)

/**
 * Spec 11.3's delete or 11.2's restore holds `Databases.whileDeleting`, and
 * there is deliberately nowhere to put a capture.
 *
 * A member of this family rather than a type of its own, so that
 * `CaptureStorage.guarded` catches it with everything else: a notification
 * arriving mid-delete or mid-restore is dropped and the banner says capture
 * stopped, which is true. A restore holds the gate across its wipe and its
 * import: measured on emulator-5554 at 517-582ms for a ledger of 10,000
 * captures and 5,000 transactions, and 2.39-2.40s at 50,000 and 25,000. The
 * alternative is worse -- see `Restore`.
 */
class DatabaseBeingDeletedException(message: String) :
    DatabaseUnavailableException(message, null)

/**
 * A closed [PingedDatabase] -- every instance `Databases.reset` has replaced
 * is one -- was asked to reach the file, or `Databases.aside` found no file
 * to open because a delete or restore had just removed it. See
 * `Databases.whileLive`.
 *
 * **Not a [DatabaseUnavailableException].** The database is fine and only
 * the handle is stale, so `CaptureStorage.guarded` filing it with the family
 * would put up a storage banner over nothing. A caller that wanted a reading
 * of that instance drops it: the file it described has been replaced.
 */
class DatabaseReplacedException(message: String) : IllegalStateException(message)
