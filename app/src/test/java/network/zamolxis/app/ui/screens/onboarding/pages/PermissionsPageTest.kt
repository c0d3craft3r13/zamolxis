package network.zamolxis.app.ui.screens.onboarding.pages

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import network.zamolxis.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for PermissionsPage composable.
 *
 * Tests cover:
 * - Notification permission card display and interactions
 * - Battery optimization card display and interactions
 * - Permission granted state indicators
 * - Navigation button callbacks
 * - Permission descriptions display
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionsPageTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Header and Title Tests ==========

    @Test
    fun permissionsPage_displaysTitle() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Stay Connected").assertIsDisplayed()
    }

    @Test
    fun permissionsPage_displaysSubtitle() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Zamolxis can notify you when:").assertIsDisplayed()
    }

    // ========== Feature Items Tests ==========

    @Test
    fun permissionsPage_displaysNewMessagesFeature() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("New messages arrive").assertIsDisplayed()
    }

    @Test
    fun permissionsPage_displaysSomeoneAddsContactFeature() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Someone adds you as a contact").assertIsDisplayed()
    }

    @Test
    fun permissionsPage_displaysDeliveryConfirmationsFeature() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Delivery confirmations are received").assertIsDisplayed()
    }

    // ========== Notification Card Tests ==========

    @Test
    fun notificationCard_isDisplayed() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test
    fun notificationCard_displaysDescription() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then
        composeTestRule.onNodeWithText("Get alerts for new messages").assertIsDisplayed()
    }

    @Test
    fun notificationCard_showsEnableButton_whenNotGranted() {
        // When - battery exempt to isolate the notification Enable button
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = true, // Battery already exempt to isolate notification button
                microphoneGranted = true, // Granted so only the notification card offers Enable
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Should have exactly one Enable button visible (scroll to make visible)
        composeTestRule.onNodeWithText("Enable").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun notificationEnableButton_triggersCallback() {
        // Given
        var callbackInvoked = false
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = true, // Battery already exempt to isolate notification button
                microphoneGranted = true, // Granted so only the notification card offers Enable
                onEnableNotifications = { callbackInvoked = true },
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // When - Click the Enable button (for notifications since battery is exempt)
        composeTestRule.onNodeWithText("Enable").performClick()

        // Then
        assertTrue("onEnableNotifications callback should be invoked", callbackInvoked)
    }

    @Test
    fun notificationCard_showsSuccessIndicator_whenGranted() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Should show check icon with "Granted" content description
        composeTestRule.onNodeWithContentDescription("Granted").assertIsDisplayed()
    }

    // ========== Battery Optimization Card Tests ==========

    @Test
    fun batteryOptimizationCard_isDisplayed() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Unrestricted Battery").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batteryOptimizationCard_displaysDescription() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Receive messages even when phone is idle").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batteryOptimizationCard_displaysSecondaryDescription() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Prevents Android from pausing Zamolxis").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batteryOptimizationCard_showsEnableButton_whenNotExempt() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true, // Notification granted to isolate battery button
                batteryOptimizationExempt = false,
                microphoneGranted = true, // Granted so this test keeps exactly one Enable button
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Should have Enable button visible for battery, scroll to make visible
        composeTestRule.onNodeWithText("Enable").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun batteryEnableButton_triggersCallback() {
        // Given
        var callbackInvoked = false
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true, // Notification granted to isolate battery button
                batteryOptimizationExempt = false,
                microphoneGranted = true, // Granted so this test keeps exactly one Enable button
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = { callbackInvoked = true },
                onBack = {},
                onContinue = {},
            )
        }

        // When - Click the Enable button (for battery since notifications is granted)
        composeTestRule.onNodeWithText("Enable").performScrollTo().performClick()

        // Then
        assertTrue("onEnableBatteryOptimization callback should be invoked", callbackInvoked)
    }

    @Test
    fun batteryOptimizationCard_showsSuccessIndicator_whenExempt() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = true,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Should show check icon with "Granted" content description, scroll to make visible
        composeTestRule.onNodeWithContentDescription("Granted").performScrollTo().assertIsDisplayed()
    }

    // ========== Both Permissions Granted Tests ==========

    @Test
    fun allPermissionsGranted_showsNoEnableButtons() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true,
                batteryOptimizationExempt = true,
                microphoneGranted = true, // Granted so this test keeps exactly one Enable button
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Enable button should not exist when both are granted
        composeTestRule.onNodeWithText("Enable").assertDoesNotExist()
    }

    @Test
    fun noPermissionsGranted_showsEveryPermissionCard() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - Every permission card should show its title
        composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unrestricted Battery").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Microphone").performScrollTo().assertIsDisplayed()
    }

    // ========== Microphone Tests ==========

    /**
     * The reason this card exists: asking for the microphone at the first
     * incoming call means a permission dialog on top of a ringing phone, and
     * the call is usually gone by the time it is answered.
     */
    @Test
    fun microphoneCard_showsEnableButton_whenNotGranted() {
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true, // Granted so only the microphone card offers Enable
                batteryOptimizationExempt = true,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        composeTestRule.onNodeWithText("Enable").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun microphoneEnableButton_triggersCallback() {
        var callbackInvoked = false
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true, // Granted so only the microphone card offers Enable
                batteryOptimizationExempt = true,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = { callbackInvoked = true },
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        composeTestRule.onNodeWithText("Enable").performScrollTo().performClick()

        assertTrue("onEnableMicrophone callback should be invoked", callbackInvoked)
    }

    @Test
    fun microphoneCard_showsSuccessIndicator_whenGranted() {
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = true,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Granted").performScrollTo().assertIsDisplayed()
    }

    // ========== Navigation Button Tests ==========

    @Test
    fun backButton_isDisplayed() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Back").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backButton_triggersCallback() {
        // Given
        var callbackInvoked = false
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = { callbackInvoked = true },
                onContinue = {},
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Back").performScrollTo().performClick()

        // Then
        assertTrue("onBack callback should be invoked", callbackInvoked)
    }

    @Test
    fun continueButton_isDisplayed() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - scroll to make visible
        composeTestRule.onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun continueButton_triggersCallback() {
        // Given
        var callbackInvoked = false
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = { callbackInvoked = true },
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Continue").performScrollTo().performClick()

        // Then
        assertTrue("onContinue callback should be invoked", callbackInvoked)
    }

    // ========== Callback Invocation Count Tests ==========

    @Test
    fun backButton_callbackCalledOnce() {
        // Given
        var callCount = 0
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = { callCount++ },
                onContinue = {},
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Back").performScrollTo().performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, callCount)
    }

    @Test
    fun continueButton_callbackCalledOnce() {
        // Given
        var callCount = 0
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = { callCount++ },
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Continue").performScrollTo().performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, callCount)
    }

    @Test
    fun notificationEnableButton_callbackCalledOnce() {
        // Given
        var callCount = 0
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = true, // Isolate notification button
                microphoneGranted = true, // Granted so only the notification card offers Enable
                onEnableNotifications = { callCount++ },
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // When
        composeTestRule.onNodeWithText("Enable").performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, callCount)
    }

    @Test
    fun batteryEnableButton_callbackCalledOnce() {
        // Given
        var callCount = 0
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true, // Isolate battery button
                batteryOptimizationExempt = false,
                microphoneGranted = true, // Granted so this test keeps exactly one Enable button
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = { callCount++ },
                onBack = {},
                onContinue = {},
            )
        }

        // When - scroll to and click
        composeTestRule.onNodeWithText("Enable").performScrollTo().performClick()

        // Then
        assertEquals("Callback should be called exactly once", 1, callCount)
    }

    // ========== State Transition Tests ==========

    @Test
    fun notificationCard_transitionsFromEnableButtonToSuccessIndicator() {
        // Given - Only notification granted to have exactly one Granted indicator
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = true,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - exactly the granted card shows a success indicator, and the two
        // that are not granted still offer their buttons. Asserted by count
        // rather than by a single node: with three cards on the page, "the
        // Granted icon" is no longer unambiguous.
        composeTestRule.onAllNodesWithContentDescription("Granted").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Enable").assertCountEquals(2)
    }

    // ========== Complete Page Layout Tests ==========

    @Test
    fun permissionsPage_displaysAllElements() {
        // When
        composeTestRule.setContent {
            PermissionsPage(
                notificationsGranted = false,
                batteryOptimizationExempt = false,
                microphoneGranted = false,
                onEnableNotifications = {},
                onEnableMicrophone = {},
                onEnableBatteryOptimization = {},
                onBack = {},
                onContinue = {},
            )
        }

        // Then - All key elements should be displayed (scroll for off-screen elements)
        composeTestRule.onNodeWithText("Stay Connected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zamolxis can notify you when:").assertIsDisplayed()
        composeTestRule.onNodeWithText("New messages arrive").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Someone adds you as a contact").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Delivery confirmations are received").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Notifications").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Get alerts for new messages").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Unrestricted Battery").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Receive messages even when phone is idle").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Prevents Android from pausing Zamolxis").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
    }
}
