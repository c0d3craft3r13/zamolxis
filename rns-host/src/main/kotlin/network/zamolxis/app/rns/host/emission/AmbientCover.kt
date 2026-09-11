package network.zamolxis.app.rns.host.emission

import network.zamolxis.app.rns.api.model.EmissionMedium

/**
 * What the surroundings currently offer to hide in.
 *
 * Both numbers are measured, not assumed, and both are gathered by listening:
 * counting who else is advertising costs no transmission, and reading how much
 * the phone itself has sent costs nothing at all.
 *
 * Either may be null, meaning the window could not be measured — a permission
 * that is not held, Bluetooth switched off, a screen dark enough that the system
 * suspends the scan. That is deliberately not the same value as zero. A
 * suspended scan and an empty room both produce no results, and reading the
 * first as the second is how a device concludes it is alone in a room holding
 * twenty other radios.
 *
 * @param nearbyRadios distinct radios heard during the window, or null if unmeasurable.
 * @param foreignBytes bytes this device sent during the window that were not ours,
 *   or null if unmeasurable.
 */
data class AmbientCover(
    val nearbyRadios: Int?,
    val foreignBytes: Long?,
) {
    companion object {
        /** Nothing measured yet. Not a claim that nobody is there. */
        val UNKNOWN = AmbientCover(nearbyRadios = null, foreignBytes = null)
    }
}

/**
 * How much company there has to be before padding hides anything.
 *
 * ## Why cover is required at all
 *
 * Padding works by making one more transmission unremarkable. In an empty room
 * it does the opposite: a steady rhythm from the only device transmitting is
 * more conspicuous than silence, and it runs continuously, so it is available
 * to be noticed for far longer than a single message would have been.
 *
 * ## Why the window is short
 *
 * Phones rotate their Bluetooth advertising address roughly every fifteen
 * minutes. Counting distinct addresses over a long window therefore counts one
 * phone several times, and the error runs in the dangerous direction: it
 * reports a crowd that is not there, and padding starts in an empty room. Half
 * a minute is short enough that a rotation almost never falls inside it, so a
 * device is counted once. Beacons that advertise more slowly than that go
 * uncounted — they were not much cover anyway.
 */
object CoverThresholds {
    /** How far back a measurement looks. Short on purpose — see above. */
    const val WINDOW_MS: Long = 30_000

    /** How many other radios have to be audible before ours is unremarkable. */
    const val NEARBY_RADIOS: Int = 10

    /**
     * How much other traffic has to have left this device before ours sits inside it.
     *
     * A floor rather than a rate: what matters is that the phone was talking at
     * all, the same way what matters on Bluetooth is that other radios were
     * heard. The value is set above the couple of hundred bytes an idle phone
     * spends on keeping connections open, so a sleeping device does not read as
     * company. It is a chosen default, not a measured constant.
     */
    const val FOREIGN_BYTES: Long = 4096
}

/**
 * Whether padding on this medium would hide in what is already there.
 *
 * The observer sits in a different place for each medium, so the company that
 * counts is different too. On Bluetooth the observer is within radio range and
 * hears what we hear, so cover is the other radios around us. On a network the
 * observer watches this subscriber rather than the internet, so cover is the
 * other traffic from this same device — nobody else's traffic can hide ours.
 *
 * A window that could not be measured is treated as no company, which is the
 * reading that keeps a quiet device quiet.
 *
 * On LoRa there is no answer that makes padding worth it. Company does not help:
 * finding a transmitter works on the signal itself whatever else is in the band,
 * and every padded byte comes out of a firmware-enforced airtime budget that a
 * real message will want later.
 */
fun EmissionMedium.hasCover(cover: AmbientCover): Boolean =
    when (this) {
        EmissionMedium.LORA_CARRIED -> false
        EmissionMedium.LORA_REMOTE -> false
        EmissionMedium.BLUETOOTH_LE -> (cover.nearbyRadios ?: 0) >= CoverThresholds.NEARBY_RADIOS
        EmissionMedium.NETWORK -> (cover.foreignBytes ?: 0) >= CoverThresholds.FOREIGN_BYTES
    }
