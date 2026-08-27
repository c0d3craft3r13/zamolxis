package network.zamolxis.app.service.group

import network.zamolxis.app.rns.api.util.LxmfFields
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Wire format for fan-out group chat.
 *
 * The group envelope is a nested `Map<String, Any>` — NOT a JSON string — so it
 * survives both legs it travels:
 *  - inside a post-quantum seal it is msgpack-packed verbatim by
 *    `PqSealedPayloadCodec` under key [LxmfFields.FIELD_CUSTOM_META];
 *  - outside the seal it rides the LXMF fields map as
 *    `fields[0xFD][CUSTOM_META_KEY_GROUP]`, and the backends' fields
 *    serializers (`AppDataParser.serializeFieldsToJson`) render it as nested
 *    JSON objects.
 *
 * Wire shape:
 * ```
 * { "v": 1, "gid": "<32 lowercase hex chars>", "mid": "<uuid string>",
 *   "ctl": "MEMBERS_SYNC" | "GROUP_UPDATED" | "MEMBER_LEFT", // absent for ordinary messages
 *   "body": { ... } }                                        // absent when ctl is absent
 * ```
 *
 * [fromMap] is strict about shape but lenient about numeric types: a value
 * that went through msgpack arrives as `Long`, one that went through the
 * fieldsJson JSON may arrive as `Int`. Any violation returns null and the
 * caller drops the message — a malformed envelope must never reach the group
 * store half-parsed.
 */
object GroupWireCodec {
    /** Current envelope version; anything else is dropped. */
    const val VERSION = 1

    /** gid is 16 bytes rendered as lowercase hex. */
    private val GID_PATTERN = Regex("[0-9a-f]{32}")

    /** The control-message kinds v1 knows. Absent = ordinary group message. */
    enum class GroupCtl { MEMBERS_SYNC, GROUP_UPDATED, MEMBER_LEFT }

    /** One entry in a [MembersSyncBody] roster. */
    data class MemberEntry(
        val hash: String,
        val role: String,
    )

    /** Body of the control message identified by [GroupEnvelope.ctl]. */
    sealed interface GroupBody {
        /**
         * Full roster broadcast — sent by the group creator so late joiners can
         * rebuild membership without having seen the original adds.
         */
        data class MembersSync(
            val name: String,
            val createdBy: String,
            val createdAt: Long,
            val members: List<MemberEntry>,
        ) : GroupBody

        /** A mutable group attribute changed; v1 carries only the display name. */
        data class GroupUpdated(
            val name: String,
        ) : GroupBody
    }

    /**
     * One group message or control frame.
     *
     * @property v wire version — always [VERSION] on encode
     * @property gid group id, 32 lowercase hex chars
     * @property mid message id, a UUID string; receivers deduplicate fan-out copies on it
     * @property ctl control-message kind, null for an ordinary group message
     * @property body the control payload matching [ctl], null for ordinary
     *   messages and [GroupCtl.MEMBER_LEFT] (which needs nothing beyond the
     *   sender's identity)
     */
    data class GroupEnvelope(
        val gid: String,
        val mid: String,
        val ctl: GroupCtl? = null,
        val body: GroupBody? = null,
        val v: Int = VERSION,
    )

    /** The envelope as the nested map that goes on the wire. */
    fun toMap(envelope: GroupEnvelope): Map<String, Any> =
        buildMap {
            put("v", envelope.v)
            put("gid", envelope.gid)
            put("mid", envelope.mid)
            envelope.ctl?.let { ctl ->
                put("ctl", ctl.name)
                when (val body = envelope.body) {
                    is GroupBody.MembersSync ->
                        put(
                            "body",
                            mapOf(
                                "name" to body.name,
                                "createdBy" to body.createdBy,
                                "createdAt" to body.createdAt,
                                "members" to
                                    body.members.map {
                                        mapOf("h" to it.hash, "role" to it.role)
                                    },
                            ),
                        )
                    is GroupBody.GroupUpdated -> put("body", mapOf("name" to body.name))
                    null -> put("body", emptyMap<String, Any>())
                }
            }
        }

    /**
     * Parse a wire map back into an envelope, or null if anything is off:
     * wrong version, malformed gid/mid, unknown ctl, missing or mistyped body
     * fields. Numbers may be Int or Long depending on whether the map came out
     * of msgpack or JSON.
     */
    @Suppress("ReturnCount") // A validation parser — every early null is one rejected shape.
    fun fromMap(map: Map<*, *>): GroupEnvelope? {
        val v = (map["v"] as? Number)?.toLong() ?: return null
        if (v != VERSION.toLong()) return null

        val gid = map["gid"] as? String ?: return null
        if (!GID_PATTERN.matches(gid)) return null

        val mid = map["mid"] as? String ?: return null
        try {
            UUID.fromString(mid)
        } catch (_: IllegalArgumentException) {
            return null
        }

        val ctl =
            when (val ctlString = map["ctl"]) {
                null -> null
                is String -> GroupCtl.entries.firstOrNull { it.name == ctlString } ?: return null
                else -> return null
            }

        val body =
            when (ctl) {
                GroupCtl.MEMBERS_SYNC -> parseMembersSync(map["body"]) ?: return null
                GroupCtl.GROUP_UPDATED -> parseGroupUpdated(map["body"]) ?: return null
                GroupCtl.MEMBER_LEFT, null -> null
            }

        return GroupEnvelope(gid = gid, mid = mid, ctl = ctl, body = body)
    }

    /**
     * The envelope from a decoded LXMF fields map — `fields[0xFD]["zgroup"]`.
     * Returns null when the field is absent or not a map (telemetry extras
     * share [LxmfFields.FIELD_CUSTOM_META] and must not be misread).
     */
    fun extractFromFields(fields: Map<Int, Any>): GroupEnvelope? {
        val meta = fields[LxmfFields.FIELD_CUSTOM_META] as? Map<*, *> ?: return null
        val zgroup = meta[LxmfFields.CUSTOM_META_KEY_GROUP] as? Map<*, *> ?: return null
        return fromMap(zgroup)
    }

    /**
     * The envelope from the `fieldsJson` string the app layer holds, whose
     * shape is `{"253": {"zgroup": {...}}}`. Parsed with org.json, the same
     * parser `PqFieldsJson` uses, so both layers read the field identically.
     */
    @Suppress("ReturnCount") // Early nulls for absent/misshaped layers of the envelope.
    fun extractFromFieldsJson(fieldsJson: String?): GroupEnvelope? {
        if (fieldsJson.isNullOrBlank()) return null
        return try {
            val root = JSONObject(fieldsJson)
            val meta = root.optJSONObject(LxmfFields.FIELD_CUSTOM_META.toString()) ?: return null
            val zgroup = meta.optJSONObject(LxmfFields.CUSTOM_META_KEY_GROUP) ?: return null
            fromMap(jsonToMap(zgroup))
        } catch (_: Exception) {
            null
        }
    }

    // ReturnCount: roster validation rejects one bad field at a time.
    @Suppress("ReturnCount")
    private fun parseMembersSync(body: Any?): GroupBody.MembersSync? {
        val map = body as? Map<*, *> ?: return null
        val name = map["name"] as? String ?: return null
        val createdBy = map["createdBy"] as? String ?: return null
        val createdAt = (map["createdAt"] as? Number)?.toLong() ?: return null
        val membersList = map["members"] as? List<*> ?: return null
        val members =
            membersList.map { entry ->
                val entryMap = entry as? Map<*, *> ?: return null
                val hash = entryMap["h"] as? String ?: return null
                val role = entryMap["role"] as? String ?: return null
                MemberEntry(hash = hash, role = role)
            }
        return GroupBody.MembersSync(
            name = name,
            createdBy = createdBy,
            createdAt = createdAt,
            members = members,
        )
    }

    private fun parseGroupUpdated(body: Any?): GroupBody.GroupUpdated? {
        val map = body as? Map<*, *> ?: return null
        val name = map["name"] as? String ?: return null
        return GroupBody.GroupUpdated(name)
    }

    /**
     * JSONObject → the plain nested map [fromMap] consumes. Written by hand
     * rather than `JSONObject.toMap()` because the framework's org.json (what
     * runs on-device) does not guarantee that method.
     */
    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for (key in obj.keys()) {
            map[key] = jsonToValue(obj.get(key))
        }
        return map
    }

    private fun jsonToValue(value: Any?): Any? =
        when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonToMap(value)
            is JSONArray -> List(value.length()) { jsonToValue(value.get(it)) }
            else -> value
        }
}
