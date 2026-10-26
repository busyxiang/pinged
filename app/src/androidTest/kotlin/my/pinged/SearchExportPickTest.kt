package my.pinged

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.ui.test.boundedWaitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search's "Export these N rows" (#201) as the user meets it: the button
 * opens the system's save-file picker for a CSV, and the file it is handed
 * holds the rows the search found and no others.
 *
 * The picker is answered by an `Instrumentation.ActivityMonitor`, as
 * `RestorePickTest` answers its own, with a file in this app's cache. The
 * search is the month's Uncategorized, opened from Spending's `2 TO SORT`
 * as `ToSortRouteTest` opens it, so a third row in the ledger is one the
 * file must leave out.
 */
@RunWith(AndroidJUnit4::class)
class SearchExportPickTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val picked = File(context.cacheDir, "picked-search.csv")

    /** Unique per row, as the column's index requires. */
    private var captureIds = 1L

    private val today: LocalDate = LocalDate.now()

    /** Each CSV picker opened. */
    private val asked = Collections.synchronizedList(mutableListOf<Intent>())
    private val picker = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_CREATE_DOCUMENT || intent.type != "text/csv") return null
            asked += intent
            return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(picked)))
        }
    }

    @Before fun aLedgerWithTwoRowsToSort() {
        picked.delete()
        instrumentation.addMonitor(picker)
        discard()
        val uncategorized = Databases.categoryDao(context).requireUncategorizedId()
        val food = Databases.categoryDao(context).all().first { it.name == "Food & Drinks" }.id
        insert(row(30_000L, uncategorized, FOUND_FIRST))
        insert(row(31_224L, uncategorized, FOUND_SECOND))
        insert(row(10_000L, food, LEFT_OUT))
    }

    @After fun removeThePicker() {
        instrumentation.removeMonitor(picker)
        picked.delete()
        discard()
    }

    @Test fun theSearchsExportWritesItsRowsWhereThePickerSays() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("Spending never drew $TO_SORT", compose.waitFor { compose.countOf(TO_SORT) == 1 })
            compose.onNodeWithText(TO_SORT).performScrollTo().performClick()
            assertTrue("the search never found its two rows", compose.waitFor { compose.countOf(FOOTER) == 1 })

            compose.onNodeWithText(FOOTER).performClick()
            compose.onNodeWithText("Export 2 rows").performScrollTo().performClick()
            assertTrue("the export opened no CSV picker", compose.waitFor { asked.size == 1 })
            assertEquals("the picker suggests the wrong name", "pinged-search.csv", asked[0].getStringExtra(Intent.EXTRA_TITLE))

            assertTrue("the export never reported its file", compose.waitFor { compose.countOf("SAVED", substring = true) == 1 })
            val lines = picked.readLines().filter { it.isNotBlank() }
            assertEquals("the file is not a header and the search's two rows: $lines", 3, lines.size)
            assertTrue("the file lacks $FOUND_FIRST: $lines", lines.any { FOUND_FIRST in it })
            assertTrue("the file lacks $FOUND_SECOND: $lines", lines.any { FOUND_SECOND in it })
            assertTrue("the file holds a row the search did not find: $lines", lines.none { LEFT_OUT in it })
        }
    }

    private fun insert(txn: Txn): Long = Databases.shared(context).txnDao().insert(txn)

    private fun row(amountSen: Long, categoryId: Long, merchant: String): Txn {
        val at = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Txn(
            // Captured, as its source says; the id names no capture row.
            rawCaptureId = captureIds++,
            amountSen = amountSen,
            direction = Direction.EXPENSE,
            occurredAt = at,
            localDate = LocalDates.of(at),
            merchantRaw = merchant,
            merchantDisplay = merchant,
            merchantKey = merchant,
            categoryId = categoryId,
            sourcePackage = "com.maybank2u.life",
            sourceLabel = "MAE",
            confidence = Confidence.HIGH,
            state = TxnState.COMMITTED,
            createdAt = 1_000L,
            updatedAt = 1_000L,
        )
    }

    private fun discard() {
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { boundedWaitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String, substring: Boolean = false): Int {
        waitForIdle()
        return onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        const val TO_SORT = "2 TO SORT"

        /** Search's footer over the two rows to sort. */
        const val FOOTER = "EXPORT THESE 2 ROWS"

        const val FOUND_FIRST = "KEDAI RUNCIT"
        const val FOUND_SECOND = "PASAR MALAM"
        const val LEFT_OUT = "KOPI TIAM"
    }
}
