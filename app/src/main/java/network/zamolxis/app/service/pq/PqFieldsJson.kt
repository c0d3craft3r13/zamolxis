package network.zamolxis.app.service.pq

import android.util.Log
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
