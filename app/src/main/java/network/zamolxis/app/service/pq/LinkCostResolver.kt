package network.zamolxis.app.service.pq

import network.zamolxis.app.data.model.InterfaceType
import network.zamolxis.crypto.pq.HybridKem
import network.zamolxis.crypto.pq.LinkCost

/**
 * Classifies a Reticulum interface by what an extra [HybridKem.OVERHEAD_BYTES]
 * costs on it.
 *
 * Sealing adds about 1.1 KB to every message. Over TCP, Wi-Fi or Bluetooth LE
 * that is not worth a thought. Over a LoRa link at a few hundred bits per second
 * it is several seconds of extra airtime on a channel shared with everyone else
 * in range — including people who are not using this app and did not agree to
 * pay for its cryptography.
 *
 * Kept as a pure function over the interface-type string the app already records
 * for each peer sighting, so the classification can be checked without a radio.
 */
object LinkCostResolver {
    /**
     * Interfaces whose link layer is sub-kbps radio.
     *
     * RNode is here regardless of how the phone reaches the device. Its
     * `connectionMode` (USB, Bluetooth, TCP) only describes the hop between phone
     * and modem; the modem still puts the packet on the air over LoRa, which is
     * the part that costs seconds.
     */
    private val EXPENSIVE_INTERFACES = setOf("RNode")

    /**
     * Cost of the interface a peer was last heard on.
     *
     * @param interfaceType the recorded type, e.g. "TCPClient", "AndroidBLE",
     *   "AutoInterface", "RNode"
     * @return [LinkCost.EXPENSIVE] for radio links; [LinkCost.CHEAP] otherwise
     */
    fun costOf(interfaceType: String?): LinkCost =
        if (interfaceType != null && EXPENSIVE_INTERFACES.any { it.equals(interfaceType, ignoreCase = true) }) {
            LinkCost.EXPENSIVE
        } else {
            LinkCost.CHEAP
        }

    /**
     * Cost of a typed interface.
     *
     * Preferred over the string overload wherever the enum is available: it
     * cannot drift when an interface is renamed, and the compiler flags a new
     * variant that has not been classified.
     */
    fun costOf(interfaceType: InterfaceType): LinkCost =
        when (interfaceType) {
            InterfaceType.RNODE -> LinkCost.EXPENSIVE
            InterfaceType.AUTO,
            InterfaceType.TCP_CLIENT,
            InterfaceType.TCP_SERVER,
            InterfaceType.BLE,
            -> LinkCost.CHEAP
            // Anything added later defaults to cheap, matching the string
            // overload: spending airtime is the recoverable mistake.
            else -> LinkCost.CHEAP
        }

    /** Cheapest cost across the interfaces a peer was heard on. See [costOfAny]. */
    fun costOfTypes(interfaceTypes: Collection<InterfaceType>): LinkCost =
        if (interfaceTypes.isEmpty() || interfaceTypes.any { costOf(it) == LinkCost.CHEAP }) {
            LinkCost.CHEAP
        } else {
            LinkCost.EXPENSIVE
        }

    /**
     * Cost when a peer has been heard on several interfaces.
     *
     * Takes the cheapest, because that is the one the message will actually take:
     * Reticulum routes over the best available path, and assuming the worst would
     * withhold protection from a conversation that is in fact travelling over
     * Wi-Fi.
     */
    fun costOfAny(interfaceTypes: Collection<String>): LinkCost =
        if (interfaceTypes.isEmpty()) {
            // Nothing recorded yet. Treat as cheap: an unknown path is far more
            // often IP than LoRa, and being wrong here costs airtime rather than
            // secrecy.
            LinkCost.CHEAP
        } else if (interfaceTypes.any { costOf(it) == LinkCost.CHEAP }) {
            LinkCost.CHEAP
        } else {
            LinkCost.EXPENSIVE
        }
}
