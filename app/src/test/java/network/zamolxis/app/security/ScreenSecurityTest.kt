package network.zamolxis.app.security

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenSecurityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("zamolxis_screen_security", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * The default decides what a user who never opens Settings is protected
     * from, which is most users. It is the single most consequential line in
     * this class, so it is pinned.
     */
    @Test
    fun `capture is blocked until the user says otherwise`() {
        assertTrue(ScreenSecurity.isBlocked(context))
    }

    @Test
    fun `the setting survives being written and read back`() {
        ScreenSecurity.setBlocked(context, false)
        assertFalse(ScreenSecurity.isBlocked(context))

        ScreenSecurity.setBlocked(context, true)
        assertTrue(ScreenSecurity.isBlocked(context))
    }

    @Test
    fun `blocking sets FLAG_SECURE on the window`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        ScreenSecurity.apply(activity.window, blocked = true)

        assertEquals(
            WindowManager.LayoutParams.FLAG_SECURE,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    /**
     * The switch has to work in both directions in one session — a user who
     * turns capture back on to take a screenshot should not have to restart the
     * app to do it.
     */
    @Test
    fun `unblocking clears FLAG_SECURE again`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ScreenSecurity.apply(activity.window, blocked = true)

        ScreenSecurity.apply(activity.window, blocked = false)

        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun `the context overload reads the stored setting`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ScreenSecurity.setBlocked(context, false)

        ScreenSecurity.apply(context, activity.window)

        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }
}
