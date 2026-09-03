package network.zamolxis.app.util

/**
 * The name the app invents for a peer that announced no name of its own.
 * Format: "Peer A1B2C3D4" — the first 8 hex characters of its destination hash.
 *
 * This is a placeholder, not a name: nobody chose it, and the same person gets a
 * different one per aspect, because `lxmf.delivery` and `lxst.telephony` hash to
 * different destinations. Anything that shows a name to a person should ask
 * [isGeneratedDisplayName] before treating one as the answer.
 */
private val GENERATED_NAME = Regex("^Peer [0-9A-F]{8}$")

const val UNKNOWN_PEER_NAME = "Unknown Peer"

/**
 * Generate the placeholder name for a destination hash.
 *
 * @param hashHex Hex string of the destination hash
 * @return e.g. "Peer 970A60FC", or [UNKNOWN_PEER_NAME] for a hash too short to name
 */
fun generatedDisplayNameFor(hashHex: String): String =
    if (hashHex.length >= 8) {
        "Peer ${hashHex.take(8).uppercase()}"
    } else {
        UNKNOWN_PEER_NAME
    }

/** As [generatedDisplayNameFor], for a hash still in bytes. */
fun generatedDisplayNameFor(hash: ByteArray): String = generatedDisplayNameFor(hash.joinToString("") { "%02x".format(it) })

/**
 * True when [name] is one this app invented rather than one the peer announced.
 *
 * A person is free to call themselves "Peer A1B2C3D4", and this says yes to that.
 * The cost of being wrong is showing their address instead of their chosen name —
 * cheap, and the opposite mistake is what puts an unrecognisable hash on a call screen.
 */
fun isGeneratedDisplayName(name: String?): Boolean = name == UNKNOWN_PEER_NAME || (name != null && GENERATED_NAME.matches(name))
