package network.zamolxis.app.rns.api.util

import android.app.Application
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.msgpack.core.MessagePack
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The post-quantum fingerprint rides in element 2 of a peer announce's `app_data`.
 *
 * Unlike the rest of that array it is a Zamolxis extension, so everything here is
 * about tolerating what other clients put in the same slot rather than trusting
 * that it is ours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppDataPqFingerprintTest {
    private val fingerprint = ByteArray(16) { it.toByte() }

    /** Mirrors NativeRnsBackendImpl.buildPeerAnnounceAppData. */
    private fun announce(
        displayName: String = "peer",
        pqFingerprint: ByteArray? = null,
    ): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        packer.packArrayHeader(if (pqFingerprint != null) 3 else 2)
        packer.packBinaryHeader(nameBytes.size)
        packer.writePayload(nameBytes)
        packer.packNil()
        if (pqFingerprint != null) {
            packer.packBinaryHeader(pqFingerprint.size)
            packer.writePayload(pqFingerprint)
        }
        return packer.toByteArray()
    }

    @Test
    fun `reads a fingerprint the announce carries`() {
        assertArrayEquals(fingerprint, AppDataParser.parsePqFingerprint(announce(pqFingerprint = fingerprint)))
    }

    @Test
    fun `an announce without one yields null`() {
        assertNull(AppDataParser.parsePqFingerprint(announce()))
    }

    @Test
    fun `null and empty app_data yield null`() {
        assertNull(AppDataParser.parsePqFingerprint(null))
        assertNull(AppDataParser.parsePqFingerprint(ByteArray(0)))
    }

    @Test
    fun `adding the fingerprint does not disturb the standard fields`() {
        // The whole approach rests on this: appending must stay invisible to
        // anything reading the LXMF elements by index.
        val withFingerprint = announce("Reporter", fingerprint)

        assertEquals("Reporter", AppDataParser.parseDisplayName(withFingerprint, Aspects.LXMF_DELIVERY))
        assertNull(AppDataParser.parsePeerStampCost(withFingerprint))
    }

    @Test
    fun `a non-msgpack announce yields null instead of throwing`() {
        // NomadNet node announces are a bare UTF-8 name, not an array.
        assertNull(AppDataParser.parsePqFingerprint("just a node name".toByteArray()))
    }

    @Test
    fun `a foreign value in slot 2 is ignored`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(3)
        packer.packBinaryHeader(4)
        packer.writePayload("peer".toByteArray())
        packer.packNil()
        packer.packString("something else entirely")

        assertNull(AppDataParser.parsePqFingerprint(packer.toByteArray()))
    }

    @Test
    fun `an oversized fingerprint is refused rather than allocated`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(3)
        packer.packBinaryHeader(4)
        packer.writePayload("peer".toByteArray())
        packer.packNil()
        // Claim far more than any digest would need.
        packer.packBinaryHeader(100_000)
        packer.writePayload(ByteArray(100_000))

        assertNull(AppDataParser.parsePqFingerprint(packer.toByteArray()))
    }

    @Test
    fun `an empty fingerprint is refused`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(3)
        packer.packBinaryHeader(4)
        packer.writePayload("peer".toByteArray())
        packer.packNil()
        packer.packBinaryHeader(0)

        assertNull(AppDataParser.parsePqFingerprint(packer.toByteArray()))
    }

    @Test
    fun `a truncated announce yields null instead of throwing`() {
        val full = announce(pqFingerprint = fingerprint)

        for (length in listOf(1, 3, 8, full.size - 1)) {
            assertNull(
                "truncating to $length bytes must not throw",
                AppDataParser.parsePqFingerprint(full.copyOf(length)),
            )
        }
    }

    @Test
    fun `a stamp cost alongside the fingerprint still reads`() {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(3)
        packer.packBinaryHeader(4)
        packer.writePayload("peer".toByteArray())
        packer.packInt(8)
        packer.packBinaryHeader(fingerprint.size)
        packer.writePayload(fingerprint)
        val appData = packer.toByteArray()

        assertEquals(8, AppDataParser.parsePeerStampCost(appData))
        assertArrayEquals(fingerprint, AppDataParser.parsePqFingerprint(appData))
    }
}
