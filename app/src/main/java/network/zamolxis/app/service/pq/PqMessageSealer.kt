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
 * ## What is covered
 *
 * The text, images, files, voice notes and the quoted text of a reply — the
 * whole user-visible payload, sealed as one blob ([SealedPayload]) that replaces
 * the LXMF fields it stands in for. The receiver rebuilds those fields verbatim,
 * so nothing downstream can tell a rebuilt attachment from one that was never
 * sealed.
 *
 * What stays outside the seal is routing metadata Reticulum needs to work and
 * side channels the recipient's protocol layer reads before the app sees the
 * message: reply target hash, reactions, telemetry, icon appearance. Those were
 * never claimed as covered, and sealing them would break the routing that
 * delivers the message at all.
 *
 * Attachments above [MAX_SEALABLE_ATTACHMENT_BYTES] are the one payload
 * exception — see that constant.
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
        /**
         * What actually goes on the wire, after the layer has had its say.
         *
         * The send path takes every field from here rather than from its own
         * locals. That is the point: when a payload is sealed, the plaintext
         * arguments must all become empty, and a call site that remembered to
         * blank the content but forgot the image would put the photo on the wire
         * beside its own ciphertext. One object, filled in one place, cannot
         * disagree with itself.
         */
        data class WirePayload(
            val content: String,
            val imageData: ByteArray? = null,
            val imageFormat: String? = null,
            val fileAttachments: List<Pair<String, ByteArray>>? = null,
            /** LXMF audio field value as `[mode, bytes]`, or null. */
            val audio: Pair<Int, ByteArray>? = null,
            val replyQuote: String? = null,
            /** Fields this layer adds: sealed content, and our key while it is still needed. */
            val extraFields: Map<Int, ByteArray> = emptyMap(),
        )

        /** What the send path should do with one outgoing message. */
        sealed interface Outgoing {
            /**
             * Send [wire] — the payload has moved inside the sealed field.
             *
             * @property protection what to record against the stored message
             */
            data class Sealed(
                val wire: WirePayload,
                val protection: PqProtection,
            ) : Outgoing

            /**
             * Send as an ordinary message. [wire] may still carry our public key,
             * which is how a conversation bootstraps: the opening message cannot
             * be sealed, but it can hand over the key that lets the reply be.
             */
            data class Plain(
                val wire: WirePayload,
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
            /**
             * LXMF fields recovered from inside the seal, in the shape
             * `AppDataParser.serializeFieldsToJson` takes. Empty for an ordinary
             * message and for one that could not be opened.
             */
            val unsealedFields: Map<Int, Any> = emptyMap(),
            /** Set when the sender's key could not be trusted; the UI must say so. */
            val keyProblem: PqKeyExchange.KeyAcceptance? = null,
        )

        /**
         * Decide and, if applicable, seal.
         *
         * @param ourDestinationHash our own LXMF destination hash, bound into the
         *   AAD so the payload is only valid in this direction of this conversation
         * @param payload everything the user is sending: text, attachments, and the
         *   quoted text if this is a reply
         * @param linkCost how expensive the chosen transport is — sealing adds
         *   [HybridKem.OVERHEAD_BYTES], which is free on TCP and seconds of
         *   airtime on LoRa
         */
        @Suppress("LongParameterList")
        suspend fun prepareOutgoing(
            identityHash: String,
            ourDestinationHash: String,
            peerHash: String,
            payload: SealedPayload,
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

                is PqDecision.SendPlain -> plain(payload, ourKey, decision.reason)

                PqDecision.Seal ->
                    sealOrFallBack(
                        state = state,
                        ourDestinationHash = ourDestinationHash,
                        peerHash = peerHash,
                        payload = payload,
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

        /** The message as the user composed it, plus our key if it still needs sending. */
        private fun plain(
            payload: SealedPayload,
            ourKey: HybridPublicKey?,
            reason: PlainReason,
        ): Outgoing.Plain =
            Outgoing.Plain(
                wire =
                    WirePayload(
                        content = payload.content,
                        imageData = payload.image?.bytes,
                        imageFormat = payload.image?.format,
                        fileAttachments =
                            payload.files.map { it.name to it.bytes }.ifEmpty { null },
                        audio = payload.audio?.let { it.mode to it.bytes },
                        replyQuote = payload.replyQuote,
                        extraFields = ourKey?.let(PqEnvelope::keyOnlyFields).orEmpty(),
                    ),
                reason = reason,
            )

        // ReturnCount: an oversize attachment, a refusal, a missing peer key and the
        // sealed result are four different outcomes, and each one is decided by its
        // own condition. Nesting them would hide which is which.
        @Suppress("LongParameterList", "ReturnCount")
        private fun sealOrFallBack(
            state: PqKeyExchange.PeerState,
            ourDestinationHash: String,
            peerHash: String,
            payload: SealedPayload,
            ourKey: HybridPublicKey?,
            mode: PqMode,
        ): Outgoing {
            // A payload this large cannot be sealed on a phone: seal() holds the
            // plaintext, the ciphertext and the assembled blob at once, so the peak
            // is several times the attachment. Above the limit the text is still
            // sealed and the attachment travels as it always did, which the message
            // is then labelled with — and REQUIRED refuses rather than quietly
            // sending a photo in the clear.
            if (payload.attachmentBytes > MAX_SEALABLE_ATTACHMENT_BYTES) {
                Log.i(
                    TAG,
                    "Attachment of ${payload.attachmentBytes} bytes exceeds the sealable limit " +
                        "($MAX_SEALABLE_ATTACHMENT_BYTES); sealing the text only",
                )
                if (mode == PqMode.REQUIRED) {
                    return Outgoing.Refused(PlainReason.ATTACHMENT_NOT_SEALABLE)
                }
                return sealTextOnly(state, ourDestinationHash, peerHash, payload, ourKey, mode)
            }

            val peerKey =
                state.knownKey
                    // decide() only returns Seal when the key is known, so this is
                    // unreachable — but a wrong guess here would send plaintext to
                    // someone expecting protection, so it is checked rather than
                    // asserted away.
                    ?: return refuseOrPlain(mode, payload, ourKey, PlainReason.PEER_KEY_NOT_YET_KNOWN)

            return try {
                Outgoing.Sealed(
                    wire =
                        WirePayload(
                            content = "",
                            extraFields =
                                PqEnvelope.fieldsFor(
                                    sealedContent =
                                        seal(peerKey, ourDestinationHash, peerHash, payload),
                                    ourKey = ourKey,
                                ),
                        ),
                    protection = PqProtection.SEALED,
                )
            } catch (e: HybridKemException) {
                Log.e(TAG, "Sealing failed; falling back per mode", e)
                refuseOrPlain(mode, payload, ourKey, PlainReason.LAYER_UNAVAILABLE)
            }
        }

        /**
         * Seal the text and let an oversized attachment travel as it always did.
         *
         * Recorded as [PqProtection.SEALED_PARTIAL], which is what the UI shows: a
         * message where one half got the hybrid layer and the other did not is not
         * the same thing as a sealed message, and saying otherwise is the kind of
         * reassuring simplification that gets someone hurt.
         */
        @Suppress("LongParameterList")
        private fun sealTextOnly(
            state: PqKeyExchange.PeerState,
            ourDestinationHash: String,
            peerHash: String,
            payload: SealedPayload,
            ourKey: HybridPublicKey?,
            mode: PqMode,
        ): Outgoing {
            val peerKey =
                state.knownKey
                    ?: return refuseOrPlain(mode, payload, ourKey, PlainReason.PEER_KEY_NOT_YET_KNOWN)

            val textOnly = SealedPayload(content = payload.content, replyQuote = payload.replyQuote)

            return try {
                Outgoing.Sealed(
                    wire =
                        WirePayload(
                            content = "",
                            imageData = payload.image?.bytes,
                            imageFormat = payload.image?.format,
                            fileAttachments =
                                payload.files.map { it.name to it.bytes }.ifEmpty { null },
                            audio = payload.audio?.let { it.mode to it.bytes },
                            extraFields =
                                PqEnvelope.fieldsFor(
                                    sealedContent =
                                        seal(peerKey, ourDestinationHash, peerHash, textOnly),
                                    ourKey = ourKey,
                                ),
                        ),
                    protection = PqProtection.SEALED_PARTIAL,
                )
            } catch (e: HybridKemException) {
                Log.e(TAG, "Sealing failed; falling back per mode", e)
                refuseOrPlain(mode, payload, ourKey, PlainReason.LAYER_UNAVAILABLE)
            }
        }

        private fun seal(
            recipient: HybridPublicKey,
            ourDestinationHash: String,
            peerHash: String,
            payload: SealedPayload,
        ): ByteArray =
            kem.seal(
                recipient = recipient,
                plaintext = PqSealedPayloadCodec.encode(payload),
                aad =
                    PqAad.forDirection(
                        senderDestinationHash = ourDestinationHash,
                        recipientDestinationHash = peerHash,
                    ),
            )

        /**
         * In [PqMode.REQUIRED] a failure to seal must stop the send. Anything else
         * would hand the user a message they believe is protected and is not.
         */
        private fun refuseOrPlain(
            mode: PqMode,
            payload: SealedPayload,
            ourKey: HybridPublicKey?,
            reason: PlainReason,
        ): Outgoing =
            if (mode == PqMode.REQUIRED) {
                Outgoing.Refused(reason)
            } else {
                plain(payload, ourKey, reason)
            }

        /** Record that a sealed or key-carrying message actually went out. */
        suspend fun onSendSucceeded(
            identityHash: String,
            peerHash: String,
            sent: Outgoing,
        ) {
            val carriedOurKey =
                when (sent) {
                    is Outgoing.Sealed -> sent.wire.extraFields.containsKey(PqEnvelope.FIELD_SENDER_KEY)
                    is Outgoing.Plain -> sent.wire.extraFields.containsKey(PqEnvelope.FIELD_SENDER_KEY)
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
            hasUnsealedAttachments: Boolean = false,
        ): Incoming {
            val keyProblem = takeInSenderKey(identityHash, peerHash, fields)

            // Read the sealed payload directly rather than through PqEnvelope.parse:
            // the payload is sealed to *our* key, so a broken sender key must not
            // stop us opening it. The two concerns are independent.
            val sealedContent =
                fields[PqEnvelope.FIELD_SEALED_CONTENT]
                    ?: return Incoming(fallbackContent, PqProtection.NONE, keyProblem = keyProblem)

            return unseal(
                identityHash = identityHash,
                ourDestinationHash = ourDestinationHash,
                peerHash = peerHash,
                sealedContent = sealedContent,
                hasUnsealedAttachments = hasUnsealedAttachments,
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

        @Suppress("LongParameterList", "ReturnCount")
        private suspend fun unseal(
            identityHash: String,
            ourDestinationHash: String,
            peerHash: String,
            sealedContent: ByteArray,
            hasUnsealedAttachments: Boolean,
            keyProblem: PqKeyExchange.KeyAcceptance?,
        ): Incoming {
            val ourKeys = repository.ourKeyPair(identityHash)
            if (ourKeys == null) {
                Log.e(TAG, "Sealed message arrived but our hybrid key pair is unavailable")
                return Incoming("", PqProtection.UNOPENED, keyProblem = keyProblem)
            }

            val opened =
                try {
                    kem.open(
                        keyPair = ourKeys,
                        wire = sealedContent,
                        aad =
                            PqAad.forDirection(
                                senderDestinationHash = peerHash,
                                recipientDestinationHash = ourDestinationHash,
                            ),
                    )
                } catch (e: HybridKemException) {
                    Log.e(TAG, "Could not open sealed message from $peerHash", e)
                    return Incoming("", PqProtection.UNOPENED, keyProblem = keyProblem)
                }

            val payload =
                try {
                    PqSealedPayloadCodec.decode(opened)
                } catch (e: PqPayloadException) {
                    // Opened but unintelligible: the tag verified, so this is a
                    // format disagreement rather than tampering. Still unreadable to
                    // the user, so it is recorded as such rather than shown blank.
                    Log.e(TAG, "Opened a sealed message from $peerHash but could not decode it", e)
                    return Incoming("", PqProtection.UNOPENED, keyProblem = keyProblem)
                }

            return Incoming(
                content = payload.content,
                // An attachment that rode outside the seal is what the sender's own
                // oversize fallback produces, and the receiver reports it the same
                // way rather than claiming the whole message was covered.
                protection =
                    if (hasUnsealedAttachments) PqProtection.SEALED_PARTIAL else PqProtection.SEALED,
                unsealedFields = payload.toLxmfFields(),
                keyProblem = keyProblem,
            )
        }

        companion object {
            private const val TAG = "PqMessageSealer"

            /**
             * Largest attachment payload this layer will seal, in bytes.
             *
             * Sealing is not streamed: [HybridKem.seal] holds the plaintext, the
             * GCM output and the assembled wire blob simultaneously, so peak memory
             * is roughly three times the payload — and the receiver pays it again on
             * the way out. The app allows attachments up to 32 MB, which at that
             * multiple is an OutOfMemoryError on a mid-range phone rather than a
             * protected photo. 4 MB covers ordinary images, voice notes and
             * documents with room to spare; above it the text is still sealed and
             * the message says the attachment was not.
             */
            const val MAX_SEALABLE_ATTACHMENT_BYTES: Long = 4L * 1024 * 1024
        }
    }
