package network.zamolxis.app.ui.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

/**
 * Unit tests for SharingDuration enum.
 *
 * The display text moved to `@StringRes displayTextRes`, so the wording assertions
 * resolve the resource against the default (English) locale rather than reading a
 * literal off the enum. Asserting the resolved text — not the bare resource id —
 * keeps these tests checking what the user actually sees.
 */
@RunWith(RobolectricTestRunner::class)
class SharingDurationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `SharingDuration has five entries`() {
        assertEquals(5, SharingDuration.entries.size)
    }

    @Test
    fun `FIFTEEN_MINUTES has correct display text`() {
        assertEquals("15 min", context.getString(SharingDuration.FIFTEEN_MINUTES.displayTextRes))
    }

    @Test
    fun `ONE_HOUR has correct display text`() {
        assertEquals("1 hour", context.getString(SharingDuration.ONE_HOUR.displayTextRes))
    }

    @Test
    fun `FOUR_HOURS has correct display text`() {
        assertEquals("4 hours", context.getString(SharingDuration.FOUR_HOURS.displayTextRes))
    }

    @Test
    fun `UNTIL_MIDNIGHT has correct display text`() {
        assertEquals("Until midnight", context.getString(SharingDuration.UNTIL_MIDNIGHT.displayTextRes))
    }

    @Test
    fun `INDEFINITE has correct display text`() {
        assertEquals("Until I stop", context.getString(SharingDuration.INDEFINITE.displayTextRes))
    }

    @Test
    fun `FIFTEEN_MINUTES has correct duration millis`() {
        assertEquals(15 * 60 * 1000L, SharingDuration.FIFTEEN_MINUTES.durationMillis)
    }

    @Test
    fun `ONE_HOUR has correct duration millis`() {
        assertEquals(60 * 60 * 1000L, SharingDuration.ONE_HOUR.durationMillis)
    }

    @Test
    fun `FOUR_HOURS has correct duration millis`() {
        assertEquals(4 * 60 * 60 * 1000L, SharingDuration.FOUR_HOURS.durationMillis)
    }

    @Test
    fun `UNTIL_MIDNIGHT has null duration millis`() {
        assertNull(SharingDuration.UNTIL_MIDNIGHT.durationMillis)
    }

    @Test
    fun `INDEFINITE has null duration millis`() {
        assertNull(SharingDuration.INDEFINITE.durationMillis)
    }

    @Test
    fun `calculateEndTime returns correct time for FIFTEEN_MINUTES`() {
        val startTime = 1000000L
        val expected = startTime + 15 * 60 * 1000L

        val actual = SharingDuration.FIFTEEN_MINUTES.calculateEndTime(startTime)

        assertEquals(expected, actual)
    }

    @Test
    fun `calculateEndTime returns correct time for ONE_HOUR`() {
        val startTime = 1000000L
        val expected = startTime + 60 * 60 * 1000L

        val actual = SharingDuration.ONE_HOUR.calculateEndTime(startTime)

        assertEquals(expected, actual)
    }

    @Test
    fun `calculateEndTime returns correct time for FOUR_HOURS`() {
        val startTime = 1000000L
        val expected = startTime + 4 * 60 * 60 * 1000L

        val actual = SharingDuration.FOUR_HOURS.calculateEndTime(startTime)

        assertEquals(expected, actual)
    }

    @Test
    fun `calculateEndTime returns null for INDEFINITE`() {
        val actual = SharingDuration.INDEFINITE.calculateEndTime(System.currentTimeMillis())

        assertNull(actual)
    }

    @Test
    fun `calculateEndTime returns midnight for UNTIL_MIDNIGHT`() {
        // Use a known time: 2025-01-15 10:00:00
        val calendar =
            Calendar.getInstance().apply {
                set(Calendar.YEAR, 2025)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 15)
                set(Calendar.HOUR_OF_DAY, 10)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        val startTime = calendar.timeInMillis

        val actual = SharingDuration.UNTIL_MIDNIGHT.calculateEndTime(startTime)

        assertNotNull(actual)

        // Verify it's the same day at 23:59:59.999
        val endCalendar =
            Calendar.getInstance().apply {
                timeInMillis = actual!!
            }
        assertEquals(2025, endCalendar.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, endCalendar.get(Calendar.MONTH))
        assertEquals(15, endCalendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(23, endCalendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, endCalendar.get(Calendar.MINUTE))
        assertEquals(59, endCalendar.get(Calendar.SECOND))
    }

    @Test
    fun `calculateEndTime for UNTIL_MIDNIGHT is always after start time`() {
        // Even if we start at 11pm, end time should still be later
        val calendar =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        val startTime = calendar.timeInMillis

        val actual = SharingDuration.UNTIL_MIDNIGHT.calculateEndTime(startTime)

        assertNotNull(actual)
        assertTrue(
            "End time should be >= start time",
            actual!! >= startTime,
        )
    }

    @Test
    fun `valueOf returns correct enum for valid names`() {
        assertEquals(
            SharingDuration.FIFTEEN_MINUTES,
            SharingDuration.valueOf("FIFTEEN_MINUTES"),
        )
        assertEquals(SharingDuration.ONE_HOUR, SharingDuration.valueOf("ONE_HOUR"))
        assertEquals(SharingDuration.FOUR_HOURS, SharingDuration.valueOf("FOUR_HOURS"))
        assertEquals(SharingDuration.UNTIL_MIDNIGHT, SharingDuration.valueOf("UNTIL_MIDNIGHT"))
        assertEquals(SharingDuration.INDEFINITE, SharingDuration.valueOf("INDEFINITE"))
    }

    @Test
    fun `entries are in correct order`() {
        val entries = SharingDuration.entries
        assertEquals(SharingDuration.FIFTEEN_MINUTES, entries[0])
        assertEquals(SharingDuration.ONE_HOUR, entries[1])
        assertEquals(SharingDuration.FOUR_HOURS, entries[2])
        assertEquals(SharingDuration.UNTIL_MIDNIGHT, entries[3])
        assertEquals(SharingDuration.INDEFINITE, entries[4])
    }

    @Test
    fun `durations are in increasing order`() {
        val fifteenMin = SharingDuration.FIFTEEN_MINUTES.durationMillis!!
        val oneHour = SharingDuration.ONE_HOUR.durationMillis!!
        val fourHours = SharingDuration.FOUR_HOURS.durationMillis!!

        assertTrue(fifteenMin < oneHour)
        assertTrue(oneHour < fourHours)
    }
}
