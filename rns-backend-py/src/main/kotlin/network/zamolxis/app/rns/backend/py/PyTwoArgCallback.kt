package network.zamolxis.app.rns.backend.py

import com.chaquo.python.PyObject
import network.zamolxis.app.rns.api.annotation.ReflectivelyKept

/** Two-arg sibling of [PyEventCallback] — currently for `Link.set_remote_identified_callback`. */
@ReflectivelyKept // event_bridge.py calls onEvent(link, identity) by name via Chaquopy
fun interface PyTwoArgCallback {
    fun onEvent(first: PyObject, second: PyObject)
}
