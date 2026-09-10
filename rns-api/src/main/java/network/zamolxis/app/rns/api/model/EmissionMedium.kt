package network.zamolxis.app.rns.api.model

/**
 * What an interface physically does when it carries a byte.
 *
 * ## Why this exists
 *
 * "Stay off the air" and "put noise on the air" are both correct advice, and
 * they are correct about different interfaces. Noise hides *when* a message
 * was sent and *how big* it was, which is what a network observer reads. It
 * does nothing about direction finding, which works on the physical signal
 * whatever is inside it — so on a radio, noise is not cover, it is more
 * chances to be located and, on LoRa, less airtime left for the message that
 * actually mattered. A single policy applied to every interface has to be
 * wrong on one half of them.
 *
 * This type is the fact the policy reads. It says only what an interface *is*.
 * What to do about it — go quiet, pad, stay as-is — is a decision that lives
 * with the policy, so that changing the decision does not mean changing this.
 *
 * ## Why the classification is exhaustive
 *
 * [emissionMedium] matches on the sealed [InterfaceConfig] rather than on
 * [InterfaceConfig.typeName], so a new interface type will not compile until
 * someone has said whether it radiates. An unclassified interface would
 * silently default to whatever the `else` branch said, and the default that
 * matters here is the one that puts an operator on the air without asking.
 */
enum class EmissionMedium {
    /**
     * LoRa from hardware the operator has on them — USB, Bluetooth Classic or
     * BLE link to an RNode in the same bag as the phone.
     *
     * A fix on this signal is a fix on the person.
     */
    LORA_CARRIED,

    /**
     * LoRa from an RNode reached over the network, so the radio is somewhere
     * the operator is not.
     *
     * Still a transmission and still spending the same airtime budget — but a
     * fix on it locates the box, not the person holding the phone. Worth
     * telling apart precisely because the difference is the whole point of
     * putting the radio somewhere else.
     */
    LORA_REMOTE,

    /**
     * Bluetooth Low Energy between phones.
     *
     * Radiates, so it is findable — but at a range measured in metres, which
     * means whoever hears it is already close enough to see the operator.
     */
    BLUETOOTH_LE,

    /** Carried over IP. A network observer reads it; no one triangulates it. */
    NETWORK,
    ;

    /** Whether bytes leave as a radio signal someone can hear without being on the network. */
    val radiates: Boolean get() = this != NETWORK

    /**
     * Whether hearing this signal locates the operator.
     *
     * The question silence is really asking. [LORA_REMOTE] radiates and is
     * still not this: it gives up the radio's position, which is why it was
     * put where it is.
     */
    val revealsOperatorPosition: Boolean get() = this == LORA_CARRIED || this == BLUETOOTH_LE

    /**
     * Whether transmissions here come out of a fixed budget.
     *
     * RNode firmware enforces short- and long-term airtime limits as a
     * percentage and holds transmissions once they are spent. Anything sent
     * for its own sake here is taken directly out of what is left for a real
     * message, at whatever moment that message arrives.
     */
    val hasAirtimeBudget: Boolean get() = this == LORA_CARRIED || this == LORA_REMOTE
}

/**
 * Which medium this interface puts bytes on.
 *
 * `RNode` splits on how the radio is reached: over TCP the hardware is
 * elsewhere, by USB or Bluetooth it is with the operator. Every other type
 * has one answer.
 */
val InterfaceConfig.emissionMedium: EmissionMedium
    get() =
        when (this) {
            is InterfaceConfig.RNode ->
                if (connectionMode == RNODE_CONNECTION_MODE_TCP) {
                    EmissionMedium.LORA_REMOTE
                } else {
                    EmissionMedium.LORA_CARRIED
                }

            is InterfaceConfig.AndroidBLE -> EmissionMedium.BLUETOOTH_LE

            is InterfaceConfig.AutoInterface -> EmissionMedium.NETWORK
            is InterfaceConfig.TCPClient -> EmissionMedium.NETWORK
            is InterfaceConfig.TCPServer -> EmissionMedium.NETWORK
            is InterfaceConfig.UDP -> EmissionMedium.NETWORK
        }

/**
 * The `connectionMode` that reaches an RNode over the network rather than over
 * a cable or a Bluetooth pairing.
 */
private const val RNODE_CONNECTION_MODE_TCP = "tcp"
