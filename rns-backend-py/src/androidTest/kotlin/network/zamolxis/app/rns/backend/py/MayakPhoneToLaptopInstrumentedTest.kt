package network.zamolxis.app.rns.backend.py

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A Mayak message from this phone to a laptop and a reply back, over real Reticulum.
 *
 * Everything before this proved pieces: the phone's ML-KEM against the laptop's,
 * its Argon2id against the laptop's, the protocol end to end over a loopback. None
 * of it put a packet between two machines. This does, and the two ends run
 * different implementations of both primitives — native ML-KEM and argon2-cffi
 * here, `cryptography` there — so a message that crosses is one that could only
 * cross if they agree.
 *
 * Driven from the laptop by ProjectBeta's `tools/phone_link_check.py`, which
 * creates both device files, introduces them to each other, starts `mayak listen`
 * behind a Reticulum TCP server, forwards the port with `adb reverse`, and passes
 * this phone's device file in as an instrumentation argument. The phone reaches
 * the laptop at 127.0.0.1 over the USB cable: no Wi-Fi, nothing on the air.
 *
 * Without those arguments the test is skipped rather than failed, so the module's
 * ordinary instrumented run does not need a laptop standing by.
 *
 * Reticulum here runs standalone, not as a shared instance, so it cannot collide
 * with an installed copy of the app holding the shared-instance ports.
 */
@RunWith(AndroidJUnit4::class)
class MayakPhoneToLaptopInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aMessageReachesTheLaptopAndTheReplyComesBack() {
        val arguments = InstrumentationRegistry.getArguments()
        val device = arguments.getString("mayak_device")
        val passphrase = arguments.getString("mayak_passphrase")
        val port = arguments.getString("mayak_port")
        assumeTrue("needs tools/phone_link_check.py driving it from a laptop", device != null && passphrase != null && port != null)

        val python = PythonRnsRuntime(context).python
        val namespace = python.builtins.callAttr("dict")
        python.builtins.callAttr("exec", SCENARIO, namespace)

        val workspace = File(context.cacheDir, "mayak-phone-link").apply { deleteRecursively() }
        val result =
            namespace.callAttr("__getitem__", "run").call(
                workspace.absolutePath,
                device,
                passphrase,
                port!!.toInt(),
                MESSAGE,
                TIMEOUT_SECONDS,
            )

        fun field(name: String): String = result.callAttr("get", name).toString()

        assertEquals("the laptop's reply, with online=${field("online")} handed_over=${field("handed_over")}", REPLY, field("reply"))
    }

    private companion object {
        /** Must match MESSAGE_FROM_PHONE in tools/phone_link_check.py. */
        const val MESSAGE = "hold position until first light — from the phone"

        /** Must match REPLY_FROM_LAPTOP in tools/phone_link_check.py. Not English, on purpose. */
        const val REPLY = "принято, телефон 灯台 🔦"

        const val TIMEOUT_SECONDS = 150

        /**
         * Kept in the test rather than in the Mayak package: it is how this check
         * wires a device to a laptop, not something the protocol ships.
         */
        const val SCENARIO = """
import base64
import threading
import time
from pathlib import Path

import RNS

from mayak.mechanisms import hybrid_mechanism
from mayak.node import Node
from mayak.rns_transport import RnsTransport, body_length_for
from mayak.store import EncryptedStore

CONFIG = (
    "[reticulum]\n"
    "  enable_transport = False\n"
    "  share_instance = No\n"
    "  panic_on_interface_error = False\n"
    "\n"
    "[logging]\n"
    "  loglevel = 3\n"
    "\n"
    "[interfaces]\n"
    "  [[Laptop over adb reverse]]\n"
    "    type = TCPClientInterface\n"
    "    enabled = yes\n"
    "    target_host = 127.0.0.1\n"
    "    target_port = PORT\n"
)


def run(workdir, device_b64, passphrase, port, message, timeout):
    work = Path(workdir)
    config = work / "reticulum"
    config.mkdir(parents=True, exist_ok=True)
    (config / "config").write_text(CONFIG.replace("PORT", str(port)))
    device = work / "phone.mayak"
    device.write_bytes(base64.urlsafe_b64decode(device_b64))

    RNS.Reticulum(str(config))
    kem = hybrid_mechanism()

    replies = []
    arrived = threading.Event()

    def on_message(contact, inbound):
        replies.append(inbound.content.decode("utf-8"))
        arrived.set()

    store = EncryptedStore(device, passphrase.encode("utf-8"))
    node = Node.open(kem, RnsTransport, body_length_for(kem), store, on_message)
    node.listen()
    laptop = next(iter(node.contacts))

    def online():
        return any(getattr(interface, "online", False) for interface in RNS.Transport.interfaces)

    deadline = time.monotonic() + 30
    while time.monotonic() < deadline and not online():
        time.sleep(0.5)
    was_online = online()

    handed_over = node.send(laptop.identity_key, message.encode("utf-8"))

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and not arrived.is_set():
        if node.waiting:
            node.flush()
        arrived.wait(1.0)

    node.stop()
    return {
        "online": str(was_online),
        "handed_over": str(handed_over),
        "reply": replies[0] if replies else "",
    }
"""
    }
}
