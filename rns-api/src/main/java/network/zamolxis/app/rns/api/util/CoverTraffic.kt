package network.zamolxis.app.rns.api.util

import network.zamolxis.app.rns.api.model.ReceivedMessage
import org.json.JSONObject
import java.security.SecureRandom

/**
 * A message sent to say nothing, so that saying something is less noticeable.
 *
 * ## What it is on the wire
 *
 * An ordinary LXMF message. Same envelope, same encryption, same delivery path
 * as a real one — the difference lives entirely inside the ciphertext, where an
 * observer cannot reach it. It carries no content and one field: padding, under
 * [CUSTOM_META_KEY_PADDING] inside [LxmfFields.FIELD_CUSTOM_META].
 *
 * ## Why it needs nothing on the receiving side
 *
 * [isUserVisibleChatMessage] already returns false for a message with no
 * content whose only field is custom metadata without the group key. So cover
 * traffic is discarded by the filter both backends already share, and there is
 * no new path for it to be handled wrongly on. [CoverTrafficVisibilityTest]
 * holds the two together: if that filter is ever widened in a way that would
 * render one of these, it fails.
 *
 * ## Why the padding is random rather than zeroes
 *
 * LXMF compresses payloads. A run of zeroes would collapse, and the message
 * would arrive on the wire at a size decided by the compressor rather than the
 * size that was chosen — which is the one property this exists to control.
 * Random bytes do not compress.
 *
 * ## What it does not do
 *
 * It does not make a real message unobservable. Cover raises the number of
 * transmissions an observer must explain; it does not remove the real one from
 * among them, because a real message is sent when the operator acts and padding
 * is not withheld to make room for it. Deciding *when* cover is worth sending
 * at all belongs to the emission policy, which asks whether there is anything
 * to hide among first.
 */
object CoverTraffic {
    /**
     * Key inside [LxmfFields.FIELD_CUSTOM_META] carrying the padding.
     *
     * Under 0xFD rather than a field number of its own, for the reason recorded
     * on [LxmfFields.FIELD_CUSTOM_META]: invented field IDs risk colliding with
     * whatever upstream LXMF assigns later. Other clients ignore 0xFD, so a
     * Sideband or MeshChat user who receives one of these sees nothing at all.
     */
    const val CUSTOM_META_KEY_PADDING = "zpad"

    /**
     * How many bytes of padding a cover message carries by default.
     *
     * Chosen to put it in the same single-packet class as an ordinary sealed
     * message — a two-hundred-character text under an established epoch
     * measures a little over two hundred bytes — so that size alone does not
     * separate the two populations.
     */
    const val DEFAULT_PADDING_BYTES = 200

    private val random = SecureRandom()

    /**
     * Fields for one cover message, ready for `extraFields` on the send call.
     *
     * @param paddingBytes how much padding to carry; must be positive.
     */
    fun fields(paddingBytes: Int = DEFAULT_PADDING_BYTES): Map<Int, Any> {
        require(paddingBytes > 0) { "cover traffic with no padding is an empty message, not cover" }
        val padding = ByteArray(paddingBytes).also(random::nextBytes)
        return mapOf(LxmfFields.FIELD_CUSTOM_META to mapOf(CUSTOM_META_KEY_PADDING to padding))
    }

    /**
     * Whether an inbound message is cover traffic and should be dropped without trace.
     *
     * Deliberately narrow: content must be empty and the padding key present.
     * A message that carries both padding and something real is not cover — it
     * is a real message, and treating it as cover would discard it.
     */
    fun isCoverTraffic(message: ReceivedMessage): Boolean {
        if (message.content.isNotBlank()) return false
        val json = message.fieldsJson?.takeIf { it.isNotEmpty() } ?: return false
        return try {
            JSONObject(json)
                .optJSONObject(LxmfFields.FIELD_CUSTOM_META.toString())
                ?.has(CUSTOM_META_KEY_PADDING) == true
        } catch (_: Exception) {
            false
        }
    }
}
