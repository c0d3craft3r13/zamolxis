package network.zamolxis.crypto.pq

/**
 * Opportunistic key exchange for the hybrid layer.
 *
 * A peer advertises only a 16-byte fingerprint in its Reticulum announce — the
 * full 1216-byte key would inflate a message that every transport node on the
 * mesh rebroadcasts, spending airtime belonging to people who do not use this
 * app. The key itself rides along with the first message in each direction and
 * is checked against the advertised fingerprint on arrival.
 *
 * The cost of that choice is bounded and worth stating plainly: the very first
 * message each way travels without the post-quantum layer, protected only by
 * Reticulum's ordinary encryption. Everything after the exchange is sealed.
 */
public object PqKeyExchange {
    /** Everything the app has stored about one peer's post-quantum state. */
    public class PeerState(
        /** Their hybrid key, once received and accepted. */
        public val knownKey: HybridPublicKey?,
        /** The fingerprint from their most recent announce, if any. */
        public val announcedFingerprint: ByteArray?,
        /** Whether our own key has already been delivered to them. */
        public val ourKeyDelivered: Boolean,
    )

    /** Outcome of examining a key that arrived in a message. */
    public sealed interface KeyAcceptance {
        /** First key from this peer, consistent with what they advertised. */
        public data object Accepted : KeyAcceptance

        /** Same key we already hold. Nothing to do. */
        public data object AlreadyKnown : KeyAcceptance

        /**
         * The key does not match the fingerprint this peer advertised.
         *
         * Either the announce or the message was tampered with. Never store it:
         * accepting a substituted key is exactly how an attacker becomes able to
         * read the conversation.
         */
        public data object FingerprintMismatch : KeyAcceptance

        /**
         * A *different* key from a peer we already have one for.
         *
         * Could be an honest rotation — reinstall, new device, deliberate
         * refresh — or an attacker swapping themselves in. The app cannot tell
         * the two apart, so this is escalated to the user rather than resolved
         * silently. Trust-on-first-use is only safe if the "changed" case is
         * visible.
         */
        public data object ChangedKey : KeyAcceptance
    }

    /** How much post-quantum capability this peer currently offers. */
    public fun support(state: PeerState): PeerPqSupport =
        when {
            state.knownKey != null -> PeerPqSupport.KEY_KNOWN
            state.announcedFingerprint != null -> PeerPqSupport.ADVERTISED_KEY_MISSING
            else -> PeerPqSupport.UNSUPPORTED
        }

    /**
     * Whether to attach our own key to an outgoing message.
     *
     * Sent until it has demonstrably arrived. It is 1217 bytes, so it is not
     * attached to every message forever — but re-sending costs far less than a
     * conversation that can never start because the one copy was lost.
     */
    public fun shouldAttachOurKey(state: PeerState): Boolean = !state.ourKeyDelivered

    /**
     * Decide what to do with a hybrid key that arrived from a peer.
     *
     * The message carrying it is already signed by Reticulum, so the sender is
     * authenticated; this adds the second, independent check that the key is the
     * one they publicly advertised.
     */
    public fun acceptIncomingKey(
        offered: HybridPublicKey,
        state: PeerState,
    ): KeyAcceptance {
        val fingerprint = state.announcedFingerprint
        if (fingerprint != null && !HybridKeyCodec.matchesFingerprint(offered, fingerprint)) {
            return KeyAcceptance.FingerprintMismatch
        }

        val existing = state.knownKey ?: return KeyAcceptance.Accepted
        return if (existing == offered) KeyAcceptance.AlreadyKnown else KeyAcceptance.ChangedKey
    }
}
