package network.zamolxis.app.rns.backend.kt

import android.app.Application
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.lxmf.LXMRouter
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the contract `NativeRnsBackendImpl.restorePeerIdentities` relies on: a
 * destination whose key the app holds out-of-band (QR scan, pasted `lxma://`,
 * restored history) must be resolvable by `Identity.recall` without any
 * announce ever arriving.
 *
 * This is the exact step that used to be missing. `restorePeerIdentities` was a
 * stub returning `entries.size`, so `recall` on the send path missed,
 * `NativeMessageSender` fell back to a 10-second path request, and the send died
 * with "Recipient not found after path request" — the contact was visible in the
 * app but unreachable. If reticulum-kt ever changes what `remember` stores or
 * what `recall` looks up, this test fails here instead of on a user's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentityRestoreContractTest {
    private val destinationHash = ByteArray(16) { (it + 1).toByte() }
    private val otherHash = ByteArray(16) { (it + 100).toByte() }

    @Before
    fun setUp() {
        Identity.clearKnownDestinations()
    }

    @After
    fun tearDown() {
        Identity.clearKnownDestinations()
    }

    @Test
    fun `a remembered destination is recallable without an announce`() {
        val identity = Identity.create()
        val publicKey = identity.getPublicKey()

        assertNull("precondition: the destination must start out unknown", Identity.recall(destinationHash))

        Identity.remember(
            packetHash = destinationHash,
            destHash = destinationHash,
            publicKey = publicKey,
        )

        val recalled = Identity.recall(destinationHash)
        assertNotNull("recall must resolve a destination seeded from a stored public key", recalled)
        assertArrayEquals(publicKey, recalled!!.getPublicKey())
        assertTrue(Identity.isKnown(destinationHash))
    }

    @Test
    fun `remembering one destination does not make an unrelated one resolvable`() {
        val identity = Identity.create()

        Identity.remember(
            packetHash = destinationHash,
            destHash = destinationHash,
            publicKey = identity.getPublicKey(),
        )

        assertNull("only the seeded destination may resolve", Identity.recall(otherHash))
    }

    @Test
    fun `a 64-byte public key is what remember accepts`() {
        // The QR payload and the peer_identities column both carry 64 bytes
        // (X25519 + Ed25519). If reticulum-kt ever expects a different width,
        // every restored identity would silently fail to resolve.
        val publicKey = Identity.create().getPublicKey()
        assertArrayEquals(intArrayOf(64), intArrayOf(publicKey.size))
    }

    @Test
    fun `the seeded destination hash is the one the send path derives`() {
        // The app stores a contact's LXMF *delivery* destination hash, and that
        // is what gets seeded. The send path goes the other way: it recalls an
        // identity by that hash and rebuilds the destination from it
        // (NativeMessageSender.sendMessage). If those two hashes disagree the
        // seeding is worthless — the message would be addressed somewhere else.
        val identity = Identity.create()
        val deliveryHash =
            Destination.hash(identity, LXMRouter.APP_NAME, LXMRouter.DELIVERY_ASPECT)

        Identity.remember(
            packetHash = deliveryHash,
            destHash = deliveryHash,
            publicKey = identity.getPublicKey(),
        )

        val recalled = Identity.recall(deliveryHash)
        assertNotNull("the delivery destination must resolve after seeding", recalled)
        val rebuilt =
            Destination.create(
                recalled!!,
                DestinationDirection.OUT,
                DestinationType.SINGLE,
                LXMRouter.APP_NAME,
                LXMRouter.DELIVERY_ASPECT,
            )
        assertArrayEquals(
            "the destination rebuilt on the send path must be the one we seeded",
            deliveryHash,
            rebuilt.hash,
        )
    }
}
