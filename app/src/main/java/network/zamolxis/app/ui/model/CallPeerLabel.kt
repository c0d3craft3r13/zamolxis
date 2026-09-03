package network.zamolxis.app.ui.model

import network.zamolxis.app.util.isGeneratedDisplayName

/**
 * What to write at the top of a call screen.
 *
 * A call is addressed to the peer's `lxst.telephony` destination, which hashes
 * differently from the `lxmf.delivery` address a person actually knows and dialled.
 * When that telephony destination announced no name, the app invents one from the
 * hash it does have — and the screen ends up showing a third string the caller has
 * never seen anywhere, for a peer they may have named themselves.
 *
 * So a name the app invented is not an answer. Keep looking, and if nothing real
 * turns up, show the hash rather than a placeholder built from a different one.
 */
object CallPeerLabel {
    /**
     * @param candidates every name the app could find for this peer, best first
     * @param dialledHash the hash to fall back to, shortened for reading
     */
    fun of(
        candidates: List<String?>,
        dialledHash: String,
    ): String = candidates.firstOrNull { isRealName(it) } ?: shortenHash(dialledHash)

    private fun isRealName(name: String?): Boolean = !name.isNullOrBlank() && !isGeneratedDisplayName(name)

    /** "631b64c15025938e3f7ea3ad61693102" -> "631b64...693102" */
    fun shortenHash(hash: String): String =
        if (hash.length > 12) {
            "${hash.take(6)}...${hash.takeLast(6)}"
        } else {
            hash
        }
}
