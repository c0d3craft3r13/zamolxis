package network.zamolxis.app.rns.host

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.zamolxis.app.data.crypto.IdentityKeyEncryptor
import network.zamolxis.app.data.db.DatabaseKeyStore
import network.zamolxis.app.rns.api.model.NetworkStatus
import network.zamolxis.app.rns.backend.py.ChaquopyRnsBackend
import network.zamolxis.app.rns.backend.py.PyEventCallback
import network.zamolxis.app.rns.backend.py.dictStr
import network.zamolxis.app.rns.backend.py.toPyBytes
import network.zamolxis.app.rns.host.persistence.ServiceSettingsAccessor
import java.io.File

/**
 * Runs Mayak — this project's own messaging protocol — in the `:reticulum` service.
 *
 * Mayak itself is Python, pip-installed from `vendor/python/mayak/` (see
 * `:rns-backend-py/CLAUDE.md`), and `mayak.host` is the whole of what it offers an
 * app. This class only decides *when*: the host starts once Reticulum is READY,
 * because it rides on the running `RNS.Reticulum` rather than starting a second
 * one, and stops when Reticulum does.
 *
 * **The passphrase** of the device file is 32 random bytes kept wrapped by the
 * Android Keystore ([DatabaseKeyStore.forMayak]), for the same reason the message
 * database's is: this service opens the file after a reboot, with nobody there to
 * type anything. Both live under `noBackupFilesDir`, so neither is swept into a
 * cloud backup or a device transfer.
 *
 * **Radio silence** reaches the host as `set_transmitting`. It is read in Kotlin
 * every [SETTINGS_POLL_MS], and Python is only called when it changes — a poll
 * that crossed into Python every time would take the GIL from the RNS reactor for
 * nothing.
 *
 * **Messages** arrive on [messages], without their text ever being logged. Nothing
 * shows them yet: until contacts can be added from a screen, nobody can write to
 * this device, so there is nothing to lose by that.
 */
class PythonMayakHost(
    private val context: Context,
    private val backend: ChaquopyRnsBackend,
    private val settings: ServiceSettingsAccessor,
    private val keyStore: DatabaseKeyStore =
        DatabaseKeyStore.forMayak(File(context.noBackupFilesDir, DIRECTORY), IdentityKeyEncryptor()),
) {
    private companion object {
        const val TAG = "PythonMayakHost"
        const val DIRECTORY = "mayak"
        const val DEVICE_FILE = "device.mayak"
        const val SETTINGS_POLL_MS = 5_000L
        const val MESSAGE_BUFFER = 64
    }

    /** A message that opened. [text] is never logged. */
    data class Inbound(
        val contactId: String,
        val name: String,
        val trust: String,
        val text: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val deviceFile: File get() = File(File(context.noBackupFilesDir, DIRECTORY), DEVICE_FILE)

    private val _messages = MutableSharedFlow<Inbound>(extraBufferCapacity = MESSAGE_BUFFER)
    val messages: SharedFlow<Inbound> = _messages.asSharedFlow()

    @Volatile
    private var host: PyObject? = null

    @Volatile
    private var settingsLoop: Job? = null

    @Volatile
    private var appliedTransmitting: Boolean? = null

    init {
        scope.launch {
            backend.core.networkStatus.collect { status ->
                when (status) {
                    NetworkStatus.READY -> start()
                    NetworkStatus.SHUTDOWN, is NetworkStatus.ERROR -> stop()
                    else -> Unit
                }
            }
        }
    }

    // Everything crossing into Python can raise anything; a Mayak that fails to
    // start must not take the Reticulum service down with it.
    @Suppress("TooGenericExceptionCaught")
    private fun start() {
        if (host != null) return
        try {
            val passphrase = keyStore.loadOrCreate()
            val sink = PyObject.fromJava(PyEventCallback(::onEvent))["onEvent"]
            val created =
                try {
                    backend.runtime.python
                        .getModule("mayak.host")
                        .callAttr("for_this_device", deviceFile.absolutePath, passphrase.toPyBytes(), sink)
                } finally {
                    passphrase.fill(0)
                }
            created.callAttr("start")
            host = created
            appliedTransmitting = null
            applySettings(created)
            Log.i(TAG, "Mayak running: ${describe(created.callAttr("status"))}")
            settingsLoop =
                scope.launch {
                    while (isActive) {
                        delay(SETTINGS_POLL_MS)
                        host?.let(::applySettings)
                    }
                }
        } catch (e: Throwable) {
            Log.e(TAG, "Mayak did not start: ${e.message}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun stop() {
        val running = host ?: return
        host = null
        settingsLoop?.cancel()
        settingsLoop = null
        try {
            val dropped = running.callAttr("stop").toInt()
            Log.i(TAG, "Mayak stopped; $dropped held message(s) dropped")
        } catch (e: Throwable) {
            Log.e(TAG, "Mayak did not stop cleanly: ${e.message}")
        }
    }

    private fun applySettings(running: PyObject) {
        val transmitting = !settings.getRadioSilence()
        if (transmitting != appliedTransmitting) {
            running.callAttr("set_transmitting", transmitting)
            appliedTransmitting = transmitting
        }
    }

    private fun onEvent(payload: PyObject) {
        if (payload.dictStr("kind") != "message") return
        _messages.tryEmit(
            Inbound(
                contactId = payload.dictStr("contact").orEmpty(),
                name = payload.dictStr("name").orEmpty(),
                trust = payload.dictStr("trust").orEmpty(),
                text = payload.dictStr("text").orEmpty(),
            ),
        )
    }

    /** The status fields that say what is running, and none that say who with. */
    private fun describe(status: PyObject): String =
        listOf("kem", "bound", "vault", "transmitting", "contacts")
            .joinToString(", ") { "$it=${status.dictStr(it)}" }
}
