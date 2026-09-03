package network.zamolxis.app.ui.screens.settings

import network.zamolxis.app.viewmodel.SettingsCardId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Маяк and Zamolxis are the same app; what differs is how much of the machine room the
 * settings screen shows before the developer gate is unlocked.
 */
class AudienceProfileTest {
    private fun visibleInMayak(card: SettingsCardId) = AudienceProfile.isVisible(card, developerMode = false, simpleUi = true)

    private fun visibleInExpert(card: SettingsCardId) = AudienceProfile.isVisible(card, developerMode = false, simpleUi = false)

    @Test
    fun `Mayak hides the operator cards`() {
        AudienceProfile.SIMPLE_UI_GATED.forEach { card ->
            assertFalse("$card should be hidden in Маяк", visibleInMayak(card))
        }
    }

    @Test
    fun `the expert build hides none of them`() {
        AudienceProfile.SIMPLE_UI_GATED.forEach { card ->
            assertTrue("$card should stay visible for the expert build", visibleInExpert(card))
        }
    }

    @Test
    fun `unlocking developer mode brings them all back`() {
        AudienceProfile.SIMPLE_UI_GATED.forEach { card ->
            assertTrue(
                "$card should reappear once the gate is unlocked",
                AudienceProfile.isVisible(card, developerMode = true, simpleUi = true),
            )
        }
    }

    @Test
    fun `everything a first-time user needs stays on screen in Mayak`() {
        val mustRemainVisible =
            listOf(
                SettingsCardId.PRIVACY,
                SettingsCardId.APP_LOCK,
                SettingsCardId.NOTIFICATIONS,
                SettingsCardId.LOCATION_SHARING,
                SettingsCardId.IMAGE_COMPRESSION,
                SettingsCardId.THEME,
                SettingsCardId.BATTERY,
                SettingsCardId.SHARE_COLUMBA,
                SettingsCardId.ABOUT,
            )

        mustRemainVisible.forEach { card ->
            assertTrue("$card must not disappear in Маяк", visibleInMayak(card))
        }
    }

    @Test
    fun `About stays visible or the developer gate becomes unreachable`() {
        // The gate is seven taps on the version inside About. Hiding About would make
        // every gated card unreachable forever.
        assertTrue(visibleInMayak(SettingsCardId.ABOUT))
    }

    @Test
    fun `battery optimisation is never hidden - without it messages do not arrive`() {
        assertTrue(visibleInMayak(SettingsCardId.BATTERY))
    }

    @Test
    fun `the extra hiding is a short list, not a second product`() {
        assertEquals(3, AudienceProfile.SIMPLE_UI_GATED.size)
    }

    @Test
    fun `the raw announce tab is offered exactly when the machine room is`() {
        // The tab is the announce list by hash: the map of the network to an operator,
        // a wall of hex to anyone else. It follows the same flag as the gated cards.
        //
        // It used to also drive the empty-contacts copy, which pointed at the Announce
        // Stream and so had to be swapped out for Маяк. That copy now offers "show my
        // code" and "scan a code" in every build, so the coupling is gone: the thing
        // Маяк had to special-case became the default.
        assertEquals(AudienceProfile.isSimpleUi, !AudienceProfile.showsNetworkTab)
    }
}
