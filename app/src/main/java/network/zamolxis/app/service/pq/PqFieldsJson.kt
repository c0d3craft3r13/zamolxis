package network.zamolxis.app.service.pq

import android.util.Log
import network.zamolxis.app.data.model.PqProtection
import network.zamolxis.app.rns.api.util.AppDataParser
import network.zamolxis.app.rns.api.util.LxmfFields
import network.zamolxis.crypto.pq.PqEnvelope
import org.json.JSONObject

/**
 * Bridges the received-message field representation to the one the hybrid layer
 * speaks.
 *
 * Outgoing fields are handed over as `Map<Int, ByteArray>` and packed into LXMF
 * as msgpack binary. Coming back the other way they have been through
 * `AppDataParser.serializeFieldsToJson`, which renders a `ByteArray` as a
 * lowercase hex string under the field number as a decimal key. This undoes
 * exactly that, and nothing else.
 */
object PqFieldsJson {
    private const val TAG = "PqFieldsJson"

    /**
     * Post-quantum fields from a received message's `fieldsJson`.
     *
     * @return only the fields this layer owns; an empty map when the message has
     *   none, which is the common case for ordinary LXMF traffic
     */
    fun extract(fieldsJson: String?): Map<Int, ByteArray> {
        if (fieldsJson.isNullOrBlank()) return emptyMap()

        return try {
            val json = JSONObject(fieldsJson)
            buildMap {
                for (field in listOf(PqEnvelope.FIELD_SENDER_KEY, PqEnvelope.FIELD_SEALED_CONTENT)) {
                    val hex = json.optString(field.toString(), "")
                    if (hex.isNotEmpty()) {
                        hexToBytes(hex)?.let { put(field, it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read post-quantum fields from message", e)
            emptyMap()
        }
    }

    /**
     * The fields to store against a received message.
     *
     * Three things happen here, and all three matter:
     *
     *  * anything recovered from inside the seal is put back under the field
     *    number the sender removed it from, rendered by the same serializer both
     *    backends use for unsealed fields — so a rebuilt attachment is
     *    indistinguishable from one that never travelled sealed;
     *  * the sealed blob is dropped once opened, because keeping it would store a
     *    second copy of every attachment as hex;
     *  * the blob is *kept* when the message could not be opened, since that
     *    ciphertext is the only copy and a later key-change resolution may still
     *    open it.
     *
     * The sender-key field is always dropped: it has already been taken in, and
     * it is 1217 bytes of no further use.
     */
    fun storedFieldsFor(
        receivedFieldsJson: String?,
        incoming: PqMessageSealer.Incoming,
    ): String? {
        if (incoming.protection == PqProtection.UNOPENED) return receivedFieldsJson
        return try {
            val merged = JSONObject(receivedFieldsJson?.takeIf { it.isNotBlank() } ?: "{}")
            merged.remove(PqEnvelope.FIELD_SENDER_KEY.toString())
            merged.remove(PqEnvelope.FIELD_SEALED_CONTENT.toString())

            if (incoming.unsealedFields.isNotEmpty()) {
                val rebuilt = AppDataParser.serializeFieldsToJson(incoming.unsealedFields)
                if (rebuilt == null) {
                    // The payload opened but cannot be rendered into fields. Better
                    // to keep the text and lose the attachment than to lose both.
                    Log.e(TAG, "Could not serialize unsealed fields; storing message without them")
                } else {
                    val rebuiltJson = JSONObject(rebuilt)
                    rebuiltJson.keys().forEach { key -> merged.put(key, rebuiltJson.get(key)) }
                }
            }

            merged.takeIf { it.length() > 0 }?.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Could not assemble stored fields; keeping what arrived", e)
            receivedFieldsJson
        }
    }

    /**
     * Whether a received message carries payload that rode *outside* the seal.
     *
     * The sender's oversize fallback leaves a large attachment in its own LXMF
     * field and seals only the text. The receiver has to know, or it would record
     * a message with an unencrypted photo in it as fully protected.
     */
    fun hasUnsealedAttachments(fieldsJson: String?): Boolean {
        if (fieldsJson.isNullOrBlank()) return false
        return try {
            val json = JSONObject(fieldsJson)
            ATTACHMENT_FIELDS.any { json.has(it.toString()) }
        } catch (e: Exception) {
            // A blob we cannot parse might carry anything. Reporting "attachments
            // present" understates protection, which is the safe direction to be
            // wrong in.
            Log.w(TAG, "Could not inspect message fields for attachments", e)
            true
        }
    }

    /** Image, file-attachment and audio field numbers — payload this layer leaves in the clear. */
    private val ATTACHMENT_FIELDS =
        setOf(
            LxmfFields.FIELD_FILE_ATTACHMENTS,
            LxmfFields.FIELD_IMAGE,
            LxmfFields.FIELD_AUDIO,
        )

    /**
     * Decode a hex string, or null if it is not one.
     *
     * Returns null rather than a partial result: a half-decoded key or payload
     * would be handed to the crypto layer as though it were real, and the layer
     * would then reject it with a message about tampering rather than about a
     * malformed field.
     */
    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val high = Character.digit(hex[i * 2], HEX_RADIX)
            val low = Character.digit(hex[i * 2 + 1], HEX_RADIX)
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private const val HEX_RADIX = 16
}
