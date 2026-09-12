package network.zamolxis.app.rns.backend.py

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Mayak protocol, end to end, inside this app's Python on a real phone.
 *
 * Mayak was written and tested on a laptop. The phone differs in every place that
 * matters to it: Chaquopy's `cryptography` is 42.0.8, which has neither ML-KEM
 * (47) nor Argon2id (44), so ML-KEM-768 comes from `libmayak_mlkem.so` and
 * Argon2id from `argon2-cffi`. Each substitution was checked against the laptop's
 * implementation on its own. This checks that they compose on the device that
 * ships them.
 *
 * It calls `mayak.selfcheck.run` — the same function the desktop suite runs — so
 * the two cannot be checking different things. That function holds a
 * conversation over the in-process loopback: a Cyrillic message, one long enough
 * to be split across frames, a reply, an encrypted store reopened and then wiped.
 * No network, nothing on the air.
 *
 * The report is asserted, not just printed: a phone that silently picked some
 * other implementation would pass a test that only asked whether it worked.
 */
@RunWith(AndroidJUnit4::class)
class MayakOnDeviceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val python by lazy { PythonRnsRuntime(context).python }

    @Test
    fun theProtocolWorksEndToEndWithWhatThePhoneHas() {
        val report = python.getModule("mayak.selfcheck").callAttr("run", context.cacheDir.absolutePath)

        fun field(name: String): String = report.callAttr("get", name).toString()

        assertEquals("ml-kem-768 from native C (mlkem-native)", field("kem"))
        assertEquals("argon2-cffi", field("argon2id"))
        assertEquals("ok", field("conversation"))
    }
}
