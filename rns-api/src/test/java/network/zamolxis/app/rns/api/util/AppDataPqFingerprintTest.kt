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
 * Reading a post-quantum fingerprint out of element 2 of a peer announce's
 * `app_data`.
 *
 * ## Why this still exists after the announce stopped carrying one
 *
 * This app no longer advertises a fingerprint. It labelled every announce as
 * ours on a channel that travels in the clear, and it duplicated a binding the
 * LXMF signature already provides — see [PeerAnnounceAppData].
 *
 * Reading one is a different question from writing one. Installs from before
 * that change still put a fingerprint there, and a label a peer volunteers
 * about itself costs us nothing to use. So the parser stays, and the announces
 * exercised here are built by hand rather than by [PeerAnnounceAppData], which
 * can no longer produce this shape.
 *
 * Element 2 belongs to upstream's `supported_functionality` list, so most of
 * what follows is about surviving what other clients legitimately put in that
 * slot rather than assuming it is ours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppDataPqFingerprintTest {
    private val fingerprint = ByteArray(16) { it.toByte() }

    /**
     * An announce in the shape older installs of this app emit: three elements
     * with raw fingerprint bytes where upstream keeps a capability list.
     * Nothing produces this any more, which is why it is packed here by hand.
     */
    private fun legacyAnnounce(
        displayName: String = "peer",
        pqFingerprint: ByteArray? = null,
    ): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        val name = displayName.toByteArray()
        packer.packArrayHeader(if (pqFingerprint != null) 3 else 2)
        packer.packBinaryHeader(name.size)
        packer.writePayload(name)
        packer.packNil()
        if (pqFingerprint != null) {
            packer.packBinaryHeader(pqFingerprint.size)
            packer.writePayload(pqFingerprint)
        }
        return packer.toByteArray()
    }

    @Test
    fun `reads a fingerprint the announce carries`() {
        assertArrayEquals(fingerprint, AppDataParser.parsePqFingerprint(legacyAnnounce(pqFingerprint = fingerprint)))
    }

    @Test
    fun `an announce without one yields null`() {
        assertNull(AppDataParser.parsePqFingerprint(legacyAnnounce()))
    }

    @Test
    fun `null and empty app_data yield null`() {
        assertNull(AppDataParser.parsePqFingerprint(null))
        assertNull(AppDataParser.parsePqFingerprint(ByteArray(0)))
    }

    @Test
    fun `adding the fingerprint does not disturb the standard fields`() {
        // An older peer's announce must still read correctly for everything
        // except the fingerprint — its name and stamp cost are where LXMF says.
        val withFingerprint = legacyAnnounce("Reporter", fingerprint)

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
        val full = legacyAnnounce(pqFingerprint = fingerprint)

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

    /**
     * The direction that matters for staying unremarkable on the air: whatever
     * this app announces today, a fingerprint is not in it.
     */
    @Test
    fun `our own announce carries no fingerprint to read`() {
        assertNull(AppDataParser.parsePqFingerprint(PeerAnnounceAppData.build("Reporter")))
    }
}
