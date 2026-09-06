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
 * to a caller: there is nowhere to put a capture right now.
 */
class DatabaseUnreadableException(message: String, cause: Throwable) :
    DatabaseUnavailableException(message, cause)
