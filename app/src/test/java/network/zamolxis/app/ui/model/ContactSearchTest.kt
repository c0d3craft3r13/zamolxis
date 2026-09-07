package network.zamolxis.app.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A contact added by address waits in `PENDING_IDENTITY` until the network produces
 * a key for it, and that wait is allowed to be long. What is not allowed is saying
 * "searching…" for a day and never admitting it found nothing.
 */
class ContactSearchTest {
    private val added = 1_700_000_000_000L

    @Test
    fun `a contact just added is still being looked for`() {
        assertEquals(
            ContactSearchState.LOOKING,
            ContactSearch.stateFor(addedAtMs = added, nowMs = added),
        )
    }

    @Test
    fun `a peer that answers within the quiet window is never troubleshot`() {
        assertEquals(
            "resolution over a live link is seconds; do not offer advice before then",
            ContactSearchState.LOOKING,
            ContactSearch.stateFor(addedAtMs = added, nowMs = added + ContactSearch.QUIET_SEARCH_MS - 1),
        )
    }

    @Test
    fun `past the quiet window the app admits it has nothing`() {
        assertEquals(
            ContactSearchState.NOT_FOUND_YET,
            ContactSearch.stateFor(addedAtMs = added, nowMs = added + ContactSearch.QUIET_SEARCH_MS),
        )
    }

    @Test
    fun `a contact added a day ago is not still described as searching`() {
        val aDay = 24 * 60 * 60 * 1000L
        assertEquals(
            "IdentityResolutionManager only gives up after 24h; the wording must not wait for it",
            ContactSearchState.NOT_FOUND_YET,
            ContactSearch.stateFor(addedAtMs = added, nowMs = added + aDay),
        )
    }

    @Test
    fun `a clock that went backwards does not trip the explanation`() {
        assertEquals(
            "addedTimestamp is wall clock and can be ahead of now after an NTP correction",
            ContactSearchState.LOOKING,
            ContactSearch.stateFor(addedAtMs = added, nowMs = added - 60_000L),
        )
    }

    @Test
    fun `the quiet window is short enough to be noticed in one sitting`() {
        assertEquals(90_000L, ContactSearch.QUIET_SEARCH_MS)
    }
}
