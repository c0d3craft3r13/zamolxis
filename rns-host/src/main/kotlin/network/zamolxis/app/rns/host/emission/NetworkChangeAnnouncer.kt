package network.zamolxis.app.rns.host.emission

/**
 * The announce a device makes when it has just arrived somewhere new.
 *
 * ## Why this is its own object
 *
 * It used to be four lines inside the service, and those four lines called a
 * method that did nothing. `ReticulumServiceBinder.announceLxmfDestination()`
 * is `= Unit`, left behind when announces moved out of the service and
 * documented as being emitted by a class that now exists only in test sources.
 * Nothing on the air, and nobody noticed — because the timestamps were written
 * anyway, so everything downstream behaved as though it had worked.
 *
 * That is the part worth naming. Recording the announce was unconditional, and
 * the record is load-bearing: the app process watches it and treats it as
 * "already announced, skip this cycle", while the settings screen shows it as
 * the last time this device was heard. A phone changing networks often would
 * defer its announce again and again — possibly never announcing at all — while
 * showing a time that never happened.
 *
 * ## The rule
 *
 * Write the record only when something actually went out. A failed announce, a
 * refusal for silence, or no identity to announce leaves the previous state
 * untouched, so the periodic schedule still owes an announce and the screen
 * still shows the last one that was real.
 */
class NetworkChangeAnnouncer(
    private val emissions: EmissionPolicy,
    private val displayName: suspend () -> String?,
    private val announce: suspend (String) -> Result<Unit>,
    private val recordAnnounced: suspend (Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Announce because the network changed, if that is allowed and possible.
     *
     * @return true when an announce reached the stack and was recorded.
     */
    suspend fun announceOnNetworkChange(): Boolean {
        // The service's own idea, not the operator's — so silence has to be
        // checked here. A network change is exactly the moment a device moved,
        // which is exactly when an unprompted transmission is worth the most to
        // whoever is looking for it.
        if (!emissions.mayEmit()) return false

        val name = displayName() ?: return false
        val sent = announce(name).isSuccess
        if (sent) recordAnnounced(now())
        return sent
    }
}
