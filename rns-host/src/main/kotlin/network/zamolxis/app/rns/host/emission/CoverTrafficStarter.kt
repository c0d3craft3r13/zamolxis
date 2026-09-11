package network.zamolxis.app.rns.host.emission

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import network.zamolxis.app.data.repository.ContactRepository
import network.zamolxis.app.data.repository.IdentityRepository
import network.zamolxis.app.rns.api.RnsBackend
import network.zamolxis.app.rns.api.RnsLxmf
import network.zamolxis.app.rns.api.model.DeliveryMethod
import network.zamolxis.app.rns.api.model.Identity
import network.zamolxis.app.rns.api.model.InterfaceConfig
import network.zamolxis.app.rns.api.util.hexToBytes
import network.zamolxis.app.rns.host.persistence.ReticulumConfigSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects cover traffic to the running service.
 *
 * Everything that decides — who, where, when, whether at all — lives in the
 * classes this assembles. This only answers where each input comes from inside
 * the `:reticulum` process, and each answer is the one another part of the
 * service already relies on, so there is no second source of truth to drift.
 *
 *  - **Who**: contacts of the active identity, from the same query
 *    [network.zamolxis.app.rns.host.persistence.PeerIdentityPrimer] uses to
 *    load their keys. People already known; never strangers.
 *  - **Over what**: the interfaces in the snapshot the UI writes on every
 *    successful initialize — the same file the service restarts itself from.
 *    Re-read each cycle, so an interface added since the last one is
 *    classified by what it is rather than missed.
 *  - **Where it goes**: the stack's own answer for each destination's next hop.
 */
@Singleton
class CoverTrafficStarter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val contactRepository: ContactRepository,
        private val identityRepository: IdentityRepository,
    ) {
        /**
         * Run cover traffic for as long as [scope] lives.
         *
         * @return the loop's job, so its owner can cancel it.
         */
        fun start(
            backend: RnsBackend,
            scope: CoroutineScope,
        ): Job {
            val emissions = EmissionPolicy(context)
            val emitter =
                CoverTrafficEmitter(
                    emissions = emissions,
                    resolver = InterfaceMediumResolver { configuredInterfaces() },
                    correspondents = { contacts() },
                    nextHopInterfaceName = { backend.core.getNextHopInterfaceName(it) },
                    sendCover = { destination, fields ->
                        sendCover(backend.lxmf, destination, fields, sender())
                    },
                )
            val tracker = CoverTracker(BluetoothCoverMeter(context), NetworkCoverMeter())
            return CoverTrafficLoop(emissions, tracker, emitter).start(scope)
        }

        private fun configuredInterfaces(): List<InterfaceConfig> =
            ReticulumConfigSnapshot
                .read(context)
                ?.configWithoutKey
                ?.enabledInterfaces
                .orEmpty()

        private suspend fun contacts(): List<ByteArray> =
            runCatching { contactRepository.getRestorableContactIdentitiesForActiveIdentity() }
                .getOrDefault(emptyList())
                .map { (destinationHex, _) -> destinationHex.hexToBytes() }

        /**
         * The identity to name as sender, carrying no key material.
         *
         * Both backends send from the stack's own delivery destination and never
         * read this argument — see `PythonRnsLxmf.sendLxmfMessageWithMethod` and
         * `NativeRnsBackendImpl.sendLxmfMessageWithMethod`, neither of which
         * passes it on. So nothing of the private key is handed across here, and
         * the public key is left empty rather than fetched for a call that will
         * not look at it. If a backend ever starts reading it, this is where a
         * cover message would stop being indistinguishable, and the place to fix.
         */
        private suspend fun sender(): Identity? =
            identityRepository.getActiveIdentitySync()?.let { active ->
                Identity(hash = active.identityHash.hexToBytes(), publicKey = ByteArray(0), privateKey = null)
            }
    }

/**
 * Send one cover message the way a direct message goes, and no other way.
 *
 * Direct delivery with no fallback to a propagation node. A real message that
 * cannot reach its recipient is worth parking on a relay for thirty days; a
 * cover message is not. Parking it would fill a stranger's relay with padding
 * for someone who is offline, and hand whoever runs that relay a record of this
 * device's sending rhythm — the one thing cover exists to blur. A peer who
 * cannot be reached directly simply gets nothing this time.
 */
internal suspend fun sendCover(
    lxmf: RnsLxmf,
    destination: ByteArray,
    fields: Map<Int, Any>,
    sender: Identity?,
): Result<Unit> {
    if (sender == null) return Result.failure(IllegalStateException("no active identity to send cover as"))
    return lxmf
        .sendLxmfMessageWithMethod(
            destinationHash = destination,
            content = "",
            sourceIdentity = sender,
            deliveryMethod = DeliveryMethod.DIRECT,
            tryPropagationOnFail = false,
            extraFields = fields,
        ).map { }
}
