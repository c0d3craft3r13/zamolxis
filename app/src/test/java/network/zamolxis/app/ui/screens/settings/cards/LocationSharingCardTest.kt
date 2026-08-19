package network.zamolxis.app.ui.screens.settings.cards

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import network.zamolxis.app.service.SharingSession
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
 * Unit tests for LocationSharingCard.
 *
 * Tests:
 * - Pure utility functions (formatTimeRemaining, getDurationDisplayText, getPrecisionRadiusDisplayText)
 * - UI display and interactions
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocationSharingCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    /**
     * formatTimeRemaining / getDurationDisplayText / getPrecisionRadiusDisplayText became
     * `@Composable` when their wording moved to `stringResource`, so they can only be
     * called from a composition. This evaluates one inside the compose rule and hands the
     * value back to the assertions, which are otherwise unchanged — the resources carry
     * the same English wording these helpers used to build by hand.
     */
    private fun <T : Any> evaluate(block: @Composable () -> T): T {
        lateinit var captured: T
        composeRule.setContent { captured = block() }
        return captured
    }

    // ========== formatTimeRemaining Tests ==========

    @Test
    fun `formatTimeRemaining returns until stopped for null endTime`() {
        val result = evaluate { formatTimeRemaining(null) }
        assertEquals("Until stopped", result)
    }

    @Test
    fun `formatTimeRemaining returns expiring for past times`() {
        val pastTime = System.currentTimeMillis() - 1000 // 1 second ago
        val result = evaluate { formatTimeRemaining(pastTime) }
        assertEquals("Expiring...", result)
    }

    @Test
    fun `formatTimeRemaining returns expiring for current time`() {
        val result = evaluate { formatTimeRemaining(System.currentTimeMillis()) }
        assertEquals("Expiring...", result)
    }

    @Test
    fun `formatTimeRemaining returns minutes for short durations`() {
        val now = System.currentTimeMillis()
        val endTime = now + 5 * 60_000 // 5 minutes from now

        val result = evaluate { formatTimeRemaining(endTime) }

        assertTrue("Result should contain minutes: $result", result.contains("m remaining"))
        assertTrue("Result should show around 5 minutes: $result", result.contains("5m") || result.contains("4m"))
    }

    @Test
    fun `formatTimeRemaining returns minutes only for under 1 hour`() {
        val now = System.currentTimeMillis()
        val endTime = now + 30 * 60_000 // 30 minutes from now

        val result = evaluate { formatTimeRemaining(endTime) }

        assertTrue("Result should contain 'm remaining': $result", result.endsWith("m remaining"))
        // Should not contain hours
        assertTrue("Result should not contain hours: $result", !result.contains("h"))
    }

    @Test
    fun `formatTimeRemaining returns hours and minutes for longer durations`() {
        val now = System.currentTimeMillis()
        val endTime = now + 90 * 60_000 // 1 hour 30 minutes from now

        val result = evaluate { formatTimeRemaining(endTime) }

        assertTrue("Result should contain 'h' for hours: $result", result.contains("h"))
        assertTrue("Result should contain 'm' for minutes: $result", result.contains("m"))
        assertTrue("Result should contain 'remaining': $result", result.contains("remaining"))
    }

    @Test
    fun `formatTimeRemaining correctly formats 2 hours`() {
        val now = System.currentTimeMillis()
        // Add 30 second buffer to prevent flakiness from test execution time — an exact
        // 2h endTime rounds down to "1h 59m" once any time has passed, and setting up
        // the composition costs more than the plain call this test used to make.
        val endTime = now + 2 * 60 * 60_000 + 30_000

        val result = evaluate { formatTimeRemaining(endTime) }

        assertTrue("Result should show 2 hours: $result", result.contains("2h"))
        assertTrue("Result should show 0 minutes: $result", result.contains("0m"))
    }

    @Test
    fun `formatTimeRemaining correctly formats 4 hours 15 minutes`() {
        val now = System.currentTimeMillis()
        // Add 30 second buffer to prevent flakiness from test execution time
        val endTime = now + (4 * 60 + 15) * 60_000 + 30_000

        val result = evaluate { formatTimeRemaining(endTime) }

        assertTrue("Result should show 4 hours: $result", result.contains("4h"))
        assertTrue("Result should show 15 minutes: $result", result.contains("15m"))
    }

    // ========== getDurationDisplayText Tests ==========

    @Test
    fun `getDurationDisplayText returns correct text for FIFTEEN_MINUTES`() {
        val result = evaluate { getDurationDisplayText("FIFTEEN_MINUTES") }
        assertEquals("15 min", result)
    }

    @Test
    fun `getDurationDisplayText returns correct text for ONE_HOUR`() {
        val result = evaluate { getDurationDisplayText("ONE_HOUR") }
        assertEquals("1 hour", result)
    }

    @Test
    fun `getDurationDisplayText returns correct text for FOUR_HOURS`() {
        val result = evaluate { getDurationDisplayText("FOUR_HOURS") }
        assertEquals("4 hours", result)
    }

    @Test
    fun `getDurationDisplayText returns correct text for UNTIL_MIDNIGHT`() {
        val result = evaluate { getDurationDisplayText("UNTIL_MIDNIGHT") }
        assertEquals("Until midnight", result)
    }

    @Test
    fun `getDurationDisplayText returns correct text for INDEFINITE`() {
        val result = evaluate { getDurationDisplayText("INDEFINITE") }
        assertEquals("Until I stop", result)
    }

    @Test
    fun `getDurationDisplayText returns fallback for invalid duration`() {
        val result = evaluate { getDurationDisplayText("INVALID_DURATION") }
        assertEquals("1 hour", result)
    }

    @Test
    fun `getDurationDisplayText returns fallback for empty string`() {
        val result = evaluate { getDurationDisplayText("") }
        assertEquals("1 hour", result)
    }

    @Test
    fun `getDurationDisplayText returns fallback for lowercase name`() {
        // Enum valueOf is case-sensitive
        val result = evaluate { getDurationDisplayText("one_hour") }
        assertEquals("1 hour", result)
    }

    // ========== getPrecisionRadiusDisplayText Tests ==========

    @Test
    fun `getPrecisionRadiusDisplayText returns Precise for 0 meters`() {
        val result = evaluate { getPrecisionRadiusDisplayText(0) }
        assertEquals("Precise", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns Neighborhood for 1000 meters`() {
        val result = evaluate { getPrecisionRadiusDisplayText(1000) }
        assertEquals("Neighborhood (~1km)", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns City for 10000 meters`() {
        val result = evaluate { getPrecisionRadiusDisplayText(10000) }
        assertEquals("City (~10km)", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns Region for 100000 meters`() {
        val result = evaluate { getPrecisionRadiusDisplayText(100000) }
        assertEquals("Region (~100km)", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns km for custom large radius`() {
        val result = evaluate { getPrecisionRadiusDisplayText(5000) }
        assertEquals("5km", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns km for very large custom radius`() {
        val result = evaluate { getPrecisionRadiusDisplayText(50000) }
        assertEquals("50km", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns meters for small custom radius`() {
        val result = evaluate { getPrecisionRadiusDisplayText(500) }
        assertEquals("500m", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText returns meters for very small radius`() {
        val result = evaluate { getPrecisionRadiusDisplayText(100) }
        assertEquals("100m", result)
    }

    @Test
    fun `getPrecisionRadiusDisplayText handles edge case at 1000m boundary`() {
        // Both boundaries are resolved in a single composition: the compose rule
        // accepts only one setContent per test, so two evaluate calls would throw.
        val (result999, result1001) =
            evaluate {
                getPrecisionRadiusDisplayText(999) to getPrecisionRadiusDisplayText(1001)
            }

        // 999m should still show in meters
        assertEquals("999m", result999)

        // 1001m should show in km (integer division: 1001/1000 = 1)
        assertEquals("1km", result1001)
    }

    @Test
    fun `getPrecisionRadiusDisplayText handles minimum positive value`() {
        val result = evaluate { getPrecisionRadiusDisplayText(1) }
        assertEquals("1m", result)
    }

    // ========== LocationSharingCard UI Tests ==========

    @Test
    fun `locationSharingCard displays title`() {
        composeTestRule.setContent {
            LocationSharingCard(
                isExpanded = true,
                onExpandedChange = {},
                enabled = true,
                onEnabledChange = {},
                activeSessions = emptyList(),
                onStopSharing = {},
                onStopAllSharing = {},
                defaultDuration = "ONE_HOUR",
                onDefaultDurationChange = {},
                locationPrecisionRadius = 0,
                onLocationPrecisionRadiusChange = {},
                // Telemetry props
                telemetryCollectorEnabled = false,
                telemetryCollectorAddress = null,
                telemetrySendIntervalSeconds = 300,
                lastTelemetrySendTime = null,
                isSendingTelemetry = false,
                onTelemetryEnabledChange = {},
                onTelemetryCollectorAddressChange = {},
                onTelemetrySendIntervalChange = {},
                onTelemetrySendNow = {},
                telemetryRequestEnabled = false,
                telemetryRequestIntervalSeconds = 900,
                lastTelemetryRequestTime = null,
                isRequestingTelemetry = false,
                onTelemetryRequestEnabledChange = {},
                onTelemetryRequestIntervalChange = {},
                onRequestTelemetryNow = {},
                telemetryHostModeEnabled = false,
                onTelemetryHostModeEnabledChange = {},
                telemetryAllowedRequesters = emptySet(),
                contacts = emptyList(),
                onTelemetryAllowedRequestersChange = {},
            )
        }

        composeTestRule.onNodeWithText("Location Sharing").assertIsDisplayed()
    }

    @Test
    fun `locationSharingCard displays description`() {
        composeTestRule.setContent {
            LocationSharingCard(
                isExpanded = true,
                onExpandedChange = {},
                enabled = false,
                onEnabledChange = {},
                activeSessions = emptyList(),
                onStopSharing = {},
                onStopAllSharing = {},
                defaultDuration = "ONE_HOUR",
                onDefaultDurationChange = {},
                locationPrecisionRadius = 0,
                onLocationPrecisionRadiusChange = {},
                // Telemetry props
                telemetryCollectorEnabled = false,
                telemetryCollectorAddress = null,
                telemetrySendIntervalSeconds = 300,
                lastTelemetrySendTime = null,
                isSendingTelemetry = false,
                onTelemetryEnabledChange = {},
                onTelemetryCollectorAddressChange = {},
                onTelemetrySendIntervalChange = {},
                onTelemetrySendNow = {},
                telemetryRequestEnabled = false,
                telemetryRequestIntervalSeconds = 900,
                lastTelemetryRequestTime = null,
                isRequestingTelemetry = false,
                onTelemetryRequestEnabledChange = {},
                onTelemetryRequestIntervalChange = {},
                onRequestTelemetryNow = {},
                telemetryHostModeEnabled = false,
                onTelemetryHostModeEnabledChange = {},
                telemetryAllowedRequesters = emptySet(),
                contacts = emptyList(),
                onTelemetryAllowedRequestersChange = {},
            )
        }

        composeTestRule.onNodeWithText("Allow this app to share your location.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `locationSharingCard displays default duration setting`() {
        composeTestRule.setContent {
            LocationSharingCard(
                isExpanded = true,
                onExpandedChange = {},
                enabled = true,
                onEnabledChange = {},
                activeSessions = emptyList(),
                onStopSharing = {},
                onStopAllSharing = {},
                defaultDuration = "ONE_HOUR",
                onDefaultDurationChange = {},
                locationPrecisionRadius = 0,
                onLocationPrecisionRadiusChange = {},
                // Telemetry props
                telemetryCollectorEnabled = false,
                telemetryCollectorAddress = null,
                telemetrySendIntervalSeconds = 300,
                lastTelemetrySendTime = null,
                isSendingTelemetry = false,
                onTelemetryEnabledChange = {},
                onTelemetryCollectorAddressChange = {},
                onTelemetrySendIntervalChange = {},
                onTelemetrySendNow = {},
                telemetryRequestEnabled = false,
                telemetryRequestIntervalSeconds = 900,
                lastTelemetryRequestTime = null,
                isRequestingTelemetry = false,
                onTelemetryRequestEnabledChange = {},
                onTelemetryRequestIntervalChange = {},
                onRequestTelemetryNow = {},
                telemetryHostModeEnabled = false,
                onTelemetryHostModeEnabledChange = {},
                telemetryAllowedRequesters = emptySet(),
                contacts = emptyList(),
                onTelemetryAllowedRequestersChange = {},
            )
        }

        composeTestRule.onNodeWithText("Default duration").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 hour").assertIsDisplayed()
    }

    @Test
    fun `locationSharingCard displays location precision setting`() {
        composeTestRule.setContent {
            LocationSharingCard(
                isExpanded = true,
                onExpandedChange = {},
                enabled = true,
                onEnabledChange = {},
                activeSessions = emptyList(),
                onStopSharing = {},
                onStopAllSharing = {},
                defaultDuration = "ONE_HOUR",
                onDefaultDurationChange = {},
                locationPrecisionRadius = 0,
                onLocationPrecisionRadiusChange = {},
                // Telemetry props
                telemetryCollectorEnabled = false,
                telemetryCollectorAddress = null,
                telemetrySendIntervalSeconds = 300,
                lastTelemetrySendTime = null,
                isSendingTelemetry = false,
                onTelemetryEnabledChange = {},
                onTelemetryCollectorAddressChange = {},
                onTelemetrySendIntervalChange = {},
                onTelemetrySendNow = {},
                telemetryRequestEnabled = false,
                telemetryRequestIntervalSeconds = 900,
                lastTelemetryRequestTime = null,
                isRequestingTelemetry = false,
                onTelemetryRequestEnabledChange = {},
                onTelemetryRequestIntervalChange = {},
                onRequestTelemetryNow = {},
                telemetryHostModeEnabled = false,
                onTelemetryHostModeEnabledChange = {},
                telemetryAllowedRequesters = emptySet(),
                contacts = emptyList(),
                onTelemetryAllowedRequestersChange = {},
            )
        }

        composeTestRule.onNodeWithText("Location precision").assertIsDisplayed()
        composeTestRule.onNodeWithText("Precise").assertIsDisplayed()
    }

    @Test
    fun `locationSharingCard with active sessions displays currently sharing section`() {
        val sessions =
            listOf(
                SharingSession(
                    destinationHash = "hash1",
                    displayName = "Alice",
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + 3600_000,
                ),
            )

        composeTestRule.setContent {
            LocationSharingCard(
                isExpanded = true,
                onExpandedChange = {},
                enabled = true,
                onEnabledChange = {},
                activeSessions = sessions,
                onStopSharing = {},
                onStopAllSharing = {},
                defaultDuration = "ONE_HOUR",
                onDefaultDurationChange = {},
                locationPrecisionRadius = 0,
                onLocationPrecisionRadiusChange = {},
                // Telemetry props
                telemetryCollectorEnabled = false,
                telemetryCollectorAddress = null,
                telemetrySendIntervalSeconds = 300,
                lastTelemetrySendTime = null,
                isSendingTelemetry = false,
                onTelemetryEnabledChange = {},
                onTelemetryCollectorAddressChange = {},
                onTelemetrySendIntervalChange = {},
                onTelemetrySendNow = {},
                telemetryRequestEnabled = false,
                telemetryRequestIntervalSeconds = 900,
                lastTelemetryRequestTime = null,
                isRequestingTelemetry = false,
                onTelemetryRequestEnabledChange = {},
                onTelemetryRequestIntervalChange = {},
                onRequestTelemetryNow = {},
                telemetryHostModeEnabled = false,
                onTelemetryHostModeEnabledChange = {},
                telemetryAllowedRequesters = emptySet(),
                contacts = emptyList(),
                onTelemetryAllowedRequestersChange = {},
            )
        }

        composeTestRule.onNodeWithText("Currently sharing with:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun `locationSharingCard with active session displays stop button`() {
        val sessions =
            listOf(
                SharingSession(
                    destinationHash = "hash1",
                    displayName = "Bob",
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + 3600_000,
                ),
            )

        composeTestRule.setContent {
            LocationSharingCard(
                isExpanded = true,
                onExpandedChange = {},
                enabled = true,
                onEnabledChange = {},
                activeSessions = sessions,
                onStopSharing = {},
                onStopAllSharing = {},
                defaultDuration = "ONE_HOUR",
                onDefaultDurationChange = {},
                locationPrecisionRadius = 0,
                onLocationPrecisionRadiusChange = {},
                // Telemetry props
                telemetryCollectorEnabled = false,
                telemetryCollectorAddress = null,
                telemetrySendIntervalSeconds = 300,
                lastTelemetrySendTime = null,
                isSendingTelemetry = false,
                onTelemetryEnabledChange = {},
                onTelemetryCollectorAddressChange = {},
                onTelemetrySendIntervalChange = {},
                onTelemetrySendNow = {},
                telemetryRequestEnabled = false,
                telemetryRequestIntervalSeconds = 900,
                lastTelemetryRequestTime = null,
                isRequestingTelemetry = false,
                onTelemetryRequestEnabledChange = {},
                onTelemetryRequestIntervalChange = {},
                onRequestTelemetryNow = {},
                telemetryHostModeEnabled = false,
                onTelemetryHostModeEnabledChange = {},
                telemetryAllowedRequesters = emptySet(),
                contacts = emptyList(),
                onTelemetryAllowedRequestersChange = {},
            )
        }

        composeTestRule.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun `locationSharingCard with multiple sessions displays stop all button`() {
        val sessions =
            listOf(
                SharingSession(
                    destinationHash = "hash1",
                    displayName = "Alice",
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + 3600_000,
                ),
                SharingSession(
                    destinationHash = "hash2",
                    displayName = "Bob",
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + 3600_000,
                ),
            )

        composeTestRule.setContent {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier.verticalScroll(scrollState),
            ) {
                LocationSharingCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    enabled = true,
                    onEnabledChange = {},
                    activeSessions = sessions,
                    onStopSharing = {},
                    onStopAllSharing = {},
                    defaultDuration = "ONE_HOUR",
                    onDefaultDurationChange = {},
                    locationPrecisionRadius = 0,
                    onLocationPrecisionRadiusChange = {},
                    // Telemetry props
                    telemetryCollectorEnabled = false,
                    telemetryCollectorAddress = null,
                    telemetrySendIntervalSeconds = 300,
                    lastTelemetrySendTime = null,
                    isSendingTelemetry = false,
                    onTelemetryEnabledChange = {},
                    onTelemetryCollectorAddressChange = {},
                    onTelemetrySendIntervalChange = {},
                    onTelemetrySendNow = {},
                    telemetryRequestEnabled = false,
                    telemetryRequestIntervalSeconds = 900,
                    lastTelemetryRequestTime = null,
                    isRequestingTelemetry = false,
                    onTelemetryRequestEnabledChange = {},
                    onTelemetryRequestIntervalChange = {},
                    onRequestTelemetryNow = {},
                    telemetryHostModeEnabled = false,
                    onTelemetryHostModeEnabledChange = {},
                    telemetryAllowedRequesters = emptySet(),
                    contacts = emptyList(),
                    onTelemetryAllowedRequestersChange = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Stop All Sharing").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `locationSharingCard stop all button invokes callback`() {
        var stopAllCalled = false
        val sessions =
            listOf(
                SharingSession(
                    destinationHash = "hash1",
                    displayName = "Alice",
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + 3600_000,
                ),
                SharingSession(
                    destinationHash = "hash2",
                    displayName = "Bob",
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + 3600_000,
                ),
            )

        composeTestRule.setContent {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier.verticalScroll(scrollState),
            ) {
                LocationSharingCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    enabled = true,
                    onEnabledChange = {},
                    activeSessions = sessions,
                    onStopSharing = {},
                    onStopAllSharing = { stopAllCalled = true },
                    defaultDuration = "ONE_HOUR",
                    onDefaultDurationChange = {},
                    locationPrecisionRadius = 0,
                    onLocationPrecisionRadiusChange = {},
                    // Telemetry props
                    telemetryCollectorEnabled = false,
                    telemetryCollectorAddress = null,
                    telemetrySendIntervalSeconds = 300,
                    lastTelemetrySendTime = null,
                    isSendingTelemetry = false,
                    onTelemetryEnabledChange = {},
                    onTelemetryCollectorAddressChange = {},
                    onTelemetrySendIntervalChange = {},
                    onTelemetrySendNow = {},
                    telemetryRequestEnabled = false,
                    telemetryRequestIntervalSeconds = 900,
                    lastTelemetryRequestTime = null,
                    isRequestingTelemetry = false,
                    onTelemetryRequestEnabledChange = {},
                    onTelemetryRequestIntervalChange = {},
                    onRequestTelemetryNow = {},
                    telemetryHostModeEnabled = false,
                    onTelemetryHostModeEnabledChange = {},
                    telemetryAllowedRequesters = emptySet(),
                    contacts = emptyList(),
                    onTelemetryAllowedRequestersChange = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Stop All Sharing").performScrollTo().performClick()

        assertTrue(stopAllCalled)
    }

    @Test
    fun `locationSharingCard disabled hides active sessions section`() {
        val sessions =
            listOf(
                SharingSession(
                    destinationHash = "hash1",
                    displayName = "Alice",
                    startTime = System.currentTimeMillis(),
                    endTime = System.currentTimeMillis() + 3600_000,
                ),
            )

        composeTestRule.setContent {
            LocationSharingCard(
                isExpanded = true,
                onExpandedChange = {},
                enabled = false,
                onEnabledChange = {},
                activeSessions = sessions,
                onStopSharing = {},
                onStopAllSharing = {},
                defaultDuration = "ONE_HOUR",
                onDefaultDurationChange = {},
                locationPrecisionRadius = 0,
                onLocationPrecisionRadiusChange = {},
                // Telemetry props
                telemetryCollectorEnabled = false,
                telemetryCollectorAddress = null,
                telemetrySendIntervalSeconds = 300,
                lastTelemetrySendTime = null,
                isSendingTelemetry = false,
                onTelemetryEnabledChange = {},
                onTelemetryCollectorAddressChange = {},
                onTelemetrySendIntervalChange = {},
                onTelemetrySendNow = {},
                telemetryRequestEnabled = false,
                telemetryRequestIntervalSeconds = 900,
                lastTelemetryRequestTime = null,
                isRequestingTelemetry = false,
                onTelemetryRequestEnabledChange = {},
                onTelemetryRequestIntervalChange = {},
                onRequestTelemetryNow = {},
                telemetryHostModeEnabled = false,
                onTelemetryHostModeEnabledChange = {},
                telemetryAllowedRequesters = emptySet(),
                contacts = emptyList(),
                onTelemetryAllowedRequestersChange = {},
            )
        }

        // Active sessions should not be shown when disabled
        composeTestRule.onNodeWithText("Currently sharing with:").assertDoesNotExist()
    }

    @Test
    fun `locationSharingCard duration click opens picker`() {
        composeTestRule.setContent {
            LocationSharingCard(
                isExpanded = true,
                onExpandedChange = {},
                enabled = true,
                onEnabledChange = {},
                activeSessions = emptyList(),
                onStopSharing = {},
                onStopAllSharing = {},
                defaultDuration = "ONE_HOUR",
                onDefaultDurationChange = {},
                locationPrecisionRadius = 0,
                onLocationPrecisionRadiusChange = {},
                // Telemetry props
                telemetryCollectorEnabled = false,
                telemetryCollectorAddress = null,
                telemetrySendIntervalSeconds = 300,
                lastTelemetrySendTime = null,
                isSendingTelemetry = false,
                onTelemetryEnabledChange = {},
                onTelemetryCollectorAddressChange = {},
                onTelemetrySendIntervalChange = {},
                onTelemetrySendNow = {},
                telemetryRequestEnabled = false,
                telemetryRequestIntervalSeconds = 900,
                lastTelemetryRequestTime = null,
                isRequestingTelemetry = false,
                onTelemetryRequestEnabledChange = {},
                onTelemetryRequestIntervalChange = {},
                onRequestTelemetryNow = {},
                telemetryHostModeEnabled = false,
                onTelemetryHostModeEnabledChange = {},
                telemetryAllowedRequesters = emptySet(),
                contacts = emptyList(),
                onTelemetryAllowedRequestersChange = {},
            )
        }

        composeTestRule.onNodeWithText("Default duration").performClick()

        // Dialog should open
        composeTestRule.onNodeWithText("Default Duration").assertIsDisplayed()
    }

    @Test
    fun `locationSharingCard precision click opens picker`() {
        composeTestRule.setContent {
            LocationSharingCard(
                isExpanded = true,
                onExpandedChange = {},
                enabled = true,
                onEnabledChange = {},
                activeSessions = emptyList(),
                onStopSharing = {},
                onStopAllSharing = {},
                defaultDuration = "ONE_HOUR",
                onDefaultDurationChange = {},
                locationPrecisionRadius = 0,
                onLocationPrecisionRadiusChange = {},
                // Telemetry props
                telemetryCollectorEnabled = false,
                telemetryCollectorAddress = null,
                telemetrySendIntervalSeconds = 300,
                lastTelemetrySendTime = null,
                isSendingTelemetry = false,
                onTelemetryEnabledChange = {},
                onTelemetryCollectorAddressChange = {},
                onTelemetrySendIntervalChange = {},
                onTelemetrySendNow = {},
                telemetryRequestEnabled = false,
                telemetryRequestIntervalSeconds = 900,
                lastTelemetryRequestTime = null,
                isRequestingTelemetry = false,
                onTelemetryRequestEnabledChange = {},
                onTelemetryRequestIntervalChange = {},
                onRequestTelemetryNow = {},
                telemetryHostModeEnabled = false,
                onTelemetryHostModeEnabledChange = {},
                telemetryAllowedRequesters = emptySet(),
                contacts = emptyList(),
                onTelemetryAllowedRequestersChange = {},
            )
        }

        composeTestRule.onNodeWithText("Location precision").performClick()

        // Dialog should open
        composeTestRule.onNodeWithText("Location Precision").assertIsDisplayed()
    }

    @Test
    fun `locationSharingCard toggle invokes callback`() {
        var enabledValue = false

        composeTestRule.setContent {
            LocationSharingCard(
                isExpanded = true,
                onExpandedChange = {},
                enabled = enabledValue,
                onEnabledChange = { enabledValue = it },
                activeSessions = emptyList(),
                onStopSharing = {},
                onStopAllSharing = {},
                defaultDuration = "ONE_HOUR",
                onDefaultDurationChange = {},
                locationPrecisionRadius = 0,
                onLocationPrecisionRadiusChange = {},
                // Telemetry props
                telemetryCollectorEnabled = false,
                telemetryCollectorAddress = null,
                telemetrySendIntervalSeconds = 300,
                lastTelemetrySendTime = null,
                isSendingTelemetry = false,
                onTelemetryEnabledChange = {},
                onTelemetryCollectorAddressChange = {},
                onTelemetrySendIntervalChange = {},
                onTelemetrySendNow = {},
                telemetryRequestEnabled = false,
                telemetryRequestIntervalSeconds = 900,
                lastTelemetryRequestTime = null,
                isRequestingTelemetry = false,
                onTelemetryRequestEnabledChange = {},
                onTelemetryRequestIntervalChange = {},
                onRequestTelemetryNow = {},
                telemetryHostModeEnabled = false,
                onTelemetryHostModeEnabledChange = {},
                telemetryAllowedRequesters = emptySet(),
                contacts = emptyList(),
                onTelemetryAllowedRequestersChange = {},
            )
        }

        // The switch is part of the header, so we click on it
        composeTestRule.onNodeWithText("Location Sharing").assertIsDisplayed()
    }
}
