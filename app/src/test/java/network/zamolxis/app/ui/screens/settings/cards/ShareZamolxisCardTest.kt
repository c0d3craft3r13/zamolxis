package network.zamolxis.app.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.zamolxis.app.test.RegisterComponentActivityRule
import network.zamolxis.app.ui.theme.ZamolxisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareZamolxisCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    @Test
    fun `displays card title`() {
        composeTestRule.setContent {
            ZamolxisTheme {
                ShareZamolxisCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    onNavigateToApkSharing = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Share Zamolxis").assertIsDisplayed()
    }

    @Test
    fun `displays description when expanded`() {
        composeTestRule.setContent {
            ZamolxisTheme {
                ShareZamolxisCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    onNavigateToApkSharing = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("Share the Zamolxis app with someone nearby.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `displays share button when expanded`() {
        composeTestRule.setContent {
            ZamolxisTheme {
                ShareZamolxisCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    onNavigateToApkSharing = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Share Zamolxis APK").assertIsDisplayed()
    }

    @Test
    fun `hides content when collapsed`() {
        composeTestRule.setContent {
            ZamolxisTheme {
                ShareZamolxisCard(
                    isExpanded = false,
                    onExpandedChange = {},
                    onNavigateToApkSharing = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Share Zamolxis APK").assertDoesNotExist()
    }

    @Test
    fun `share button triggers navigation callback`() {
        var navigated = false
        composeTestRule.setContent {
            ZamolxisTheme {
                ShareZamolxisCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    onNavigateToApkSharing = { navigated = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Share Zamolxis APK").performClick()
        assertTrue(navigated)
    }

    @Test
    fun `expand change callback is invoked`() {
        var expandedValue: Boolean? = null
        composeTestRule.setContent {
            ZamolxisTheme {
                ShareZamolxisCard(
                    isExpanded = false,
                    onExpandedChange = { expandedValue = it },
                    onNavigateToApkSharing = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Share Zamolxis").performClick()
        assertEquals(true, expandedValue)
    }
}
