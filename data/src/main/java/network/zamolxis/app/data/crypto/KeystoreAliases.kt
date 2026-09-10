package network.zamolxis.app.data.crypto

/**
 * Every Android Keystore entry this app creates, in one place.
 *
 * ## Why they are gathered here
 *
 * A duress wipe destroys Keystore entries from an explicit list, deliberately:
 * enumerating the store with `KeyStore.aliases()` would reach past this app into
 * keys belonging to others. The cost of that choice is that the list has to be
 * complete, and it was not. The container device key was added months after the
 * identity key and never added to the wipe, so a duress wipe destroyed the
 * message database and left behind the hardware key that opens an exported copy
 * of the same messages — the more complete copy of the two.
 *
 * Keeping the names here rather than beside the code that generates them means
 * the wipe and the generators cannot hold different ideas about what exists.
 * **Anything that creates a Keystore entry belongs in [ALL].**
 */
object KeystoreAliases {
    /** Wraps identity key material and the database passphrase. */
    const val IDENTITY_MASTER = "zamolxis_identity_master_key"

    /**
     * Opens the device slot of an export container.
     *
     * Non-exportable and destroyable on purpose: destroying it closes that slot in
     * every copy of the container that exists anywhere, which is the only form of
     * "delete the backup" that survives the backup having been copied first.
     */
    const val CONTAINER_DEVICE = "zamolxis_container_device_key"

    /** What a wipe has to remove for the device to hold nothing readable. */
    val ALL: List<String> = listOf(IDENTITY_MASTER, CONTAINER_DEVICE)
}
