package network.zamolxis.app.rns.backend.py

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // The test APK has a keystore of its own, separate from the installed
        // app's, and no real device files. Keys left there by an earlier run that
        // crashed are orphans; clearing them keeps the keystore from filling up
        // run after run. The self-check checks only its own file's keys either way.
        val vault = python.getModule("mayak.vault").callAttr("hardware_vault")
        if (vault.toString() != "None") {
            val orphans = python.getModule("mayak.bound_store")
                .callAttr("forget_orphaned_stores", vault, python.builtins.callAttr("list"))
            report("orphaned store keys destroyed before the run: $orphans")
        }

        val report = python.getModule("mayak.selfcheck").callAttr("run", context.cacheDir.absolutePath)

        fun field(name: String): String = report.callAttr("get", name).toString()

        assertEquals("ml-kem-768 from native C (mlkem-native)", field("kem"))
        assertEquals("argon2-cffi", field("argon2id"))
        assertEquals("ok", field("conversation"))

        // The bound store ran against the real Android Keystore: an earlier copy
        // of a bound file failed to open after a secret left it, and wiping left
        // no hardware keys behind. A phone whose keystore is software-only would
        // report "passphrase only" here, and that is a failure, not a pass.
        val store = field("store")
        assertTrue("expected a hardware-bound store, got: $store", store.startsWith("bound to "))
        report("store=$store, ordinary save ${field("store_save_ms")} ms, rotation ${field("store_rotation_ms")} ms")
    }

    /**
     * Into the instrumentation output rather than logcat: this phone's main log
     * buffer is 256 KiB and keeps almost nothing, so a Log line from a test is
     * gone before anyone reads it.
     */
    private fun report(line: String) {
        val status = Bundle().apply { putString("stream", "\n$line\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status)
    }
}
