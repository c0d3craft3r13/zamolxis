package network.zamolxis.app.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list a duress wipe destroys.
 *
 * This is a small file with a large consequence. A wipe removes Keystore entries
 * by name — enumerating the store would reach into other apps' keys — so an
 * alias missing from the list survives the wipe, and so does whatever it
 * unlocks. That has already happened once: the container device key was added
 * long after the identity key and never added to the wipe, leaving a duress wipe
 * that destroyed the message database while the key opening an exported copy of
 * the same messages stayed on the phone.
 */
class KeystoreAliasesTest {
    @Test
    fun `every alias the app defines is one a wipe destroys`() {
        assertTrue(
            "the identity master key must be wiped",
            KeystoreAliases.IDENTITY_MASTER in KeystoreAliases.ALL,
        )
        assertTrue(
            "the container device key must be wiped",
            KeystoreAliases.CONTAINER_DEVICE in KeystoreAliases.ALL,
        )
    }

    /**
     * The count is asserted on purpose. Adding an alias without adding it to
     * [KeystoreAliases.ALL] is the mistake this file exists to prevent, and a
     * test that only checks the ones it already knows about would not notice.
     * Failing here means: put the new alias in `ALL`, then update this number.
     */
    @Test
    fun `adding an alias means deciding whether a wipe destroys it`() {
        assertEquals(
            "a new Keystore alias must be added to ALL before this number changes",
            2,
            KeystoreAliases.ALL.size,
        )
    }

    @Test
    fun `no alias is listed twice`() {
        assertEquals(KeystoreAliases.ALL.size, KeystoreAliases.ALL.toSet().size)
    }

    /** The names are what is actually stored on devices already in the field. */
    @Test
    fun `the alias names are the ones already on devices`() {
        assertEquals("zamolxis_identity_master_key", KeystoreAliases.IDENTITY_MASTER)
        assertEquals("zamolxis_container_device_key", KeystoreAliases.CONTAINER_DEVICE)
    }
}
