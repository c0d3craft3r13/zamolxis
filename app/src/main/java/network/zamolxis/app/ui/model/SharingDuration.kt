package network.zamolxis.app.ui.model

import androidx.annotation.StringRes
import java.util.Calendar
import network.zamolxis.app.R

/**
 * Duration options for location sharing.
 *
 * @property displayTextRes User-facing text resource for the duration option
 * @property durationMillis Duration in milliseconds, or null for computed/indefinite durations
 */
enum class SharingDuration(
    @param:StringRes val displayTextRes: Int,
    val durationMillis: Long?,
) {
    FIFTEEN_MINUTES(R.string.locationshare_duration_15min, 15 * 60 * 1000L),
    ONE_HOUR(R.string.locationshare_duration_1hour, 60 * 60 * 1000L),
    FOUR_HOURS(R.string.locationshare_duration_4hours, 4 * 60 * 60 * 1000L),
    UNTIL_MIDNIGHT(R.string.locationshare_duration_midnight, null),
    INDEFINITE(R.string.locationshare_duration_indefinite, null),
    ;

    /**
     * Calculate the end timestamp for this sharing duration.
     *
     * @param startTimeMillis The start time in milliseconds since epoch
     * @return The end time in milliseconds since epoch, or null for INDEFINITE
     */
    fun calculateEndTime(startTimeMillis: Long = System.currentTimeMillis()): Long? {
        return when (this) {
            INDEFINITE -> null
            UNTIL_MIDNIGHT -> {
                val calendar =
                    Calendar.getInstance().apply {
                        timeInMillis = startTimeMillis
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                        set(Calendar.SECOND, 59)
                        set(Calendar.MILLISECOND, 999)
                    }
                calendar.timeInMillis
            }
            else -> startTimeMillis + (durationMillis ?: 0L)
        }
    }
}
