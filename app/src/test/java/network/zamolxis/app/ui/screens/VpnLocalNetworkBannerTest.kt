package network.zamolxis.app.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import network.zamolxis.app.test.RegisterComponentActivityRule
import network.zamolxis.app.ui.components.VpnLocalNetworkBanner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The banner is the only thing that tells a user why local peers disappeared
 * while a VPN is on — the app itself cannot route around a tunnel that refuses
 * to be bypassed. It must therefore appear exactly when a tunnel is up, and
 * never linger once it is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VpnLocalNetworkBannerTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun `the banner explains the tunnel while one is active`() {
        composeRule.setContent {
            MaterialTheme { VpnLocalNetworkBanner(vpnActive = true) }
        }

        composeRule
            .onNodeWithText(
                "VPN is on — peers on your local network may not be found. " +
                    "Exclude Zamolxis in your VPN app to reach them.",
            ).assertIsDisplayed()
    }

    @Test
    fun `no tunnel means no banner`() {
        composeRule.setContent {
            MaterialTheme { VpnLocalNetworkBanner(vpnActive = false) }
        }

        // Nothing to explain, and a standing warning about a VPN that is off
        // would train the user to ignore it.
        composeRule.onNodeWithText("VPN is on", substring = true).assertDoesNotExist()
    }
}
