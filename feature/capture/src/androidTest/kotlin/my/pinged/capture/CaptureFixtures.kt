package my.pinged.capture

import my.pinged.data.Databases
import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.entity.CaptureSource

/**
 * Shared setup for the instrumented capture tests.
 *
 * These run against the real encrypted database the app ships, not an
 * in-memory one: the invariant under test is "nothing was stored", and a
 * substitute store would prove it about the substitute.
 */
object CaptureFixtures {

    fun notification(
        context: Context,
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        subText: String? = null,
        channel: String = "capture-test",
        flags: Int = 0,
    ): Notification {
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
        title?.let { builder.setContentTitle(it) }
        text?.let { builder.setContentText(it) }
        subText?.let { builder.setSubText(it) }
        bigText?.let { builder.setStyle(Notification.BigTextStyle().bigText(it)) }
        return builder.build().also { it.flags = it.flags or flags }
    }

    fun posted(
        notification: Notification,
        pkg: String,
        id: Int = 1,
        tag: String? = null,
        postTime: Long = System.currentTimeMillis(),
    ): StatusBarNotification = StatusBarNotification(
        pkg,
        pkg,
        id,
        tag,
        /* uid = */ 0,
        /* initialPid = */ 0,
        /* score = */ 0,
        notification,
        Process.myUserHandle(),
        postTime,
    )

    /**
     * Puts [pkg] in the allow-list in the given state.
     *
     * `insertIfNew` then `setEnabled`, because `capture_source` deliberately
     * has no whole-row upsert: REPLACE would reset the user's consent to the
     * incoming object's default of false. See `CaptureSourceDao`.
     */
    fun allowList(context: Context, pkg: String, enabled: Boolean) {
        val dao = Databases.captureSourceDao(context)
        dao.insertIfNew(
            CaptureSource(pkg = pkg, label = pkg, firstSeenAt = System.currentTimeMillis()),
        )
        dao.setEnabled(pkg, enabled)
    }

    /**
     * Every place in the database where [needle] appears, as `table.column`.
     *
     * A row count says a capture was not inserted. This says the text is not
     * in the database at all -- any table, any column, including anything a
     * later migration adds -- which is the form spec 11.1's promise actually
     * takes: the app never persists content from an app the user did not
     * enable.
     */
    fun textInDatabase(context: Context, needle: String): List<String> {
        val db = Databases.shared(context).openHelper.readableDatabase
        val tables = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                "AND name NOT LIKE 'room_%'",
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }

        val hits = mutableListOf<String>()
        for (table in tables) {
            val columns = mutableListOf<String>()
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameColumn)
            }
            for (column in columns) {
                db.query(
                    "SELECT COUNT(*) FROM `$table` WHERE CAST(`$column` AS TEXT) LIKE ?",
                    arrayOf<Any?>("%$needle%"),
                ).use { cursor ->
                    if (cursor.moveToFirst() && cursor.getInt(0) > 0) hits += "$table.$column"
                }
            }
        }
        return hits
    }

    /**
     * Run a shell command through the instrumentation's UiAutomation.
     *
     * Byte-identical private copies of this lived in `ListenerTest` and
     * `RebindTest`. Shell-command quoting and the automation plumbing are the
     * two things that break when a device or an API level changes, and having
     * them in two files meant finding both.
     */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    /** Poll [condition] until it holds or the deadline passes. */
    fun waitFor(timeoutMillis: Long = 10_000L, condition: () -> Boolean): Boolean =
        waitForValue(timeoutMillis) { if (condition()) true else null } == true

    /**
     * Poll [read] until it answers non-null or the deadline passes.
     *
     * The value-returning shape of [waitFor]. `EnableToTransactionTest` had its
     * own copy of this loop for want of it, which is one more deadline and one
     * more sleep interval to keep in step with this one.
     */
    fun <T : Any> waitForValue(timeoutMillis: Long = 10_000L, read: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(100)
        }
        return read()
    }

    /**
     * Stop any queued stage-two work.
     *
     * Six identical copies of this existed. It is the guard that stops a real
     * worker waking mid-suite and writing to the shared database, so a missed
     * copy does not fail here -- it produces a flake in a different test class.
     */
    fun cancelStageTwo(context: Context) {
        runCatching {
            androidx.work.WorkManager.getInstance(context)
                .cancelUniqueWork(ParseWorker.UNIQUE_NAME)
        }
    }
}
