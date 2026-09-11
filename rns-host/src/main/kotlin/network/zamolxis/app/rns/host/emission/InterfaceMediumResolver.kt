package network.zamolxis.app.rns.host.emission

import network.zamolxis.app.rns.api.model.EmissionMedium
import network.zamolxis.app.rns.api.model.InterfaceConfig
import network.zamolxis.app.rns.api.model.emissionMedium

/**
 * Works out what a destination's traffic will physically travel over.
 *
 * ## Why the obvious way is wrong
 *
 * The stack answers "which interface reaches this destination" with a name, and
 * that name is usually the one its owner typed. The Python backend reads
 * `iface.name` and only falls back to the class when it is blank; the Kotlin
 * backend returns the name and nothing else. So the string coming back is
 * "Home TCP" or "Roof antenna", not a type.
 *
 * Deciding whether padding is safe by reading that string would mean an
 * interface called "wifi" gets padded because of what it was called, while
 * being a LoRa radio. So the name is matched back to the interface the operator
 * actually configured, whose type is known for certain, and the type decides.
 *
 * ## What happens when it cannot tell
 *
 * Unknown means no padding. Silence costs a feature; guessing costs
 * transmissions from a position someone asked us not to give away, and every
 * way of being wrong here ends in the second. That covers a destination with no
 * known path, an interface added by the stack itself, and a name that matches
 * nothing — all of which are ordinary, none of which are worth a guess.
 */
class InterfaceMediumResolver(
    private val configuredInterfaces: () -> List<InterfaceConfig>,
) {
    /**
     * The medium behind [reportedName], or null if it cannot be established.
     *
     * Matching is on the configured name first, because that is what both
     * backends normally return. The bracketed form the stack falls back to
     * — `RNodeInterface[…]`, `TCPInterface[Server/…]` — is recognised too, since
     * an interface with no name of its own still has to be classified rather
     * than dropped into the unknown bucket by default.
     */
    fun mediumOf(reportedName: String?): EmissionMedium? {
        val name = reportedName?.trim().orEmpty()
        if (name.isEmpty()) return null

        val configured = configuredInterfaces().firstOrNull { it.name == name }
        return configured?.emissionMedium ?: mediumOfStackName(name)
    }

    /** Whether it is safe to put padding on the way to this destination. */
    fun mayPad(
        reportedName: String?,
        cover: AmbientCover,
    ): Boolean {
        val medium = mediumOf(reportedName) ?: return false
        return medium.hasCover(cover)
    }

    /**
     * The stack's own rendering of an interface, used only when nothing the
     * operator configured matches. `RNodeInterface` covers a radio on a cable
     * and one reached over the network alike; both are LoRa and neither is ever
     * padded, so the distinction that matters elsewhere does not matter here.
     */
    private fun mediumOfStackName(name: String): EmissionMedium? {
        val className = name.substringBefore('[').substringBefore('/').trim()
        return when {
            className.startsWith("RNode") -> EmissionMedium.LORA_CARRIED
            className.startsWith("BLE") || className.startsWith("AndroidBLE") -> EmissionMedium.BLUETOOTH_LE
            className.startsWith("TCP") -> EmissionMedium.NETWORK
            className.startsWith("UDP") -> EmissionMedium.NETWORK
            className.startsWith("Auto") -> EmissionMedium.NETWORK
            className.startsWith("I2P") -> EmissionMedium.NETWORK
            className.startsWith("Backbone") -> EmissionMedium.NETWORK
            else -> null
        }
    }
}
