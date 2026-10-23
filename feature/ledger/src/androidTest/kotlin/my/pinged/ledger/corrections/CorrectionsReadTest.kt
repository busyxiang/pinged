package my.pinged.ledger.corrections

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.capture.Corrections
import my.pinged.capture.Graph
import my.pinged.data.Databases
import my.pinged.ledger.corruptTheDatabase
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.refreshed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The corrections holder's read against the real database (#300, #296): the
 * body `:core:read` runs in one transaction reads the capture store with
 * `runBlocking`, which the JVM suite's in-memory ledger cannot say is safe
 * inside SQLCipher's one connection.
 */
@RunWith(AndroidJUnit4::class)
class CorrectionsReadTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @After fun leaveNothingBehind() = app.discardTheDatabase()

    @Test fun aSweptHistoryReadsAsAnEmptyListUnderThePacksVersion() = runBlocking {
        app.discardTheDatabase()
        val matcher = Graph.ruleMatcher()
        while (Corrections.sweep(app, Databases.rawCaptureDao(app), matcher).more) { }
        val model = CorrectionsViewModel(app)

        model.read.refreshed()

        val state = model.read.state.value.toState()
        assertEquals("The read did not land", true, state.loaded)
        assertEquals("A swept history on an empty ledger is still being checked", false, state.checking)
        assertEquals(emptyList<Corrections.Correction>(), state.items)
        assertEquals(matcher.packVersion, state.packVersion)
    }

    @Test fun anUnopenableDatabaseIsStorageUnavailableNotEmpty() = runBlocking {
        app.corruptTheDatabase()
        val model = CorrectionsViewModel(app)

        model.read.refreshed()

        assertEquals(true, model.read.state.value.toState().storageUnavailable)
    }
}
