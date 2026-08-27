package network.zamolxis.app.service.group

import network.zamolxis.app.rns.api.util.LxmfFields
import network.zamolxis.app.service.group.GroupWireCodec.GroupBody
import network.zamolxis.app.service.group.GroupWireCodec.GroupCtl
import network.zamolxis.app.service.group.GroupWireCodec.GroupEnvelope
import network.zamolxis.app.service.group.GroupWireCodec.MemberEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The group envelope is a wire format two builds of the app have to agree on,
 * and it is also the boundary past which a malformed message must not travel —
 * so these tests pin both the round trips and every rejection.
 */
class GroupWireCodecTest {
    private val gid = "0123456789abcdef0123456789abcdef"
    private val mid = "3f6f9bda-4b1f-4a2c-9f4a-2b7e6c1d8a90"

    private fun membersSyncEnvelope() =
        GroupEnvelope(
            gid = gid,
            mid = mid,
            ctl = GroupCtl.MEMBERS_SYNC,
            body =
                GroupBody.MembersSync(
                    name = "Ridge relay ops",
                    createdBy = "aabbccddeeff00112233445566778899",
                    createdAt = 1_756_000_000_000L,
                    members =
                        listOf(
                            MemberEntry("aabbccddeeff00112233445566778899", "ADMIN"),
                            MemberEntry("00112233445566778899aabbccddeeff", "MEMBER"),
                        ),
                ),
        )

    private fun roundTrip(envelope: GroupEnvelope) = GroupWireCodec.fromMap(GroupWireCodec.toMap(envelope))

    @Test
    fun `a plain group message round-trips with no control keys`() {
        val envelope = GroupEnvelope(gid = gid, mid = mid)

        val map = GroupWireCodec.toMap(envelope)

        assertTrue("ctl" !in map)
        assertTrue("body" !in map)
        assertEquals(envelope, roundTrip(envelope))
    }

    @Test
    fun `a members sync round-trips with its full roster`() {
        assertEquals(membersSyncEnvelope(), roundTrip(membersSyncEnvelope()))
    }

    @Test
    fun `a group rename round-trips`() {
        val envelope =
            GroupEnvelope(
                gid = gid,
                mid = mid,
                ctl = GroupCtl.GROUP_UPDATED,
                body = GroupBody.GroupUpdated("new name"),
            )

        assertEquals(envelope, roundTrip(envelope))
    }

    @Test
    fun `a member-left round-trips with an empty body`() {
        val envelope = GroupEnvelope(gid = gid, mid = mid, ctl = GroupCtl.MEMBER_LEFT)

        assertEquals(emptyMap<String, Any>(), GroupWireCodec.toMap(envelope)["body"])
        assertEquals(envelope, roundTrip(envelope))
    }

    @Test
    fun `numbers arriving as Long parse the same as Int`() {
        // msgpack decodes every integer as Long; the fieldsJson leg may hand
        // over Int. Both must land in the same envelope.
        val map = GroupWireCodec.toMap(membersSyncEnvelope()).toMutableMap()
        map["v"] = 1L
        val body = (map["body"] as Map<*, *>).toMutableMap()
        body["createdAt"] = 1_756_000_000_000L
        map["body"] = body

        assertEquals(membersSyncEnvelope(), GroupWireCodec.fromMap(map))
    }

    // ------------------------------------------------------------ rejections

    @Test
    fun `a version other than 1 is rejected`() {
        val map = GroupWireCodec.toMap(membersSyncEnvelope()).toMutableMap()
        map["v"] = 2

        assertNull(GroupWireCodec.fromMap(map))
    }

    @Test
    fun `a missing version is rejected`() {
        val map = GroupWireCodec.toMap(membersSyncEnvelope()).toMutableMap()
        map.remove("v")

        assertNull(GroupWireCodec.fromMap(map))
    }

    @Test
    fun `a malformed gid is rejected`() {
        val base = GroupWireCodec.toMap(membersSyncEnvelope()).toMutableMap()

        for (bad in listOf("ABCDEF0123456789ABCDEF0123456789", "abc", "g123456789abcdef0123456789abcdef")) {
            base["gid"] = bad
            assertNull("gid '$bad' must be rejected", GroupWireCodec.fromMap(base))
        }
    }

    @Test
    fun `a malformed mid is rejected`() {
        val map = GroupWireCodec.toMap(membersSyncEnvelope()).toMutableMap()
        map["mid"] = "not-a-uuid"

        assertNull(GroupWireCodec.fromMap(map))
    }

    @Test
    fun `an unknown ctl string is rejected`() {
        val map = GroupWireCodec.toMap(membersSyncEnvelope()).toMutableMap()
        map["ctl"] = "DISBAND"

        assertNull(GroupWireCodec.fromMap(map))
    }

    @Test
    fun `a members sync without its body is rejected`() {
        val map = GroupWireCodec.toMap(membersSyncEnvelope()).toMutableMap()
        map.remove("body")

        assertNull(GroupWireCodec.fromMap(map))
    }

    @Test
    fun `a members sync with a missing body field is rejected`() {
        val map = GroupWireCodec.toMap(membersSyncEnvelope()).toMutableMap()
        val body = (map["body"] as Map<*, *>).toMutableMap()
        body.remove("createdAt")
        map["body"] = body

        assertNull(GroupWireCodec.fromMap(map))
    }

    @Test
    fun `a member entry missing its role is rejected`() {
        val map = GroupWireCodec.toMap(membersSyncEnvelope()).toMutableMap()
        val body = (map["body"] as Map<*, *>).toMutableMap()
        body["members"] = listOf(mapOf("h" to "aabbccddeeff00112233445566778899"))
        map["body"] = body

        assertNull(GroupWireCodec.fromMap(map))
    }

    @Test
    fun `a group rename without a name is rejected`() {
        val map =
            GroupWireCodec
                .toMap(
                    GroupEnvelope(
                        gid = gid,
                        mid = mid,
                        ctl = GroupCtl.GROUP_UPDATED,
                        body = GroupBody.GroupUpdated("x"),
                    ),
                ).toMutableMap()
        map["body"] = emptyMap<String, Any>()

        assertNull(GroupWireCodec.fromMap(map))
    }

    // --------------------------------------------------- extraction from fields

    @Test
    fun `extractFromFields pulls the envelope from the custom-meta field`() {
        val envelope = membersSyncEnvelope()
        val fields =
            mapOf<Int, Any>(
                LxmfFields.FIELD_CUSTOM_META to
                    mapOf(LxmfFields.CUSTOM_META_KEY_GROUP to GroupWireCodec.toMap(envelope)),
            )

        assertEquals(envelope, GroupWireCodec.extractFromFields(fields))
    }

    @Test
    fun `extractFromFields tolerates fields with no group envelope`() {
        assertNull(GroupWireCodec.extractFromFields(emptyMap()))
        // Telemetry extras share 0xFD and must not be misread as a group frame.
        assertNull(
            GroupWireCodec.extractFromFields(
                mapOf(LxmfFields.FIELD_CUSTOM_META to mapOf("cease" to 123L)),
            ),
        )
        assertNull(
            GroupWireCodec.extractFromFields(
                mapOf(LxmfFields.FIELD_CUSTOM_META to "not-a-map"),
            ),
        )
    }

    @Test
    fun `extractFromFieldsJson pulls the envelope from the serialized fields`() {
        val envelope = membersSyncEnvelope()
        // The shape AppDataParser.serializeFieldsToJson produces: field numbers
        // as decimal string keys, nested maps as nested objects.
        val fieldsJson =
            """{"253": {"zgroup": {"v": 1, "gid": "$gid", "mid": "$mid",""" +
                """ "ctl": "MEMBERS_SYNC", "body": {"name": "Ridge relay ops",""" +
                """ "createdBy": "aabbccddeeff00112233445566778899",""" +
                """ "createdAt": 1756000000000, "members": [""" +
                """{"h": "aabbccddeeff00112233445566778899", "role": "ADMIN"},""" +
                """{"h": "00112233445566778899aabbccddeeff", "role": "MEMBER"}]}}}}}"""

        assertEquals(envelope, GroupWireCodec.extractFromFieldsJson(fieldsJson))
    }

    @Test
    fun `extractFromFieldsJson returns null for garbage and absence`() {
        assertNull(GroupWireCodec.extractFromFieldsJson(null))
        assertNull(GroupWireCodec.extractFromFieldsJson(""))
        assertNull(GroupWireCodec.extractFromFieldsJson("not json {{"))
        assertNull(GroupWireCodec.extractFromFieldsJson("""{"6": ["png", "data"]}"""))
        // Telemetry extras under 0xFD without the zgroup key.
        assertNull(GroupWireCodec.extractFromFieldsJson("""{"253": {"cease": "abcd"}}"""))
        // A zgroup value that is not an object.
        assertNull(GroupWireCodec.extractFromFieldsJson("""{"253": {"zgroup": "nope"}}"""))
    }
}
