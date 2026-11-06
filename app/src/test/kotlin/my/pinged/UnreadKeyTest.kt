package my.pinged

import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import my.pinged.data.NamedSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [Unread]'s key through saved state, as Nav3 saves the back stack across a
 * process death: the filter comes back whole, its app's package and name
 * together, and every app's list comes back unfiltered (#368).
 */
@RunWith(RobolectricTestRunner::class)
class UnreadKeyTest {
    @Test fun aFilteredListComesBackFilteredToTheSameApp() {
        val key = Unread(NamedSource("com.grabtaxi.passenger", "GRABPAY"))

        assertEquals(key, decodeFromSavedState(Unread.serializer(), encodeToSavedState(Unread.serializer(), key)))
    }

    @Test fun everyAppsListComesBackUnfiltered() {
        val key = Unread(only = null)

        assertEquals(key, decodeFromSavedState(Unread.serializer(), encodeToSavedState(Unread.serializer(), key)))
    }
}
