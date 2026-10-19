package my.pinged.capture

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.pinged.data.entity.Arrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #218: Android's own packages never post a payment, so they are never offered
 * as a capture source. Sources' "seen recently" list is [SourceCounters.allSeenCounts].
 */
@RunWith(RobolectricTestRunner::class)
class SourceCountersSystemTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L

    /** The DataStore is a process singleton, so a test cannot assume it starts empty. */
    @Before fun emptyTheStore() = runBlocking { CaptureCaches.clear(context) }

    private fun count(pkg: String) = runBlocking {
        SourceCounters.countOne(context, pkg, Arrival.POSTED, postTime = now, now = now)
    }

    private fun counts() = runBlocking { SourceCounters.allSeenCounts(context) }

    @Test fun `System UI and the android package are not counted`() {
        assertFalse(count("com.android.systemui"))
        assertFalse(count("android"))
        count("my.bank")

        assertEquals(mapOf("my.bank" to 1), counts())
    }

    /** Counted before the exclusion existed: the store still has it, the screen must not. */
    @Test fun `a System UI count already in the store is not listed`() {
        runBlocking {
            context.captureStore.edit {
                it[intPreferencesKey("seen:com.android.systemui")] = 1
                it[intPreferencesKey("seen:android")] = 3
            }
        }
        count("my.bank")

        assertEquals(mapOf("my.bank" to 1), counts())
    }
}
