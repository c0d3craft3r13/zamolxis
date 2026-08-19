package network.zamolxis.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Records that one of our identities has handed its hybrid public key to a peer.
 *
 * Scoped to the pair, not to the peer: each local identity has its own hybrid
 * key, so "they have my key" is only true for the identity that sent it.
 *
 * Absence of a row means "not delivered yet", which is the safe default — the
 * cost of attaching the key one extra time is 1217 bytes, while the cost of
 * wrongly believing it arrived is a conversation that can never be sealed.
 *
 * @property identityHash the local identity whose key was sent
 * @property peerHash the peer it was sent to
 * @property deliveredTimestamp when it was sent
 */
@Entity(
    tableName = "pq_key_deliveries",
    primaryKeys = ["identityHash", "peerHash"],
    foreignKeys = [
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["identityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("peerHash")],
)
data class PqKeyDeliveryEntity(
    val identityHash: String,
    val peerHash: String,
    val deliveredTimestamp: Long,
)
