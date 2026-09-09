package network.zamolxis.app.rns.host.persistence

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import network.zamolxis.app.data.repository.ContactRepository
import network.zamolxis.app.rns.api.RnsBackend
import network.zamolxis.app.rns.api.model.NetworkStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the public keys of known contacts into the RNS identity cache as soon
 * as the stack is up, from the `:reticulum` process itself.
 *
 * ## Why this is a security control and not a cache warm-up
 *
 * An inbound LXMF message is signed by its sender, and the signature can only
 * be checked against the sender's public key. When RNS cannot recall that key
 * it does not reject the message — it reports that the source is unknown and
 * hands it over unverified. So a contact whose key happens to be missing from
 * the cache is a contact whose name can be worn by anyone, for exactly as long
 * as the key stays missing.
 *
 * Priming turns that into an invariant worth stating: **if a public key for a
 * sender exists anywhere in this app, the stack has it, so the message is
 * either verified or refused.** "Source unknown" then means what it says — a
 * stranger nobody could have checked — rather than a gap.
 *
 * ## Why here rather than in the app process
 *
 * `ZamolxisApplication` already re-seeds identities, but only while the UI
 * process is alive, and it does so asynchronously after startup. The service
 * receives messages when the UI process is dead, restarts on its own after an
 * OOM kill, and gets messages flushed at it the moment LXMF comes up. The one
 * situation that reliably empties the cache — a fresh install restored from an
 * encrypted export, where contacts come back into the database but RNS's own
 * storage starts empty — is precisely a first run, when queued mail arrives
 * first and the UI may still be starting.
 *
 * Re-priming on every transition to [NetworkStatus.READY] rather than once:
 * the stack is restarted whenever interfaces change, and `Identity.remember`
 * is idempotent, so the repeat costs nothing and covers the restart.
 */
@Singleton
class PeerIdentityPrimer
    @Inject
    constructor(
        private val contactRepository: ContactRepository,
    ) {
        private companion object {
            const val TAG = "PeerIdentityPrimer"

            /** Chunked for the same reason the app process chunks: a large address book must not arrive as one allocation. */
            const val BATCH_SIZE = 500
        }

        /**
         * Watch [backend] and prime whenever it becomes ready.
         *
         * @return the watching job, so its owner can cancel it — this collects
         *   for as long as the service lives and never completes on its own.
         */
        fun start(
            backend: RnsBackend,
            scope: CoroutineScope,
        ): Job =
            scope.launch {
                // `networkStatus` is a StateFlow, so it already conflates repeats:
                // this fires once per genuine arrival at READY, and a stack restart
                // (READY -> INITIALIZING -> READY) primes again, which is wanted.
                backend.core.networkStatus
                    .filter { it is NetworkStatus.READY }
                    .collect { prime(backend) }
            }

        /**
         * Push every contact public key we hold into the stack's identity cache.
         *
         * Failures are logged and swallowed per batch. A contact that cannot be
         * restored is one whose messages will arrive unverified — bad, and worth
         * the log line — but aborting would leave every *later* contact in the
         * same state, which is worse.
         *
         * @return how many identities the backend accepted
         */
        suspend fun prime(backend: RnsBackend): Int {
            val identities =
                runCatching { contactRepository.getRestorableContactIdentitiesForActiveIdentity() }
                    .getOrElse { error ->
                        Log.w(TAG, "Could not read contact identities to prime", error)
                        return 0
                    }
            if (identities.isEmpty()) return 0

            var primed = 0
            identities.chunked(BATCH_SIZE).forEach { batch ->
                backend.core
                    .restorePeerIdentities(batch)
                    .onSuccess { primed += it }
                    .onFailure { Log.w(TAG, "Could not prime ${batch.size} contact identities", it) }
            }
            Log.i(TAG, "Primed $primed of ${identities.size} contact identities for signature checking")
            return primed
        }
    }
