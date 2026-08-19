package network.zamolxis.app.service.pq

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import network.zamolxis.app.data.repository.PqKeyRepository
import network.zamolxis.crypto.pq.HybridKem
import network.zamolxis.crypto.pq.HybridKemException
import network.zamolxis.crypto.pq.LinkCost
import network.zamolxis.crypto.pq.PlainReason
import network.zamolxis.crypto.pq.PqDecision
import network.zamolxis.crypto.pq.PqEnvelope
import network.zamolxis.crypto.pq.PqKeyExchange
import network.zamolxis.crypto.pq.PqMode
import network.zamolxis.crypto.pq.PqPolicy

/**
 * The seam between the message path and the hybrid post-quantum layer.
 *
 * Everything that has to happen around a single message lives here rather than
 * in the view model: deciding whether to seal, sealing, attaching our key,
 * unsealing on arrival, and accepting a key that came with it. The send path is
 * a 3000-line class where a mistake does not leak data but silently strands
 * messages, so this logic is kept somewhere it can be exercised on its own.
 */
@Singleton
class PqMessageSealer
    @Inject
    constructor(
        private val repository: PqKeyRepository,
        private val kem: HybridKem,
    ) {
        /** What the send path should do with one outgoing message. */
        sealed interface Outgoing {
            /**
             * Send these fields instead of the plaintext.
             *
             * @property content what goes in the LXMF content slot — empty,
             *   because the real content is inside [extraFields]
             */
            data class Sealed(
                val content: String,
                val extraFields: Map<Int, ByteArray>,
            ) : Outgoing

            /**
             * Send as an ordinary message. [extraFields] may still carry our
             * public key, which is how a conversation bootstraps: the opening
             * message cannot be sealed, but it can hand over the key that lets
             * the reply be.
             */
            data class Plain(
                val content: String,
                val extraFields: Map<Int, ByteArray>,
                val reason: PlainReason,
            ) : Outgoing

            /** Do not send. Only in [PqMode.REQUIRED], when sealing is impossible. */
            data class Refused(
                val reason: PlainReason,
            ) : Outgoing
        }

        /** What arrived, after the layer has had its turn. */
        data class Incoming(
            /** Plaintext content, unsealed if it was sealed. */
            val content: String,
            /** True when this message was actually protected by the hybrid layer. */
            val wasSealed: Boolean,
            /** Set when the sender's key could not be trusted; the UI must say so. */
            val keyProblem: PqKeyExchange.KeyAcceptance? = null,
        )

        /**
         * Decide and, if applicable, seal.
         *
         * @param linkCost how expensive the chosen transport is — sealing adds
         *   [HybridKem.OVERHEAD_BYTES], which is free on TCP and seconds of
         *   airtime on LoRa
         */
        suspend fun prepareOutgoing(
            identityHash: String,
            peerHash: String,
            content: String,
            mode: PqMode,
            linkCost: LinkCost,
        ): Outgoing {
            val state = repository.peerState(identityHash, peerHash)
            val decision = decisionFor(state, mode, linkCost)

            // Attached whenever the peer may not hold it, independently of whether
            // this particular message gets sealed. Without it a pair of peers can
            // sit forever, each unable to seal because neither has sent a key.
            val ourKey =
                if (PqKeyExchange.shouldAttachOurKey(state)) {
                    repository.ourPublicKey(identityHash)
                } else {
                    null
                }

            return when (decision) {
                is PqDecision.Refuse -> Outgoing.Refused(decision.reason)

                is PqDecision.SendPlain ->
                    Outgoing.Plain(
                        content = content,
                        extraFields = ourKey?.let(PqEnvelope::keyOnlyFields).orEmpty(),
                        reason = decision.reason,
                    )

                PqDecision.Seal -> sealOrFallBack(state, content, ourKey, mode)
            }
        }

        private fun decisionFor(
            state: PqKeyExchange.PeerState,
            mode: PqMode,
            linkCost: LinkCost,
        ): PqDecision = PqPolicy.decide(mode, PqKeyExchange.support(state), linkCost)

        /**
         * Whether a message sent to this peer right now would be sealed.
         *
         * Exists so the chat indicator and the send path cannot disagree: both
         * arrive here, at the same decision, from the same stored state. A badge
         * computed separately would eventually drift and start claiming protection
         * that is not being applied — worse than showing nothing at all.
         */
        suspend fun isConversationSealed(
            identityHash: String,
            peerHash: String,
            mode: PqMode,
            linkCost: LinkCost,
        ): Boolean {
            val state = repository.peerState(identityHash, peerHash)
            return decisionFor(state, mode, linkCost) == PqDecision.Seal
        }

        private fun sealOrFallBack(
            state: PqKeyExchange.PeerState,
            content: String,
            ourKey: network.zamolxis.crypto.pq.HybridPublicKey?,
            mode: PqMode,
        ): Outgoing {
            val peerKey =
                state.knownKey
                    // decide() only returns Seal when the key is known, so this is
                    // unreachable — but a wrong guess here would send plaintext to
                    // someone expecting protection, so it is checked rather than
                    // asserted away.
                    ?: return refuseOrPlain(mode, content, ourKey, PlainReason.PEER_KEY_NOT_YET_KNOWN)

            return try {
                Outgoing.Sealed(
                    content = "",
                    extraFields =
                        PqEnvelope.fieldsFor(
                            sealedContent = kem.seal(peerKey, content.toByteArray(Charsets.UTF_8)),
                            ourKey = ourKey,
                        ),
                )
            } catch (e: HybridKemException) {
                Log.e(TAG, "Sealing failed; falling back per mode", e)
                refuseOrPlain(mode, content, ourKey, PlainReason.PEER_KEY_NOT_YET_KNOWN)
            }
        }

        /**
         * In [PqMode.REQUIRED] a failure to seal must stop the send. Anything else
         * would hand the user a message they believe is protected and is not.
         */
        private fun refuseOrPlain(
            mode: PqMode,
            content: String,
            ourKey: network.zamolxis.crypto.pq.HybridPublicKey?,
            reason: PlainReason,
        ): Outgoing =
            if (mode == PqMode.REQUIRED) {
                Outgoing.Refused(reason)
            } else {
                Outgoing.Plain(content, ourKey?.let(PqEnvelope::keyOnlyFields).orEmpty(), reason)
            }

        /** Record that a sealed or key-carrying message actually went out. */
        suspend fun onSendSucceeded(
            identityHash: String,
            peerHash: String,
            sent: Outgoing,
        ) {
            val carriedOurKey =
                when (sent) {
                    is Outgoing.Sealed -> sent.extraFields.containsKey(PqEnvelope.FIELD_SENDER_KEY)
                    is Outgoing.Plain -> sent.extraFields.containsKey(PqEnvelope.FIELD_SENDER_KEY)
                    is Outgoing.Refused -> false
                }
            if (carriedOurKey) {
                repository.markOurKeyDelivered(identityHash, peerHash)
            }
        }

        /**
         * Unseal an arriving message and take in any key it carried.
         *
         * @param fallbackContent the LXMF content as received, used when the
         *   message is not sealed
         * @return the content to store, or null if a sealed message could not be
         *   opened — storing the ciphertext as if it were text would show the user
         *   gibberish and hide the failure
         */
        suspend fun processIncoming(
            identityHash: String,
            peerHash: String,
            fallbackContent: String,
            fields: Map<Int, ByteArray>,
        ): Incoming? {
            val keyProblem = takeInSenderKey(identityHash, peerHash, fields)

            // Read the sealed payload directly rather than through PqEnvelope.parse:
            // the payload is sealed to *our* key, so a broken sender key must not
            // stop us opening it. The two concerns are independent.
            val sealedContent =
                fields[PqEnvelope.FIELD_SEALED_CONTENT]
                    ?: return Incoming(fallbackContent, wasSealed = false, keyProblem = keyProblem)

            return unseal(identityHash, peerHash, sealedContent, keyProblem)
        }

        /**
         * Take in a key the message carried.
         *
         * @return the problem worth showing the user, or null if there was none
         */
        private suspend fun takeInSenderKey(
            identityHash: String,
            peerHash: String,
            fields: Map<Int, ByteArray>,
        ): PqKeyExchange.KeyAcceptance? {
            val offered =
                try {
                    PqEnvelope.senderKeyFrom(fields)
                } catch (e: HybridKemException) {
                    // Logged, and deliberately not stored. Treating a corrupt key as
                    // "peer has no key" would be a silent downgrade, which is what
                    // stripping key material is meant to achieve.
                    Log.w(TAG, "Malformed hybrid key from $peerHash", e)
                    null
                } ?: return null

            return repository.acceptIncomingKey(identityHash, peerHash, offered).takeIf {
                it is PqKeyExchange.KeyAcceptance.FingerprintMismatch ||
                    it is PqKeyExchange.KeyAcceptance.ChangedKey
            }
        }

        private suspend fun unseal(
            identityHash: String,
            peerHash: String,
            sealedContent: ByteArray,
            keyProblem: PqKeyExchange.KeyAcceptance?,
        ): Incoming? {
            val ourKeys = repository.ourKeyPair(identityHash)
            if (ourKeys == null) {
                Log.e(TAG, "Sealed message arrived but our hybrid key pair is unavailable")
                return null
            }

            return try {
                Incoming(
                    content = String(kem.open(ourKeys, sealedContent), Charsets.UTF_8),
                    wasSealed = true,
                    keyProblem = keyProblem,
                )
            } catch (e: HybridKemException) {
                Log.e(TAG, "Could not open sealed message from $peerHash", e)
                null
            }
        }

        private companion object {
            private const val TAG = "PqMessageSealer"
        }
    }
