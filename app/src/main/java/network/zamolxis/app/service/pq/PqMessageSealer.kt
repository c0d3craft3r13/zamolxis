package network.zamolxis.app.service.pq

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import network.zamolxis.app.data.model.PqProtection
import network.zamolxis.app.data.repository.PqKeyRepository
import network.zamolxis.crypto.pq.HybridKem
import network.zamolxis.crypto.pq.HybridKemException
import network.zamolxis.crypto.pq.HybridPublicKey
import network.zamolxis.crypto.pq.LinkCost
import network.zamolxis.crypto.pq.PlainReason
import network.zamolxis.crypto.pq.PqAad
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
 * a 3000-line class, so this logic is kept somewhere it can be exercised on its
 * own.
 *
 * ## What is and is not covered
 *
 * The layer seals the *text* of a message. Images, files and voice notes travel
 * in their own LXMF fields and are not sealed — see
 * [PlainReason.ATTACHMENT_NOT_SEALABLE]. That limit is reported, never hidden:
 * an attachment downgrades the message to [PqProtection.SEALED_PARTIAL], and in
 * [PqMode.REQUIRED] it refuses the send outright rather than letting a photo
 * leave in the clear under a badge that says the conversation is protected.
 *
 * ## Failure direction
 *
 * Every failure path here is resolved by [PqMode], not by convenience. In
 * [PqMode.REQUIRED] a fault produces [Outgoing.Refused] — the user asked for
 * protection or nothing, and "or nothing" is the half that matters.
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
             * @property protection what to record against the stored message
             */
            data class Sealed(
                val content: String,
                val extraFields: Map<Int, ByteArray>,
                val protection: PqProtection,
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
            /** Plaintext content, unsealed if it was sealed. Empty when it could not be opened. */
            val content: String,
            /** What the layer did to this message, for the stored row and the UI. */
            val protection: PqProtection,
            /** Set when the sender's key could not be trusted; the UI must say so. */
            val keyProblem: PqKeyExchange.KeyAcceptance? = null,
        )

        /**
         * Decide and, if applicable, seal.
         *
         * @param ourDestinationHash our own LXMF destination hash, bound into the
         *   AAD so the payload is only valid in this direction of this conversation
         * @param hasAttachments whether the message also carries an image, file or
         *   voice note — those are not sealed, and the caller must say so rather
         *   than letting the layer overstate what it covered
         * @param linkCost how expensive the chosen transport is — sealing adds
         *   [HybridKem.OVERHEAD_BYTES], which is free on TCP and seconds of
         *   airtime on LoRa
         */
        @Suppress("LongParameterList")
        suspend fun prepareOutgoing(
            identityHash: String,
            ourDestinationHash: String,
            peerHash: String,
            content: String,
            hasAttachments: Boolean,
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

                PqDecision.Seal ->
                    sealOrFallBack(
                        state = state,
                        ourDestinationHash = ourDestinationHash,
                        peerHash = peerHash,
                        content = content,
                        hasAttachments = hasAttachments,
                        ourKey = ourKey,
                        mode = mode,
                    )
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

        @Suppress("LongParameterList")
        private fun sealOrFallBack(
            state: PqKeyExchange.PeerState,
            ourDestinationHash: String,
            peerHash: String,
            content: String,
            hasAttachments: Boolean,
            ourKey: HybridPublicKey?,
            mode: PqMode,
        ): Outgoing {
            // An attachment cannot be sealed by this layer. In REQUIRED that is a
            // refusal, not a footnote: the alternative is a photo on the wire in
            // the clear while the conversation is badged as protected.
            if (hasAttachments && mode == PqMode.REQUIRED) {
                return Outgoing.Refused(PlainReason.ATTACHMENT_NOT_SEALABLE)
            }

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
                            sealedContent =
                                kem.seal(
                                    recipient = peerKey,
                                    plaintext = content.toByteArray(Charsets.UTF_8),
                                    aad =
                                        PqAad.forDirection(
                                            senderDestinationHash = ourDestinationHash,
                                            recipientDestinationHash = peerHash,
                                        ),
                                ),
                            ourKey = ourKey,
                        ),
                    protection =
                        if (hasAttachments) PqProtection.SEALED_PARTIAL else PqProtection.SEALED,
                )
            } catch (e: HybridKemException) {
                Log.e(TAG, "Sealing failed; falling back per mode", e)
                refuseOrPlain(mode, content, ourKey, PlainReason.LAYER_UNAVAILABLE)
            }
        }

        /**
         * In [PqMode.REQUIRED] a failure to seal must stop the send. Anything else
         * would hand the user a message they believe is protected and is not.
         */
        private fun refuseOrPlain(
            mode: PqMode,
            content: String,
            ourKey: HybridPublicKey?,
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
         * @param ourDestinationHash our own LXMF destination hash — the other half
         *   of the AAD the sender bound the payload to
         * @param fallbackContent the LXMF content as received, used when the
         *   message is not sealed
         * @param hasAttachments whether the message also carried an image, file or
         *   voice note. Passed in rather than read off [fields] because [fields]
         *   holds only what this layer owns — deriving it here would silently
         *   report every sealed message as fully protected.
         * @return never null. A sealed message that cannot be opened comes back as
         *   [PqProtection.UNOPENED] with empty content so the caller stores an
         *   honest placeholder: dropping it would mean the sender holds a delivery
         *   proof for something the recipient never learns exists, and would throw
         *   away ciphertext that a later key-change resolution could still open.
         */
        @Suppress("LongParameterList")
        suspend fun processIncoming(
            identityHash: String,
            ourDestinationHash: String,
            peerHash: String,
            fallbackContent: String,
            fields: Map<Int, ByteArray>,
            hasAttachments: Boolean = false,
        ): Incoming {
            val keyProblem = takeInSenderKey(identityHash, peerHash, fields)

            // Read the sealed payload directly rather than through PqEnvelope.parse:
            // the payload is sealed to *our* key, so a broken sender key must not
            // stop us opening it. The two concerns are independent.
            val sealedContent =
                fields[PqEnvelope.FIELD_SEALED_CONTENT]
                    ?: return Incoming(fallbackContent, PqProtection.NONE, keyProblem)

            return unseal(
                identityHash = identityHash,
                ourDestinationHash = ourDestinationHash,
                peerHash = peerHash,
                sealedContent = sealedContent,
                hasAttachments = hasAttachments,
                keyProblem = keyProblem,
            )
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

        @Suppress("LongParameterList")
        private suspend fun unseal(
            identityHash: String,
            ourDestinationHash: String,
            peerHash: String,
            sealedContent: ByteArray,
            hasAttachments: Boolean,
            keyProblem: PqKeyExchange.KeyAcceptance?,
        ): Incoming {
            val ourKeys = repository.ourKeyPair(identityHash)
            if (ourKeys == null) {
                Log.e(TAG, "Sealed message arrived but our hybrid key pair is unavailable")
                return Incoming("", PqProtection.UNOPENED, keyProblem)
            }

            return try {
                Incoming(
                    content =
                        String(
                            kem.open(
                                keyPair = ourKeys,
                                wire = sealedContent,
                                aad =
                                    PqAad.forDirection(
                                        senderDestinationHash = peerHash,
                                        recipientDestinationHash = ourDestinationHash,
                                    ),
                            ),
                            Charsets.UTF_8,
                        ),
                    protection =
                        if (hasAttachments) PqProtection.SEALED_PARTIAL else PqProtection.SEALED,
                    keyProblem = keyProblem,
                )
            } catch (e: HybridKemException) {
                Log.e(TAG, "Could not open sealed message from $peerHash", e)
                Incoming("", PqProtection.UNOPENED, keyProblem)
            }
        }

        private companion object {
            private const val TAG = "PqMessageSealer"
        }
    }
