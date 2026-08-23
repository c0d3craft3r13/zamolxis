package network.zamolxis.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * What the caller's ear is supposed to get.
 *
 * The audible qualities asked for — quiet, clean, recognisably a ring — are all
 * properties of the generated buffer, so they can be checked here rather than
 * left to whether someone remembered to listen.
 */
class RingbackToneTest {
    private val cadence = RingbackTone().buildCadence()

    private fun frames(millis: Int) = RingbackTone.SAMPLE_RATE * millis / 1000

    private val pulseFrames = frames(RingbackTone.PULSE_MS)
    private val gapFrames = frames(RingbackTone.GAP_MS)

    @Test
    fun `cadence is two pulses, a gap and a rest`() {
        val expected =
            frames(RingbackTone.PULSE_MS) * 2 +
                frames(RingbackTone.GAP_MS) +
                frames(RingbackTone.REST_MS)

        assertEquals(expected, cadence.size)
    }

    /**
     * The whole point of the design: it has to be audible without being
     * something the caller holds away from their ear.
     */
    @Test
    fun `never exceeds the level it promises`() {
        val ceiling = (RingbackTone.AMPLITUDE * Short.MAX_VALUE).toInt()
        val peak = cadence.maxOf { abs(it.toInt()) }

        assertTrue("peak $peak should stay within $ceiling", peak <= ceiling)
    }

    /** Loud enough to hear, or the tone tells the caller nothing. */
    @Test
    fun `is loud enough to be heard`() {
        val floor = (RingbackTone.AMPLITUDE * Short.MAX_VALUE * 0.8).toInt()
        val peak = cadence.maxOf { abs(it.toInt()) }

        assertTrue("peak $peak should reach at least $floor", peak >= floor)
    }

    @Test
    fun `the gap between the two pulses is silent`() {
        val gap = cadence.copyOfRange(pulseFrames, pulseFrames + gapFrames)

        assertTrue("gap must be silence", gap.all { it.toInt() == 0 })
    }

    @Test
    fun `the rest after the pair is silent`() {
        val restStart = pulseFrames + gapFrames + pulseFrames
        val rest = cadence.copyOfRange(restStart, cadence.size)

        assertTrue("rest must be silence", rest.all { it.toInt() == 0 })
    }

    /**
     * A pulse that begins or ends away from zero clicks, and the click is what
     * makes an otherwise soft sound feel cheap. The envelope exists to prevent
     * exactly this, so it is worth asserting rather than assuming.
     */
    @Test
    fun `every pulse edge starts and ends at silence`() {
        val edges =
            listOf(
                0 to "first pulse start",
                pulseFrames - 1 to "first pulse end",
                pulseFrames + gapFrames to "second pulse start",
                pulseFrames + gapFrames + pulseFrames - 1 to "second pulse end",
            )

        edges.forEach { (index, label) ->
            assertEquals("$label should be at zero", 0, cadence[index].toInt())
        }
    }

    /** The two pulses differ — a rising pair, not the same note twice. */
    @Test
    fun `the second pulse is a different note from the first`() {
        val first = cadence.copyOfRange(0, pulseFrames)
        val second = cadence.copyOfRange(pulseFrames + gapFrames, pulseFrames + gapFrames + pulseFrames)

        // Counting zero crossings is enough to tell two pitches apart without
        // reimplementing the synthesis in the test.
        assertTrue(
            "the second pulse should be the higher note",
            zeroCrossings(second) > zeroCrossings(first),
        )
    }

    private fun zeroCrossings(samples: ShortArray): Int =
        (1 until samples.size).count { i ->
            val previous = samples[i - 1].toInt()
            val current = samples[i].toInt()
            (previous < 0 && current >= 0) || (previous > 0 && current <= 0)
        }

    /** A cadence that does not loop seamlessly ticks once per repeat. */
    @Test
    fun `the cadence loops without a seam`() {
        assertEquals("last sample", 0, cadence.last().toInt())
        assertEquals("first sample", 0, cadence.first().toInt())
    }
}
