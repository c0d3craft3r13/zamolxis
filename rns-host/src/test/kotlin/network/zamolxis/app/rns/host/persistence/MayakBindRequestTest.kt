package network.zamolxis.app.rns.host.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The hand-off between the developer setting in the UI process and the service that
 * owns the Mayak device file.
 *
 * What matters is that a request is acted on once. The service removes it whatever the
 * outcome, so a bind that fails is reported rather than attempted every few seconds —
 * and removes only the request it acted on, so a person who changed their mind while
 * the file was being rewritten is not silently overruled.
 */
@RunWith(RobolectricTestRunner::class)
class MayakBindRequestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = ServiceSettingsAccessor(context)

    @Before
    fun setUp() {
        context.getSharedPreferences(ServiceSettingsAccessor.CROSS_PROCESS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `nothing is pending until something is asked for`() {
        assertNull(settings.getMayakBindRequest())
    }

    @Test
    fun `a request is there until the service clears it`() {
        settings.requestMayakBound(true)

        assertEquals(true, settings.getMayakBindRequest())

        settings.clearMayakBindRequest(handled = true)

        assertNull(settings.getMayakBindRequest())
    }

    @Test
    fun `a request changed while the service worked on the old one survives`() {
        settings.requestMayakBound(true)
        settings.requestMayakBound(false)

        settings.clearMayakBindRequest(handled = true)

        assertEquals(false, settings.getMayakBindRequest())
    }

    @Test
    fun `an unbind request is not mistaken for no request`() {
        settings.requestMayakBound(false)

        assertEquals(false, settings.getMayakBindRequest())
    }

    @Test
    fun `before the service has reported, Mayak is not running`() {
        assertEquals(MayakFileState.NOT_RUNNING, settings.getMayakFileState())
        assertFalse(settings.getMayakCanBind())
        assertNull(settings.getMayakFileError())
    }

    @Test
    fun `a report carries the state, whether binding is possible, and why it failed`() {
        settings.reportMayakFile(MayakFileState.PORTABLE, canBind = true, error = "the keystore refused")

        assertEquals(MayakFileState.PORTABLE, settings.getMayakFileState())
        assertTrue(settings.getMayakCanBind())
        assertEquals("the keystore refused", settings.getMayakFileError())
    }

    @Test
    fun `a report without an error clears the last one`() {
        settings.reportMayakFile(MayakFileState.PORTABLE, canBind = true, error = "the keystore refused")

        settings.reportMayakFile(MayakFileState.BOUND, canBind = true, error = null)

        assertEquals(MayakFileState.BOUND, settings.getMayakFileState())
        assertNull(settings.getMayakFileError())
    }

    @Test
    fun `a state this build does not know reads as not running`() {
        context.getSharedPreferences(ServiceSettingsAccessor.CROSS_PROCESS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ServiceSettingsAccessor.KEY_MAYAK_FILE_STATE, "SOMETHING_NEWER")
            .commit()

        assertEquals(MayakFileState.NOT_RUNNING, settings.getMayakFileState())
    }
}
