package network.zamolxis.app.data.db.entity

import androidx.room.Entity

/**
 * One post-quantum epoch: a run of messages sealed under a single handshake.
 *
 * ## Why this table exists
 *
 * Sealing each message with its own handshake costs 1149 bytes, four times what
 * a Reticulum packet carries, so every sealed message needed a link and a
 * multi-packet transfer — a shape that told anyone counting packets which
 * conversations were protected. An epoch pays that once and then costs 29 bytes
 * a message, which is what this row is for: somewhere to keep the epoch between
 * one message and the next.
 *
 * ## One row per direction
 *
 * Epochs are simplex. The one we send under is ours to advance; the ones we
 * receive under belong to the peer and we only ever read them. Keeping the two
 * apart means neither side has to negotiate anything, and two ends opening an
 * epoch at the same moment is not a race — it is simply two epochs.
 *
 * @property identityHash our local identity
 * @property peerHash the peer's destination hash
 * @property outbound true for the epoch we seal with, false for one we open with
 * @property epochId hex of the identifier the wire carries, derived from the root
 * @property encryptedRoot the epoch root, wrapped by the Android Keystore the
 *   same way a local key pair is. The database is already encrypted; this is the
 *   second wrap, because a root opens every message of its epoch and deserves
 *   the treatment key material gets rather than the treatment rows get.
 * @property nextCounter the counter the next outgoing message will use.
 *
 *   Only meaningful on an outbound row, and it must never go backwards. A
 *   repeated counter repeats a message key and its nonce together, which takes
 *   AES-GCM apart completely — so a sender that cannot be sure where it got to
 *   opens a fresh epoch rather than guessing.
 * @property messageCount how many messages this epoch has carried, for retiring it
 * @property createdTimestamp when the epoch was opened
 * @property lastUsedTimestamp when a message last went through it. Inbound rows
 *   are kept well past this — a propagation node holds mail for thirty days, and
 *   a root deleted earlier turns late delivery into a message nobody can read.
 */
@Entity(
    tableName = "pq_epochs",
    primaryKeys = ["identityHash", "peerHash", "outbound", "epochId"],
)
data class PqEpochEntity(
    val identityHash: String,
    val peerHash: String,
    val outbound: Boolean,
    val epochId: String,
    val encryptedRoot: ByteArray,
    val nextCounter: Int,
    val messageCount: Int,
    val createdTimestamp: Long,
    val lastUsedTimestamp: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PqEpochEntity
        return identityHash == other.identityHash &&
            peerHash == other.peerHash &&
            outbound == other.outbound &&
            epochId == other.epochId
    }

    override fun hashCode(): Int {
        var result = identityHash.hashCode()
        result = 31 * result + peerHash.hashCode()
        result = 31 * result + outbound.hashCode()
        result = 31 * result + epochId.hashCode()
        return result
    }
}
