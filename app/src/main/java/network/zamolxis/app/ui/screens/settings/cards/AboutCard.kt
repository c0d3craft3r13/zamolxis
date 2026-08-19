package network.zamolxis.app.ui.screens.settings.cards

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import network.zamolxis.app.R
import network.zamolxis.app.service.AppUpdateResult
import network.zamolxis.app.ui.components.CollapsibleSettingsCard
import network.zamolxis.app.util.SystemInfo
import network.zamolxis.app.util.safeOpenUrl

@Composable
fun AboutCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    systemInfo: SystemInfo,
    onCopySystemInfo: () -> Unit,
    onReportBug: () -> Unit,
    updateCheckResult: AppUpdateResult = AppUpdateResult.Idle,
    includePrereleaseUpdates: Boolean = false,
    onCheckForUpdates: () -> Unit = {},
    onSetIncludePrereleaseUpdates: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

    CollapsibleSettingsCard(
        title = stringResource(R.string.about_title),
        icon = Icons.Default.Info,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Logo and Header
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.about_logo_cd),
                modifier = Modifier.size(108.dp),
            )

            Text(
                text = stringResource(R.string.about_app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            HorizontalDivider()

            // Version Information
            InfoSection(title = stringResource(R.string.about_app_info)) {
                InfoRow("Version", systemInfo.appVersion)
                InfoRow("Build Number", systemInfo.appBuildCode.toString())
                InfoRow("Build Type", systemInfo.buildType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() })
                InfoRow("Git Commit", systemInfo.gitCommitHash)
                InfoRow("Build Date", systemInfo.buildDate)
            }

            HorizontalDivider()

            // Device Information
            InfoSection(title = stringResource(R.string.about_device_info)) {
                InfoRow("Android Version", systemInfo.androidVersion)
                InfoRow("API Level", systemInfo.apiLevel.toString())
                InfoRow("Device Model", systemInfo.deviceModel)
                InfoRow("Manufacturer", systemInfo.manufacturer)
            }

            HorizontalDivider()

            // Protocol Versions
            InfoSection(title = stringResource(R.string.about_protocol_versions)) {
                if (systemInfo.reticulumVersion != null) {
                    InfoRow("Reticulum", systemInfo.reticulumVersion)
                }
                if (systemInfo.lxmfVersion != null) {
                    InfoRow("LXMF", systemInfo.lxmfVersion)
                }
                if (systemInfo.bleReticulumVersion != null) {
                    InfoRow("BLE-Reticulum", systemInfo.bleReticulumVersion)
                }
                if (systemInfo.lxstVersion != null) {
                    InfoRow("LXST", systemInfo.lxstVersion)
                }
            }

            HorizontalDivider()

            // Identity
            if (systemInfo.identityHash != null) {
                InfoSection(title = stringResource(R.string.identitycard_title)) {
                    InfoRow("Identity Hash", systemInfo.identityHash)
                }
                HorizontalDivider()
            }

            // Links
            InfoSection(title = stringResource(R.string.about_links)) {
                LinkButton("GitHub Repository", "https://github.com/c0d3craft3r13/zamolxis", context)
                LinkButton("Report an Issue", "https://github.com/c0d3craft3r13/zamolxis/issues", context)
                LinkButton("About Reticulum", "https://reticulum.network/", context)
            }

            HorizontalDivider()

            // Legal
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.about_copyright, network.zamolxis.app.BuildConfig.COPYRIGHT_YEAR),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Upstream attribution. MPL 2.0 requires the original notices to
                // survive redistribution, so this stays visible in the shipped app.
                Text(
                    text = stringResource(R.string.about_forked_from),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        openExternalUrl(context, "https://github.com/c0d3craft3r13/zamolxis/blob/main/LICENSE.md")
                    },
                ) {
                    Text(stringResource(R.string.about_view_license), style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()

            // Attribution
            Text(
                text = stringResource(R.string.about_built_with),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Reticulum by Mark Qvist", style = MaterialTheme.typography.bodySmall)
                Text("LXMF by Mark Qvist", style = MaterialTheme.typography.bodySmall)
                Text("Material Design 3", style = MaterialTheme.typography.bodySmall)
                Text("Jetpack Compose", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            // Updates
            InfoSection(title = stringResource(R.string.about_updates)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.about_prereleases),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = includePrereleaseUpdates,
                        onCheckedChange = onSetIncludePrereleaseUpdates,
                    )
                }

                val isChecking = updateCheckResult is AppUpdateResult.Checking
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    enabled = !isChecking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.about_check_updates))
                }

                when (val result = updateCheckResult) {
                    is AppUpdateResult.UpToDate ->
                        Text(
                            text = stringResource(R.string.about_up_to_date, result.currentVersion),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    is AppUpdateResult.UpdateAvailable ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.about_update_available, result.tagName),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = {
                                    openExternalUrl(context, result.htmlUrl)
                                },
                            ) {
                                Text(stringResource(R.string.about_view_release))
                            }
                        }
                    is AppUpdateResult.Error ->
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    else -> {}
                }
            }

            HorizontalDivider()

            // Copy Button
            OutlinedButton(
                onClick = onCopySystemInfo,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.about_copy_sysinfo))
            }

            // Report Bug Button
            OutlinedButton(
                onClick = onReportBug,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.about_report_bug))
            }
        }
    }
}

@Composable
private fun InfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    // The label keeps its natural width; the value takes the remaining space and
    // is right-aligned. Without the weight, a long value (e.g. the Python
    // flavor's "Reticulum 1.4.2 (torlando-tech fork)") and the label both claim
    // their full intrinsic width and crowd/overlap under SpaceBetween — instead
    // a long value now wraps within its column.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LinkButton(
    label: String,
    url: String,
    context: Context,
) {
    TextButton(
        onClick = {
            openExternalUrl(context, url)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}

private fun openExternalUrl(
    context: Context,
    url: String,
) {
    if (!safeOpenUrl(context, url)) {
        Toast.makeText(context, R.string.error_no_app_for_link, Toast.LENGTH_SHORT).show()
    }
}
