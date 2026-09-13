package network.zamolxis.app.rns.backend.py

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * `mayak.host` on the phone, the way the `:reticulum` service drives it.
 *
 * `PythonMayakHost` in `:rns-host` makes exactly these calls: `for_this_device` with a
 * random passphrase and a Kotlin callback, `start`, `set_transmitting`, `status`, `stop`.
 * This runs them against the phone's real Keystore and its native ML-KEM, and then flips
 * the device file bound and back, which is what the developer setting will do.
 *
 * No Reticulum is started. A host with no contacts registers no destination and sends
 * nothing, so none of this reaches RNS — and starting one would leave `RNS.Transport`
 * initialised for every later test in this process, which
 * `PythonRnsCoreIdentityRecoveryInstrumentedTest` requires it not to be. The first
 * version of this test did start one, and that test failed after it.
 */
@RunWith(AndroidJUnit4::class)
class MayakHostInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val python by lazy { PythonRnsRuntime(context).python }

    @Test
    fun theHostRunsBindsAndUnbindsOnThePhonesKeystore() {
        val directory = File(context.cacheDir, "mayak-host").apply { deleteRecursively() }
        val device = File(directory, "device.mayak")
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val events = mutableListOf<String>()
        val sink = PyObject.fromJava(PyEventCallback { events += it.dictStr("kind").orEmpty() })["onEvent"]

        val host =
            python.getModule("mayak.host")
                .callAttr("for_this_device", device.absolutePath, passphrase.toPyBytes(), sink)

        var started = System.nanoTime()
        host.callAttr("start")
        report("first start (keys made, file sealed): ${elapsedMs(started)} ms")

        fun status(field: String): String = host.callAttr("status").dictStr(field).orEmpty()

        assertEquals("true", status("running"))
        assertEquals("x25519+ml-kem-768", status("kem"))
        assertEquals("the phone has a hardware keystore to bind to", "true", status("can_bind"))
        assertEquals("a new device file is never bound without being asked", "false", status("bound"))
        assertTrue(device.isFile)

        val invitation = host.callAttr("invite").toString()
        assertTrue(invitation, invitation.startsWith("mayak1:"))

        host.callAttr("set_transmitting", false)
        assertEquals("false", status("transmitting"))
        host.callAttr("set_transmitting", true)

        started = System.nanoTime()
        assertEquals(0, host.callAttr("set_bound", true).toInt())
        report("bind: ${elapsedMs(started)} ms, vault=${status("vault")}")
        assertEquals("true", status("bound"))
        assertEquals("true", status("running"))
        assertEquals("the invitation made before binding is still there", "1", status("open_invitations"))

        started = System.nanoTime()
        host.callAttr("set_bound", false)
        report("unbind: ${elapsedMs(started)} ms")
        assertEquals("false", status("bound"))
        assertEquals("1", status("open_invitations"))

        host.callAttr("stop")
        assertEquals("false", status("running"))

        val again =
            python.getModule("mayak.host")
                .callAttr("for_this_device", device.absolutePath, passphrase.toPyBytes(), sink)
        again.callAttr("start")
        assertEquals("the file reopens after a restart", "1", again.callAttr("status").dictStr("open_invitations"))

        val wrong = passphrase.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val stranger =
            python.getModule("mayak.host")
                .callAttr("for_this_device", device.absolutePath, wrong.toPyBytes(), sink)
        try {
            stranger.callAttr("start")
            fail("a different passphrase opened the device file")
        } catch (refused: PyException) {
            assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains("HostError"))
        }

        again.callAttr("wipe")
        assertFalse(device.exists())
        assertTrue("nothing arrived, so nothing was delivered: $events", events.isEmpty())
        assertFalse("a host with no contacts reached into Reticulum", transportInitialised())
    }

    private fun transportInitialised(): Boolean =
        python.builtins
            .callAttr("hasattr", python.getModule("RNS")["Transport"], "owner")
            .toJava(Boolean::class.javaObjectType)

    private fun elapsedMs(since: Long): Long = (System.nanoTime() - since) / 1_000_000

    /** Into the instrumentation output; this phone's log buffer keeps almost nothing. */
    private fun report(line: String) {
        val status = Bundle().apply { putString("stream", "\n$line\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status)
    }
}
