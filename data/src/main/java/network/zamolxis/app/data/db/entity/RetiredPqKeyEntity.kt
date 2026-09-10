package network.zamolxis.app.data.db.entity

import androidx.room.Entity

/**
 * A hybrid key pair that has been rotated out but cannot be thrown away yet.
 *
 * ## Why keeping it is not optional
 *
 * Rotating used to replace the stored pair outright. Every peer that had not yet
 * received the new public key went on sealing to the old one, and the old
 * private half no longer existed — so those messages arrived and could not be
 * opened. A propagation node holds mail for thirty days
 * (`LXMRouter.MESSAGE_EXPIRY`), so the window was not a moment: it was a month
 * of a contact's messages disappearing into an error, on the user's side, with
 * nothing to say what had happened.
 *
 * A retired pair is tried after the current one and before giving up. It costs
 * one extra decapsulation on a message that was going to fail anyway.
 *
 * ## Why deleting it later is the point
 *
 * This is also what makes rotation mean something. The long-lived key
 * decapsulates any recorded sealed message, so anyone holding it and a capture
 * reads the past. Rotation only takes that away once the old private half is
 * genuinely gone — which is what the retention window ends with. Keeping the
 * pair forever would make rotation a gesture.
 *
 * @property identityHash the identity the pair belonged to
 * @property publicKeyHex the retired public key, hex.
 *
 *   The primary key, because it is what actually tells one retired pair from
 *   another. Keying on the retirement time instead looked natural and was wrong:
 *   two rotations inside the same millisecond collided, and the second quietly
 *   replaced the first — losing the very key the in-flight messages had been
 *   sealed to. A test caught it; nothing else would have.
 * @property retiredTimestamp when it was rotated out, for the retention window
 * @property encryptedKeyPair the pair, Keystore-wrapped exactly as the live one is
 */
@Entity(
    tableName = "retired_pq_keys",
    primaryKeys = ["identityHash", "publicKeyHex"],
)
data class RetiredPqKeyEntity(
    val identityHash: String,
    val publicKeyHex: String,
    val retiredTimestamp: Long,
    val encryptedKeyPair: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RetiredPqKeyEntity
        return identityHash == other.identityHash && publicKeyHex == other.publicKeyHex
    }

    override fun hashCode(): Int = 31 * identityHash.hashCode() + publicKeyHex.hashCode()
}
