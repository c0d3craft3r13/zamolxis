package network.zamolxis.app.data.model

import androidx.annotation.StringRes
import network.zamolxis.app.R

/**
 * User preference for the map base style.
 *
 * [AUTO] follows the system day/night theme at render time.
 * [LIGHT] and [DARK] override the system setting.
 */
enum class MapStylePreference(
    @param:StringRes val displayNameRes: Int,
) {
    AUTO(R.string.mapstyle_auto),
    LIGHT(R.string.mapstyle_light),
    DARK(R.string.mapstyle_dark),
    ;

    companion object {
        val DEFAULT = AUTO

        fun fromName(name: String): MapStylePreference = entries.find { it.name == name } ?: DEFAULT
    }
}
