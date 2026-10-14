package my.pinged.capture

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Spec 9.6's just-in-time prompt, from the point the allow-list gate has said no.
 *
 * Robolectric, because the two things that decide whether the prompt reaches the
 * user -- whether the app may post at all, and what a tap opens -- are a
 * `NotificationManager`'s answers, and only a shadow lets a test turn
 * notifications off without revoking a permission and killing its own process.
 *
 * The "already asked" mark lives in a DataStore that outlives a single test in
 * this process, so each test starts by forgetting it.
 */
@RunWith(RobolectricTestRunner::class)
class SourcePromptTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun freshUserWhoAllowsNotifications() = runBlocking {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // The app module's launcher activity, which this library's manifest lacks.
        val launcher = ComponentName(context.packageName, "my.pinged.MainActivity")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(launcher)
            addIntentFilterForActivity(
                launcher,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
        SourcePrompt.forget(context)
    }

    @Test
    fun `a suggested package is offered once and only once`() = runBlocking {
        assertTrue(SourcePrompt.offer(context, TNG))
        assertFalse(SourcePrompt.offer(context, TNG))

        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        // Named by the pack's label, the name the rules were written for.
        assertEquals("Capture Touch 'n Go eWallet?", posted.single().extras.getString("android.title"))
    }

    @Test
    fun `a package the pack does not know is never offered`() = runBlocking {
        assertFalse(SourcePrompt.offer(context, "com.example.chat"))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `while notifications are off nothing is posted and the one prompt is kept`() = runBlocking {
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(SourcePrompt.offer(context, MAYBANK))

        shadowOf(manager).setNotificationsEnabled(true)
        assertTrue(SourcePrompt.offer(context, MAYBANK))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `until notifications are granted nothing is posted and the one prompt is kept`() = runBlocking {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(SourcePrompt.offer(context, MAYBANK))

        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(SourcePrompt.offer(context, MAYBANK))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `tapping the prompt opens the capture sources`() = runBlocking {
        SourcePrompt.offer(context, TNG)
        val tap = shadowOf(shadowOf(manager).allNotifications.single().contentIntent).savedIntent

        assertTrue(SourcePrompt.opensSources(tap))
        // Explicit: MainActivity declares no CATEGORY_DEFAULT, so an implicit
        // intent would not start it.
        assertEquals("my.pinged.MainActivity", tap.component?.className)
    }

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAYBANK = "com.maybank2u.life"
    }
}
