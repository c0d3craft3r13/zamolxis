package network.zamolxis.app.rns.backend.py

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.PyObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Can the Python in this app call C that this project built?
 *
 * The question is not academic. ML-KEM-768 is the post-quantum half of the
 * Mayak protocol, and on Android it cannot come from `cryptography`: Chaquopy's
 * package index stops at 42.0.8 and ML-KEM landed in 47. Shipping the FIPS 203
 * implementation as a native library and binding it with `ctypes` keeps every
 * line of protocol logic in Python — but only if CPython inside this process
 * can dlopen a library out of our own APK and call into it.
 *
 * `_ctypes.cpython-311.so` being in Chaquopy's bootstrap set proves the module
 * exists. It says nothing about our library, which is what this measures, on a
 * real device, before anybody vendors anybody's C.
 *
 * The three cases are the three shapes a KEM binding needs: a returned value,
 * an output buffer the caller owns, and an input buffer the caller owns.
 */
@RunWith(AndroidJUnit4::class)
class CtypesNativeLibraryInstrumentedTest {
    private val python by lazy {
        PythonRnsRuntime(ApplicationProvider.getApplicationContext()).python
    }

    private val ctypes by lazy { python.getModule("ctypes") }

    /** Loaded by soname: Android resolves it from the APK's lib/<abi>/ directory. */
    private fun mayakNative(): PyObject = ctypes.callAttr("CDLL", "libmayak_native.so")

    @Test
    fun pythonLoadsTheLibraryThisProjectBuilt() {
        assertEquals(1, mayakNative().callAttr("mayak_native_abi_version").toInt())
    }

    @Test
    fun cWritesIntoABufferPythonOwns() {
        val buffer = ctypes.callAttr("create_string_buffer", FILLED.size)

        mayakNative().callAttr("mayak_native_fill", buffer, FILLED.size)

        assertArrayEquals(FILLED, checkNotNull(buffer["raw"]).toJava(ByteArray::class.java))
    }

    @Test
    fun cReadsABufferPythonOwns() {
        val message = "mayak".toByteArray()

        // `toPyBytes()` and not the ByteArray itself: Chaquopy hands a Kotlin
        // ByteArray across as `jarray('B')`, and ctypes refuses it with
        // "don't know how to convert parameter 1". Measured, not assumed — the
        // first run of this test failed on exactly that line. It matters beyond
        // this test, because a KEM binding passes nothing but byte buffers.
        val checksum = mayakNative().callAttr("mayak_native_checksum", message.toPyBytes(), message.size).toInt()

        assertEquals(expectedChecksum(message), checksum)
    }

    /** The same arithmetic as mayak_native.c, written out rather than copied from a run. */
    private fun expectedChecksum(data: ByteArray): Int {
        var sum = 0L
        for (byte in data) {
            sum = (sum * 31L + (byte.toInt() and 0xFF)) and 0xFFFFFFFFL
        }
        return (sum and 0xFFFFL).toInt()
    }

    private companion object {
        /** What mayak_native_fill writes: i * 7 + 3. */
        val FILLED = ByteArray(8) { (it * 7 + 3).toByte() }
    }
}
