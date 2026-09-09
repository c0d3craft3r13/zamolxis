package network.zamolxis.app.rns.api.util

import org.msgpack.core.MessagePack

/**
 * The `app_data` payload attached to a peer (LXMF delivery) announce.
 *
 * ## Byte-identical to upstream, deliberately
 *
 * An announce is broadcast to the whole mesh and its `app_data` travels in the
 * clear — Reticulum signs it but does not encrypt it. Everything put here is
 * therefore readable by anyone within radio range of any node that forwards it,
 * which makes this the one place where a private messenger can most easily
 * announce which private messenger it is.
 *
 * Upstream LXMF 1.1.0 packs three elements — `[display_name, stamp_cost,
 * supported_functionality]` — where the third is a list of capability flags
 * (`LXMRouter.get_announce_app_data`). This builds exactly that and nothing
 * else, so a captured announce cannot separate this app from any other LXMF
 * node.
 *
 * ## What used to be here
 *
 * A 16-byte post-quantum key fingerprint was packed into the third slot. Two
 * things were wrong with it. It collided with `supported_functionality` rather
 * than extending the array — upstream survives that only because it checks
 * `type(peer_data[2]) == list` before use — and, more seriously, an array of
 * three whose third element is raw bytes is a shape nothing else on the mesh
 * produces. It marked every announce as ours, and it marked it *harder* the
 * more protection the user had switched on.
 *
 * Nothing was lost by removing it. Its job was to let a peer check a key that
 * arrived in a message against one the identity had advertised — and an LXMF
 * message is already signed by that same identity, so a key delivered inside a
 * signature-verified message carries the same binding, from the same key, with
 * a full signature instead of 128 bits of digest. What the fingerprint bought
 * beyond that was one extra state in the send policy, and
 * [network.zamolxis.crypto.pq.PqPolicy] resolved that state to the same
 * decision as its neighbour in every mode.
 *
 * **Lives in `:rns-api` so both backends build byte-identical announces**, and
 * `PeerAnnounceAppDataConformanceTest` holds them to vectors produced by the
 * Python reference itself.
 */
object PeerAnnounceAppData {
    /**
     * `LXMF.SF_COMPRESSION` — the one capability flag upstream advertises today
     * (`LXMF/LXMF.py`). Inlined because `:rns-api` sits below both LXMF stacks
     * and must not depend on either.
     */
    private const val SF_COMPRESSION = 0x00

    /**
     * Pack an announce payload identical to what upstream LXMF would emit.
     *
     * @param displayName the identity's display name, UTF-8 as a msgpack `bin`.
     *   Null packs nil, which is what upstream does for an identity that has
     *   none.
     *
     * The stamp cost is always nil: this app registers its delivery identity
     * without one, and upstream packs nil in that slot for exactly that case.
     */
    fun build(displayName: String?): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packArrayHeader(3)

        if (displayName == null) {
            packer.packNil()
        } else {
            val nameBytes = displayName.toByteArray(Charsets.UTF_8)
            packer.packBinaryHeader(nameBytes.size)
            packer.writePayload(nameBytes)
        }

        packer.packNil()

        packer.packArrayHeader(1)
        packer.packInt(SF_COMPRESSION)

        return packer.toByteArray()
    }
}
