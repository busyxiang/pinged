package my.pinged.capture

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.pinged.data.entity.Arrival
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Spec 9.6: a package never enabled and never touched in the picker is dropped
 * once it was first seen more than 30 days ago.
 */
@RunWith(RobolectricTestRunner::class)
class SourceCountersExpiryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = 24L * 60 * 60 * 1000
    private val start = 1_800_000_000_000L

    /** The DataStore is a process singleton, so a test cannot assume it starts empty. */
    @Before fun emptyTheStore() = runBlocking { CaptureCaches.clear(context) }

    private fun count(pkg: String, now: Long) = runBlocking {
        SourceCounters.countOne(context, pkg, Arrival.POSTED, postTime = now, now = now)
    }

    private fun drop(now: Long, keep: Set<String> = emptySet()) = runBlocking {
        SourceCounters.dropExpired(context, now, keep)
    }

    private fun counts() = runBlocking { SourceCounters.allSeenCounts(context) }

    @Test fun `a package first seen 31 days ago is dropped and one seen 29 days ago is not`() {
        count("com.old", start)
        count("com.young", start + 2 * day)

        drop(now = start + 31 * day)

        assertEquals(mapOf("com.young" to 1), counts())
    }

    @Test fun `the age is from first seen, so posting again does not keep a package`() {
        count("com.chatty", start)
        count("com.chatty", start + 30 * day)

        drop(now = start + 31 * day)

        assertEquals(emptyMap<String, Int>(), counts())
    }

    @Test fun `a package the caller keeps is not dropped however old`() {
        count("com.touched", start)

        drop(now = start + 365 * day, keep = setOf("com.touched"))

        assertEquals(mapOf("com.touched" to 1), counts())
    }

    @Test fun `a dropped package that posts again returns with a count of one`() {
        count("com.back", start)
        count("com.back", start + day)
        drop(now = start + 31 * day)

        count("com.back", start + 32 * day)
        drop(now = start + 33 * day)

        assertEquals(mapOf("com.back" to 1), counts())
    }

    /**
     * An install upgraded from before first-seen was recorded: a count and no
     * age. Written straight into the store, because no current path can make one.
     */
    @Test fun `a count with no first-seen gets 30 days from the first drop pass`() {
        runBlocking { context.captureStore.edit { it[intPreferencesKey("seen:com.legacy")] = 5 } }

        drop(now = start)
        assertEquals(mapOf("com.legacy" to 5), counts())

        drop(now = start + 29 * day)
        assertEquals(mapOf("com.legacy" to 5), counts())

        drop(now = start + 31 * day)
        assertEquals(emptyMap<String, Int>(), counts())
    }
}
