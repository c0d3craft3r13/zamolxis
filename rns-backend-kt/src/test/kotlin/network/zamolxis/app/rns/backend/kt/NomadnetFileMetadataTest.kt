package network.zamolxis.app.rns.backend.kt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.msgpack.core.MessagePack

/**
 * `parseNomadnetFileMetadata` runs on bytes that came off the mesh from a remote
 * NomadNet node, so every malformed shape has to come back as `null` rather than
 * throw into the request coroutine. It also has to tell a file response apart from
 * an ordinary page response, which is decided purely by the presence of a `name`
 * key.
 */
class NomadnetFileMetadataTest {
    private val handler = NativeNomadNetHandler(appContext = null, deliveryIdentityProvider = { null })

    private fun packMap(entries: List<Pair<String, Any?>>): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(entries.size)
        entries.forEach { (key, value) ->
            packer.packString(key)
            when (value) {
                null -> packer.packNil()
                is String -> packer.packString(value)
                is Long -> packer.packLong(value)
                is Int -> packer.packInt(value)
                is Boolean -> packer.packBoolean(value)
                else -> error("unsupported test value $value")
            }
        }
        return packer.toByteArray()
    }

    @Test
    fun `reads name and size off a file response`() {
        val bytes = packMap(listOf("name" to "manual.pdf", "size" to 4096L))

        val meta = handler.parseNomadnetFileMetadata(bytes)

        assertEquals("manual.pdf", meta?.get("name"))
        assertEquals(4096L, meta?.get("size"))
    }

    @Test
    fun `integers widen to Long regardless of how they were packed`() {
        val bytes = packMap(listOf("name" to "a.bin", "size" to 7))

        val meta = handler.parseNomadnetFileMetadata(bytes)

        assertEquals("Callers read size as Long; a packed int must not surface as Int", 7L, meta?.get("size"))
    }

    @Test
    fun `a map without name is not a file response`() {
        val bytes = packMap(listOf("size" to 10L, "mtime" to 1L))

        assertNull(handler.parseNomadnetFileMetadata(bytes))
    }

    @Test
    fun `values of unhandled types become null without dropping the rest of the map`() {
        val bytes = packMap(listOf("name" to "a.bin", "readable" to true, "size" to 3L))

        val meta = handler.parseNomadnetFileMetadata(bytes)

        assertEquals("a.bin", meta?.get("name"))
        assertNull("Booleans are skipped, not parsed", meta?.get("readable"))
        assertEquals("Skipping a value must not desynchronise the reader", 3L, meta?.get("size"))
    }

    @Test
    fun `a page response is not mistaken for file metadata`() {
        val page = "`FHello, mesh".toByteArray(Charsets.UTF_8)

        assertNull(handler.parseNomadnetFileMetadata(page))
    }

    @Test
    fun `truncated bytes return null instead of throwing`() {
        val full = packMap(listOf("name" to "manual.pdf", "size" to 4096L))

        assertNull(handler.parseNomadnetFileMetadata(full.copyOf(full.size / 2)))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(handler.parseNomadnetFileMetadata(ByteArray(0)))
    }

    @Test
    fun `a non-map top level value returns null`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(2)
        packer.packString("name")
        packer.packString("manual.pdf")

        assertNull(handler.parseNomadnetFileMetadata(packer.toByteArray()))
    }

    @Test
    fun `a non-string key returns null rather than a half-read map`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packInt(1)
        packer.packString("manual.pdf")

        assertNull(handler.parseNomadnetFileMetadata(packer.toByteArray()))
    }
}
