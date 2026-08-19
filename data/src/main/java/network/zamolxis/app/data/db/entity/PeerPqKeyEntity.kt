package network.zamolxis.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What we know about one peer's post-quantum capability.
 *
 * Global rather than identity-scoped, following `peer_identities`: a peer's
 * public key is a property of that peer, not of which of our identities is
 * talking to them.
 *
 * A row can exist with only a fingerprint. That is the normal state between
 * seeing a peer's announce and receiving their first message — it records that
 * they *can* do post-quantum, before we hold the key to do it with.
 *
 * @property peerHash the peer's identity hash
 * @property publicKey their encoded hybrid public key, once received and accepted
 * @property announcedFingerprint the fingerprint from their most recent announce
 * @property keyChangeUnresolved set when a peer offered a key different from the
 *   stored one. Sealing stops until a human resolves it: an unexplained key
 *   change is indistinguishable from someone substituting themselves in, and
 *   quietly accepting it is how that attack succeeds.
 * @property pendingPublicKey the key that triggered [keyChangeUnresolved], held
 *   but never used.
 *
 *   It has to be kept rather than discarded: a peer stops attaching its key once
 *   it believes we have one, so a key thrown away here would never be offered
 *   again and the user could never accept a legitimate rotation. Storing it is
 *   safe precisely because nothing reads it for sealing — only the resolution
 *   flow promotes it to [publicKey], and only on an explicit human decision.
 * @property fingerprintMismatchTimestamp when this peer last offered a key that
 *   contradicted the fingerprint in its own announce, or null if that never
 *   happened.
 *
 *   Persisted rather than logged. A mismatch means either the announce or the
 *   message was altered in transit, which is the loudest signal this layer can
 *   produce — and the user is the only party who can check the key out of band.
 *   Leaving it in logcat means nobody ever sees it.
 * @property updatedTimestamp when this row last changed
 */
@Entity(tableName = "peer_pq_keys")
data class PeerPqKeyEntity(
    @PrimaryKey
    val peerHash: String,
    val publicKey: ByteArray? = null,
    val announcedFingerprint: ByteArray? = null,
    val keyChangeUnresolved: Boolean = false,
    val pendingPublicKey: ByteArray? = null,
    val fingerprintMismatchTimestamp: Long? = null,
    val updatedTimestamp: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerPqKeyEntity
        return peerHash == other.peerHash
    }

    override fun hashCode(): Int = peerHash.hashCode()
}
