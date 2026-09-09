package network.zamolxis.app.rns.backend.kt

import network.zamolxis.app.rns.api.util.PeerAnnounceAppData
import network.zamolxis.app.rns.api.util.toHex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-impl conformance: the announce `app_data` this app puts on the air must
 * be byte-identical to what upstream Python LXMF emits.
 *
 * ## Why this is a privacy test, not a compatibility one
 *
 * `app_data` rides in the clear inside every announce, and an announce is
 * rebroadcast across the mesh. Any byte here that upstream would not have
 * written is a public label saying which app this node is running. So the
 * assertion is not "close enough to interoperate" — it is equality.
 *
 * ## Ground truth
 *
 * Reference: `LXMRouter.get_announce_app_data()` packs
 * `[display_name_bytes_or_None, stamp_cost_or_None, [SF_COMPRESSION]]` via
 * `msgpack.packb(...)` (LXMF 1.1.0, the version vendored under
 * `vendor/python/LXMF`). This app registers its delivery identity without a
 * stamp cost, so the second element is always nil.
 *
 * The vectors below were produced by the Python reference itself, on msgpack
 * 1.2.2 (whose default `use_bin_type=True` encodes the name as a msgpack bin,
 * matching Kotlin's `packBinaryHeader`):
 *
 * ```
 * import msgpack
 * msgpack.packb([name.encode("utf-8"), None, [0x00]]).hex()
 * ```
 *
 * If this test fails, the announce has diverged from what every other LXMF node
 * emits and this app has become identifiable on the air. Re-derive the vectors
 * from the vendored LXMF before touching an assertion.
 *
 * ## The gap this test used to have
 *
 * It previously called the builder through an overload that omitted the
 * post-quantum fingerprint, while the production path always passed one. The
 * assertion held, the wire format diverged, and the test stayed green for the
 * entire life of the divergence. There is now no second shape to fall through
 * to: [PeerAnnounceAppData.build] takes a display name and nothing else, and
 * the backend entry point below is the one production calls.
 */
class PeerAnnounceAppDataConformanceTest {
    private fun announced(displayName: String): String =
        NativeRnsBackendImpl.buildPeerAnnounceAppData(displayName).toHex()

    @Test
    fun `ascii display name matches python msgpack bin encoding`() {
        // msgpack.packb([b"Test User", None, [0]]).hex()
        assertEquals("93c409546573742055736572c09100", announced("Test User"))
    }

    @Test
    fun `single char display name matches python`() {
        // msgpack.packb([b"A", None, [0]]).hex()
        assertEquals("93c40141c09100", announced("A"))
    }

    @Test
    fun `empty display name matches python (bin8 length zero)`() {
        // msgpack.packb([b"", None, [0]]).hex()
        assertEquals("93c400c09100", announced(""))
    }

    @Test
    fun `utf8 multibyte display name matches python byte-for-byte`() {
        // msgpack.packb(["Café ☕".encode("utf-8"), None, [0]]).hex()
        assertEquals("93c409436166c3a920e29895c09100", announced("Café ☕"))
    }

    /** Upstream packs nil for an identity with no display name at all. */
    @Test
    fun `absent display name matches python nil`() {
        // msgpack.packb([None, None, [0]]).hex()
        assertEquals("93c0c09100", PeerAnnounceAppData.build(null).toHex())
    }

    /**
     * The backend must not build its own shape. Both flavours announce through
     * the shared builder, and the Python flavour also has upstream's own
     * `router.announce()` available — all three have to agree byte for byte or
     * the two builds are distinguishable from each other on the air.
     */
    @Test
    fun `the backend entry point delegates to the shared builder`() {
        assertEquals(
            PeerAnnounceAppData.build("Delegation").toHex(),
            NativeRnsBackendImpl.buildPeerAnnounceAppData("Delegation").toHex(),
        )
    }

    /**
     * The tail of every announce is the capability list, and it must be a
     * msgpack array — not raw bytes, which is what a fingerprint packed into
     * that slot looked like.
     */
    @Test
    fun `the third element is a capability list and never raw bytes`() {
        val packed = PeerAnnounceAppData.build("Anyone")

        // 0x91 = fixarray(1), 0x00 = SF_COMPRESSION. A bin header would be 0xc4.
        assertEquals("9100", packed.copyOfRange(packed.size - 2, packed.size).toHex())
    }
}
