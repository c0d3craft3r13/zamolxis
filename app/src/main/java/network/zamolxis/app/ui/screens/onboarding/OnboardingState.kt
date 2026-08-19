package network.zamolxis.app.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import network.zamolxis.app.R

/**
 * State for the paged onboarding flow.
 */
@Immutable
data class OnboardingState(
    val currentPage: Int = 0,
    val displayName: String = "",
    val selectedInterfaces: Set<OnboardingInterfaceType> = setOf(OnboardingInterfaceType.AUTO),
    val notificationsEnabled: Boolean = false,
    val notificationsGranted: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val crashReportingEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = true,
    val hasCompletedOnboarding: Boolean = false,
    val error: String? = null,
    val blePermissionsGranted: Boolean = false,
    val blePermissionsDenied: Boolean = false,
)

/**
 * Interface types that can be enabled during onboarding.
 * Simplified version of the full InterfaceConfig for user selection.
 */
enum class OnboardingInterfaceType(
    @param:StringRes val displayName: Int,
    @param:StringRes val description: Int,
    @param:StringRes val secondaryDescription: Int? = null,
) {
    AUTO(
        displayName = R.string.onboarding_interface_wifi_name,
        description = R.string.onboarding_interface_wifi_description,
        secondaryDescription = R.string.onboarding_interface_wifi_secondary,
    ),
    BLE(
        displayName = R.string.onboarding_interface_ble_name,
        description = R.string.onboarding_interface_ble_description,
        secondaryDescription = R.string.onboarding_interface_ble_secondary,
    ),
    TCP(
        displayName = R.string.onboarding_interface_tcp_name,
        description = R.string.onboarding_interface_tcp_description,
        secondaryDescription = R.string.onboarding_interface_tcp_secondary,
    ),
    RNODE(
        displayName = R.string.onboarding_interface_rnode_name,
        description = R.string.onboarding_interface_rnode_description,
        secondaryDescription = R.string.onboarding_interface_rnode_secondary,
    ),
}

/**
 * Ordered onboarding pages. [CRASH_REPORTING] is only present in the `sentry` flavor
 * (gated by BuildConfig.CRASH_REPORTING_AVAILABLE when the page list is built).
 */
enum class OnboardingPage {
    WELCOME,
    IDENTITY,
    CONNECTIVITY,
    PERMISSIONS,
    CRASH_REPORTING,
    COMPLETE,
}

/**
 * Build the ordered list of onboarding pages for the current build flavor. The crash
 * reporting opt-in page is omitted entirely when crash reporting is not available.
 */
fun onboardingPages(crashReportingAvailable: Boolean): List<OnboardingPage> =
    OnboardingPage.entries.filter {
        it != OnboardingPage.CRASH_REPORTING || crashReportingAvailable
    }
