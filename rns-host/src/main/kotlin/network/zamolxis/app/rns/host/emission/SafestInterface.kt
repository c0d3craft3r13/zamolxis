package network.zamolxis.app.rns.host.emission

import network.zamolxis.app.rns.api.model.EmissionMedium
import network.zamolxis.app.rns.api.model.InterfaceConfig
import network.zamolxis.app.rns.api.model.emissionMedium

/**
 * Which interface an announce should leave by, when the device gets to choose.
 *
 * ## Why an announce is worth steering at all
 *
 * An announce publishes this identity's public key in the clear, to everyone in
 * earshot, on a schedule. Where it goes out therefore decides who gets handed
 * that key: a network peer, or anyone with a receiver within radio range of the
 * operator. The content is identical; only the audience differs, and the
 * audience is the whole question.
 *
 * ## The order
 *
 * Safest first, and "safe" here means one thing only — how much a listener
 * learns about *where the operator is* by hearing it.
 *
 *  1. [EmissionMedium.NETWORK] — observed, never triangulated.
 *  2. [EmissionMedium.LORA_REMOTE] — radiates, but from hardware the operator
 *     is not standing next to. A fix lands on the box.
 *  3. [EmissionMedium.BLUETOOTH_LE] — radiates a few metres. Whoever hears it
 *     is close enough to look, so it gives away little that being seen would
 *     not, but it is still a transmission from the operator's own position.
 *  4. [EmissionMedium.LORA_CARRIED] — radiates far, from the operator's pocket.
 *     Last by a wide margin.
 *
 * ## What this deliberately does not do
 *
 * It does not suppress the announce when only a radio is available. A node that
 * never announces on the only interface it has is a node nobody can reach,
 * which is a different product. Choosing the safest available is the whole
 * intent; refusing to speak at all belongs to radio silence, which the operator
 * turns on knowing the cost.
 */
object SafestInterface {
    /**
     * How exposing each medium is, lowest first. The order is the policy; the
     * media themselves only state what they are.
     */
    private val RISK_ORDER =
        listOf(
            EmissionMedium.NETWORK,
            EmissionMedium.LORA_REMOTE,
            EmissionMedium.BLUETOOTH_LE,
            EmissionMedium.LORA_CARRIED,
        )

    /**
     * The name of the safest enabled interface, or null when there is no
     * meaningful choice to make.
     *
     * Null means "announce the way the stack would anyway". That covers an empty
     * list and a list with nothing enabled — in both cases naming an interface
     * would be inventing one.
     */
    fun among(interfaces: List<InterfaceConfig>): String? =
        interfaces
            .filter { it.enabled }
            .minByOrNull { RISK_ORDER.indexOf(it.emissionMedium) }
            ?.name

    /** The ranking itself, exposed so a test can pin the order rather than infer it. */
    fun riskOrder(): List<EmissionMedium> = RISK_ORDER
}
