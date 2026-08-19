package network.zamolxis.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * The hybrid post-quantum key pair belonging to one local identity.
 *
 * Kept in its own table rather than as columns on `local_identities` so the
 * migration that introduces it only ever creates tables — an existing row of
 * identity data, which is what a user would lose irrecoverably, is never
 * rewritten.
 *
 * One key pair per identity, matching how the rest of the app isolates
 * identities from each other. Deleting an identity takes its hybrid key with it.
 *
 * @property identityHash the owning local identity
 * @property publicKey encoded hybrid public key, safe to publish
 * @property encryptedKeyPair the full key pair (private halves included) after
 *   Android Keystore wrapping — the same protection the Reticulum identity key
 *   gets. Never leaves the device and never goes on the wire.
 * @property createdTimestamp when the pair was generated, for future rotation
 */
@Entity(
    tableName = "local_pq_keys",
    foreignKeys = [
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["identityHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LocalPqKeyEntity(
    @PrimaryKey
    val identityHash: String,
    val publicKey: ByteArray,
    val encryptedKeyPair: ByteArray,
    val createdTimestamp: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LocalPqKeyEntity
        return identityHash == other.identityHash
    }

    override fun hashCode(): Int = identityHash.hashCode()
}
