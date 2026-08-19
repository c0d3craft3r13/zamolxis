package network.zamolxis.app.rns.api.util

import org.msgpack.core.MessagePack

/**
 * The `app_data` payload attached to a peer (LXMF delivery) announce.
 *
 * Upstream LXMF packs `[display_name, stamp_cost]`. Zamolxis appends a third
 * element — the 16-byte hybrid post-quantum fingerprint — when the identity has
 * one, so peers learn sealing is possible before anyone writes the first
 * message.
 *
 * Appending is safe for everyone else on the mesh: LXMF and NomadNet read
 * `app_data[0]` and `app_data[1]` by index, so a longer array is simply not
 * looked at. Only the fingerprint goes here, never the key itself — at 1216
 * bytes a key would inflate a message every transport node rebroadcasts,
 * spending airtime that belongs to the whole network.
 *
 * **Lives in `:rns-api` so both backends build byte-identical announces.** It
 * previously existed only in the Kotlin backend, which left the Python
 * flavor — the one that ships under the plain application id — announcing
 * without a fingerprint at all. Peers therefore could never verify a key
 * against an announcement for the majority of installs, and the fingerprint
 * check silently degraded to bare trust-on-first-use.
 */
object PeerAnnounceAppData {
    /**
     * Pack an announce payload.
     *
     * @param displayName the identity's display name, UTF-8 on the wire
     * @param pqFingerprint hybrid key fingerprint to advertise, or null for the
     *   plain two-element upstream shape
     */
    fun build(
        displayName: String,
        pqFingerprint: ByteArray? = null,
    ): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        packer.packArrayHeader(if (pqFingerprint != null) 3 else 2)
        packer.packBinaryHeader(nameBytes.size)
        packer.writePayload(nameBytes)
        // Stamp cost is nil: Zamolxis does not set one, and LXMF's own
        // get_announce_app_data packs nil in the same slot when
        // register_delivery_identity was called without a cost.
        packer.packNil()
        if (pqFingerprint != null) {
            packer.packBinaryHeader(pqFingerprint.size)
            packer.writePayload(pqFingerprint)
        }
        return packer.toByteArray()
    }
}
