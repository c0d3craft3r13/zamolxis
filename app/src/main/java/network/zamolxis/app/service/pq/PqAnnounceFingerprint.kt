package network.zamolxis.app.service.pq

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import network.zamolxis.app.data.repository.IdentityRepository
import network.zamolxis.app.data.repository.PqKeyRepository
import network.zamolxis.app.repository.SettingsRepository
import network.zamolxis.crypto.pq.PqMode

/**
 * The post-quantum fingerprint to advertise in an announce.
 *
 * One implementation, injected everywhere an announce is triggered. It exists
 * because there is more than one such place — the automatic scheduler and three
 * separate "announce now" actions in the UI — and each of them previously called
 * the announce API without a fingerprint. A peer that only ever heard one of
 * those manual announces therefore never learned this identity could be sealed
 * to, and no test caught it because each call site looked locally correct.
 *
 * Failure is deliberately silent and returns null: being announced without the
 * hint costs the first message of a conversation, while not being announced at
 * all costs reachability.
 */
@Singleton
class PqAnnounceFingerprint
    @Inject
    constructor(
        private val identityRepository: IdentityRepository,
        private val pqKeyRepository: PqKeyRepository,
        private val settingsRepository: SettingsRepository,
    ) {
        /**
         * The active identity's 16-byte fingerprint, or null if there is none to
         * advertise.
         *
         * Null while the feature is off. Advertising a fingerprint the send path
         * will never act on is worse than saying nothing: a peer in "always seal"
         * mode would treat us as post-quantum capable and refuse to send at all,
         * waiting for a key that is never coming. It also avoids generating key
         * material for someone who switched the feature off.
         */
        suspend fun current(): ByteArray? =
            runCatching {
                if (settingsRepository.getPostQuantumMode() == PqMode.OFF) return@runCatching null
                identityRepository.getActiveIdentitySync()?.identityHash?.let { identityHash ->
                    pqKeyRepository.ourFingerprint(identityHash)
                }
            }.getOrElse {
                Log.w(TAG, "Could not read post-quantum fingerprint; announcing without it", it)
                null
            }

        private companion object {
            private const val TAG = "PqAnnounceFingerprint"
        }
    }
