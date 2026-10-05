package my.pinged

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Design 6's damaged state as the user meets it: settings offers the rescue
 * and no export, the rescue opens the system's save-file picker, and the file
 * it writes is reported in words that add up.
 *
 * The picker is the system's, so it is answered here by an
 * `Instrumentation.ActivityMonitor` that intercepts `ACTION_CREATE_DOCUMENT`
 * and hands back a file in this app's cache -- `DocumentSink` opens a `file:`
 * URI through the same `ContentResolver` call it opens a provider's with.
 */
@RunWith(AndroidJUnit4::class)
class RescueFromSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val picked = File(context.cacheDir, "rescued-from-settings.json")

    /** Each picker opened, and what it answers: the file, or the user backing out. */
    private val asked = Collections.synchronizedList(mutableListOf<Intent>())
    @Volatile private var choose = true
    private val picker = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
            asked += intent
            return if (choose) {
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(picked)))
            } else {
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
    }

    @Before fun aDamagedLedger() = runBlocking<Unit> {
        picked.delete()
        instrumentation.addMonitor(picker)
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        val leaf = Databases.shared(context).let { db ->
            db.runInTransaction { db.rawCaptureDao().insertAll(List(CAPTURES) { capture(it) }) }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val leaves = mutableListOf<Int>()
            db.openHelper.readableDatabase
                .query("SELECT pageno FROM dbstat WHERE name = 'raw_capture' AND pagetype = 'leaf' ORDER BY path")
                .use { c -> while (c.moveToNext()) leaves += c.getInt(0) }
            leaves[leaves.size / 2]
        }
        Databases.reset()
        RandomAccessFile(context.getDatabasePath(DatabaseFactory.NAME), "rw").use { raf ->
            val at = (leaf - 1).toLong() * 4096 + 1000
            raf.seek(at)
            val original = raf.readByte()
            raf.seek(at)
            raf.writeByte(original.toInt() xor 0xFF)
        }
        IntegrityStore.recordDamage(context)
    }

    @After fun leaveAnOpenableDatabase() = runBlocking<Unit> {
        instrumentation.removeMonitor(picker)
        picked.delete()
        IntegrityStore.forget(context)
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun aDamagedLedgerIsRescuedFromSettingsIntoAFileItReports() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("SETTINGS").performClick()
            assertTrue("settings never said the ledger is damaged", compose.waitFor { compose.countOf(RESCUE) == 1 })
            assertEquals("a damaged ledger is offered Export everything", 0, compose.countOf("Export everything"))

            // The delete sheet's offer of a copy is the rescue here too, and
            // backing out of its picker starts nothing.
            choose = false
            compose.onNodeWithText("Delete everything").performScrollTo().performClick()
            compose.onNodeWithText("$RESCUE first").performScrollTo().performClick()
            assertTrue("the delete sheet's rescue opened no picker", compose.waitFor { asked.size == 1 })

            choose = true
            // Scrolled back to: the delete row above left the notice above
            // the fold on CI's 320x640 emulator, where a click does nothing.
            compose.onNodeWithText(RESCUE)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performScrollTo()
                .performClick()
            assertTrue("tapping the rescue opened no picker", compose.waitFor { asked.size == 2 })
            assertEquals("the picker suggests the wrong name", RESCUE_FILE_NAME, asked[1].getStringExtra(Intent.EXTRA_TITLE))

            assertTrue(
                "the rescue never reported its file",
                compose.waitFor { compose.countOf("RESCUED WHAT COULD BE READ") == 1 },
            )
            assertTrue("the rescued file is empty", picked.length() > 0)
            assertEquals(
                "the sheet's count of what read does not add up to the $CAPTURES seeded",
                1,
                compose.countOf("of 3,000 notifications;", substring = true),
            )
            compose.onNodeWithText("OK").performClick()
            assertTrue("the report outlived its OK", compose.waitFor { compose.countOf("RESCUED WHAT COULD BE READ") == 0 })
        }
    }

    private fun capture(index: Int) = RawCapture(
        sourcePackage = "my.com.tngdigital.ewallet",
        postedAt = 1_600_000_000_000L + index,
        whenMillis = null,
        capturedAt = 1_600_000_000_000L + index,
        sbnKey = "0|my.com.tngdigital.ewallet|$index|null|0",
        notifId = index,
        notifTag = null,
        userHandle = 0,
        channelId = "txn",
        flags = 0,
        arrival = Arrival.POSTED,
        title = "Touch 'n Go eWallet",
        text = "Payment of RM1.00 to merchant number $index successful",
        bigText = null,
        subText = null,
        extrasJson = null,
        contentHash = "rescue-$index",
        // Not NEW, nor any status a re-parse revisits: every resume's
        // `ListenerStatus.report` enqueues stage two, which would work through
        // NEW captures on the damaged file past this class's teardown and
        // reopen the shared instance on it between the reset and the delete --
        // a damaged ledger the next class is then served.
        parseStatus = ParseStatus.UPDATE_OF,
    )

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String, substring: Boolean = false): Int {
        waitForIdle()
        return onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
    }

    private companion object {
        const val CAPTURES = 3_000
        const val RESCUE = "Rescue what can still be read"

        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L
    }
}
