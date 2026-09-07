package network.zamolxis.app.ui.model

/**
 * What to tell someone about a contact whose identity has not been found yet.
 *
 * Adding a contact by address puts it in `PENDING_IDENTITY` until the network
 * produces a public key for it. Until now the app said "Searching for identity…"
 * for the whole of that wait, and the wait can be long: `IdentityResolutionManager`
 * only gives up after 24 hours, and the sweep that would notice runs every three.
 * A person who mistyped an address — or who was handed the identity hash instead
 * of the address, which is the same 32 characters and easy to confuse — watches a
 * spinner for a day and is told nothing.
 *
 * Shortening that deadline would be the wrong fix. A peer on a mesh can genuinely
 * be away for a day, and declaring them non-existent because they went walking is
 * a lie in the other direction. What is wrong is the wording, not the patience:
 * after the first minutes the app should stop implying an answer is imminent and
 * say plainly that it is still looking and why that may be.
 */
enum class ContactSearchState {
    /** Just added; a reply may be seconds away. */
    LOOKING,

    /** Long enough that the person deserves the likely reasons. */
    NOT_FOUND_YET,
}

object ContactSearch {
    /**
     * How long the app keeps saying "searching" before it admits it has nothing.
     *
     * Long enough for a peer that is present and announcing — resolution over a
     * live link is seconds — and short enough that a wrong address is not
     * mistaken for a slow one for the rest of the day.
     */
    const val QUIET_SEARCH_MS = 90 * 1000L

    /**
     * @param addedAtMs when the contact was added, wall clock
     * @param nowMs wall clock now — the same clock, because [addedAtMs] comes from
     *   the database and is not monotonic
     */
    fun stateFor(
        addedAtMs: Long,
        nowMs: Long,
        quietMs: Long = QUIET_SEARCH_MS,
    ): ContactSearchState = if (nowMs - addedAtMs < quietMs) ContactSearchState.LOOKING else ContactSearchState.NOT_FOUND_YET
}
