package network.zamolxis.app.ui.screens.settings

import network.zamolxis.app.BuildConfig
import network.zamolxis.app.viewmodel.SettingsCardId

/**
 * Which settings a build shows before the developer gate is unlocked.
 *
 * The app already hid the network internals — `NetworkCard`, `IdentityCard`, the
 * propagation and RNode cards — behind seven taps on the version, so that it "reads as a
 * plain messenger by default". Маяк takes that a little further for people who will never
 * unlock the gate: same code, same protocol, less of the machine room on screen.
 *
 * Only three cards differ, because most of the work was already done. Kept as one list
 * rather than scattered `BuildConfig.SIMPLE_UI` checks, so the difference between the two
 * products is readable in one place.
 */
object AudienceProfile {
    /** True in the Маяк flavor. */
    val isSimpleUi: Boolean = BuildConfig.SIMPLE_UI

    /**
     * Whether the Contacts screen offers the "Network" tab.
     *
     * That tab is the raw announce list — every node the mesh has mentioned, by hash.
     * For an operator it is the map of the network; to a first-time user it is a wall of
     * hex for people they have never met and cannot message. Маяк leaves the two ways
     * that actually connect two humans, which the empty state now offers directly.
     */
    val showsNetworkTab: Boolean = !isSimpleUi

    /**
     * Cards Маяк folds behind the developer gate on top of what is already hidden.
     *
     * - [SettingsCardId.SHARED_INSTANCE_BANNER] reports on a shared RNS instance. That is
     *   an operator's concern, and to anyone else it reads as an error.
     * - [SettingsCardId.VOICE_CALL_PERMISSIONS] belongs in the call flow. A permission is
     *   asked for when it is needed, not offered in a list of twenty cards.
     * - [SettingsCardId.MAP_SOURCES] picks a tile provider. One sensible default is the
     *   right number of choices here; downloading offline maps stays available.
     */
    internal val SIMPLE_UI_GATED =
        setOf(
            SettingsCardId.SHARED_INSTANCE_BANNER,
            SettingsCardId.VOICE_CALL_PERMISSIONS,
            SettingsCardId.MAP_SOURCES,
        )

    /**
     * Whether [card] should be rendered in this build.
     *
     * @param developerMode true once the user has unlocked the gate from the About card
     */
    fun isVisible(
        card: SettingsCardId,
        developerMode: Boolean,
    ): Boolean = isVisible(card, developerMode, isSimpleUi)

    /**
     * The decision itself, with the flavor passed in so both products can be tested from
     * whichever variant the unit tests happen to run under.
     */
    internal fun isVisible(
        card: SettingsCardId,
        developerMode: Boolean,
        simpleUi: Boolean,
    ): Boolean = developerMode || !(simpleUi && card in SIMPLE_UI_GATED)
}
