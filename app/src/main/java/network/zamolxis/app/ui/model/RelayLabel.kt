package network.zamolxis.app.ui.model

/**
 * How the "My Relay" card names a propagation node that never announced a name.
 *
 * `ContactDao` falls a contact's display name back to its destination hash
 * (`COALESCE(customNickname, peerName, destinationHash)`), which is the right answer for
 * an operator: that hash is how you tell one propagation node from another. But the relay
 * is usually the one contact that has no name — chosen automatically, never spoken to —
 * so the card pinned above everything else in Contacts reads as the same 32 characters of
 * hex twice over: once where a name belongs, and again on the line beneath it.
 *
 * Маяк puts a readable name there and drops the duplicate hash line. Nothing is hidden —
 * the hash is still on the peer details screen, one tap away — and the expert build is
 * left exactly as it was, because there the hash is the point.
 */
object RelayLabel {
    /**
     * Whether [displayName] is only the destination hash showing through the DAO's
     * fallback, rather than a name someone chose or a peer announced.
     *
     * Compared case-insensitively: the hash reaches the UI from several places (announce
     * payloads, `lxma://` links, QR scans) and they do not agree on case.
     */
    fun isNameless(
        displayName: String,
        destinationHash: String,
    ): Boolean = displayName.equals(destinationHash, ignoreCase = true)

    /**
     * Whether the relay card should stand a readable name in for the hash, and drop the
     * hash line that would otherwise repeat it.
     *
     * @param simpleUi true in the Маяк flavor — see
     *   [network.zamolxis.app.ui.screens.settings.AudienceProfile.isSimpleUi]
     */
    fun substitutesName(
        displayName: String,
        destinationHash: String,
        simpleUi: Boolean,
    ): Boolean = simpleUi && isNameless(displayName, destinationHash)
}
