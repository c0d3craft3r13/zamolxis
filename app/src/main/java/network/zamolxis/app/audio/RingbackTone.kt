package network.zamolxis.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sound the caller hears while the other phone is ringing.
 *
 * Synthesised rather than taken from `ToneGenerator`: the platform's
 * `TONE_SUP_RINGTONE` is a harsh two-frequency buzz built for a 1970s exchange,
 * and it is the same sound every other app makes. This is a pair of soft bell
 * tones a fifth apart, rising — recognisable within one cadence as "it is
 * ringing", without being something you want to hold away from your ear.
 *
 * Kept quiet on purpose ([AMPLITUDE] is a sixth of full scale). A ringback is
 * feedback, not an announcement: the caller is holding the phone to their head
 * already, and the useful information is only that something is happening.
 *
 * ## How it is built
 *
 * One cadence is generated once into a [AudioTrack.MODE_STATIC] buffer and
 * looped by the audio hardware, so nothing has to wake up to keep it going for
 * the length of a ringout.
 *
 * Each pulse is a fundamental plus a quiet octave, which is what stops it
 * sounding like a test sine, shaped by a raised-cosine envelope. The envelope
 * is the part that matters most: a tone that starts and stops abruptly clicks,
 * and a click is the thing that makes a soft sound feel cheap.
 */
class RingbackTone {
    companion object {
        private const val TAG = "RingbackTone"

        internal const val SAMPLE_RATE = 44_100

        /** First pulse: a soft A above middle C. */
        private const val TONE_LOW_HZ = 440.0

        /** Second pulse, a perfect fifth up — the interval that reads as a question. */
        private const val TONE_HIGH_HZ = 660.0

        /** Peak level, well below full scale. See the class note on loudness. */
        internal const val AMPLITUDE = 0.16

        /** The octave partial that gives the tone a bell edge rather than a sine's flatness. */
        private const val OCTAVE_MIX = 0.22

        internal const val PULSE_MS = 220
        internal const val GAP_MS = 170

        /** Silence after the pair. Long enough that the cadence reads as ringing, not music. */
        internal const val REST_MS = 2_400

        /** Raised-cosine fade at each edge of a pulse; short enough to stay crisp. */
        private const val EDGE_MS = 25
    }

    private var track: AudioTrack? = null

    /** Whether the tone is currently looping. */
    val isPlaying: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /**
     * Start looping the ringback.
     *
     * Safe to call repeatedly — a second call while already playing is ignored,
     * so a recomposition or a repeated state emission cannot stack two tracks.
     */
    fun start() {
        if (isPlaying) return
        stop()

        try {
            val samples = buildCadence()
            val newTrack =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            // The usage that exists for exactly this: call
                            // signalling. It routes and ducks with call audio
                            // rather than with media, so the tone follows the
                            // earpiece/speaker choice and does not fight music.
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    ).setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

            newTrack.write(samples, 0, samples.size)
            // Looping in the track itself, so the cadence continues without a
            // thread or a timer of ours running for the whole ringout.
            newTrack.setLoopPoints(0, samples.size, -1)
            newTrack.play()
            track = newTrack
            Log.d(TAG, "Ringback started")
        } catch (e: Exception) {
            // A missing or busy audio path must never take the call down with
            // it — the call still works, it is just silent while it rings.
            Log.e(TAG, "Could not start ringback", e)
            stop()
        }
    }

    /** Stop and release. Idempotent. */
    fun stop() {
        val current = track ?: return
        track = null
        try {
            if (current.state == AudioTrack.STATE_INITIALIZED) current.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Ringback already stopped: ${e.message}")
        }
        try {
            current.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release ringback track", e)
        }
    }

    /**
     * One full cadence: low pulse, gap, high pulse, long rest.
     *
     * Internal so a test can assert what the ear is supposed to get —
     * that the gaps are actually silent, that nothing exceeds the level
     * this is meant to stay under, and that each pulse starts and ends at
     * zero rather than with the click an abrupt edge makes.
     */
    internal fun buildCadence(): ShortArray {
        val pulse = msToFrames(PULSE_MS)
        val gap = msToFrames(GAP_MS)
        val rest = msToFrames(REST_MS)
        val out = ShortArray(pulse + gap + pulse + rest)

        renderPulse(out, offset = 0, frames = pulse, frequency = TONE_LOW_HZ)
        renderPulse(out, offset = pulse + gap, frames = pulse, frequency = TONE_HIGH_HZ)
        // The gap and the rest are left as the zeros ShortArray already holds.
        return out
    }

    private fun renderPulse(
        out: ShortArray,
        offset: Int,
        frames: Int,
        frequency: Double,
    ) {
        val edge = msToFrames(EDGE_MS).coerceAtMost(frames / 2)
        for (i in 0 until frames) {
            val t = i.toDouble() / SAMPLE_RATE
            val wave =
                sin(2.0 * PI * frequency * t) +
                    OCTAVE_MIX * sin(4.0 * PI * frequency * t)
            // Normalised so adding the octave cannot push the peak past AMPLITUDE.
            val value = wave / (1.0 + OCTAVE_MIX) * AMPLITUDE * envelope(i, frames, edge)
            out[offset + i] = (value * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /**
     * Raised-cosine ramp in, flat, raised-cosine ramp out.
     *
     * Half a cosine period rather than a straight line: a linear ramp still has
     * a corner at each end of it, and corners are audible as a faint tick.
     */
    private fun envelope(
        index: Int,
        frames: Int,
        edge: Int,
    ): Double =
        when {
            edge <= 0 -> 1.0
            index < edge -> 0.5 * (1.0 - cos(PI * index / edge))
            index >= frames - edge -> 0.5 * (1.0 - cos(PI * (frames - index) / edge))
            else -> 1.0
        }

    private fun msToFrames(millis: Int): Int = SAMPLE_RATE * millis / 1000
}
