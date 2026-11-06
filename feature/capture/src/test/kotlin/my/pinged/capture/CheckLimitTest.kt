package my.pinged.capture

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Spec 7.1's check limit as the store keeps it (#362): one of six steps, RM500
 * until the user picks another, and RM500 again after delete everything.
 */
@RunWith(RobolectricTestRunner::class)
class CheckLimitTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The store is a process singleton, so each test leaves it as a fresh install has it. */
    @After fun reset() = runBlocking { CheckLimit.reset(context) }

    private fun read() = runBlocking { CheckLimit.sen(context) }

    @Test fun `the steps are the six the brief names, RM500 among them`() {
        assertEquals(listOf(10_000L, 20_000L, 50_000L, 100_000L, 200_000L, 500_000L), CheckLimit.STEPS_SEN)
        assertEquals(50_000L, CheckLimit.DEFAULT_SEN)
    }

    @Test fun `a fresh install reads RM500`() {
        assertEquals(50_000L, read())
    }

    @Test fun `a step saved is the step read`() {
        runBlocking { CheckLimit.set(context, 100_000L) }

        assertEquals(100_000L, read())
    }

    @Test fun `an amount that is not a step is refused and the limit kept`() {
        runBlocking { CheckLimit.set(context, 20_000L) }

        assertThrows(IllegalArgumentException::class.java) { runBlocking { CheckLimit.set(context, 75_000L) } }
        assertEquals(20_000L, read())
    }

    /** A value no step names -- a later version's step, a hand-edited file -- is not a limit. */
    @Test fun `a stored value that is not a step reads as RM500`() {
        runBlocking { context.checkLimitStore.edit { it[CheckLimit.KEY] = 75_000L } }

        assertEquals(50_000L, read())
    }

    @Test fun `a reset reads RM500 again`() {
        runBlocking {
            CheckLimit.set(context, 500_000L)
            CheckLimit.reset(context)
        }

        assertEquals(50_000L, read())
    }
}
