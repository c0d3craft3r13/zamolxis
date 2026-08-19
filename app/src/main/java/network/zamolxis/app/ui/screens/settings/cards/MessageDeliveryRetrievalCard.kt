package network.zamolxis.app.ui.screens.settings.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import network.zamolxis.app.R
import network.zamolxis.app.service.RelayInfo
import network.zamolxis.app.ui.components.CollapsibleSettingsCard
import network.zamolxis.app.ui.util.rememberLifecycleTickerMillis
import network.zamolxis.app.util.DestinationHashValidator

/**
 * Settings card for message delivery and retrieval options.
 * Allows users to configure:
 * - Default delivery method (Direct/Propagated)
 * - Retry via relay on failure toggle
 * - Auto-select nearest relay vs. manual selection
 * - View current relay info
 * - Auto-retrieve from relay toggle
 * - Retrieval interval selection
 * - Manual sync button
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList") // Settings card requires many configuration options
@Composable
fun MessageDeliveryRetrievalCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    defaultMethod: String,
    tryPropagationOnFail: Boolean,
    currentRelayName: String?,
    currentRelayHops: Int?,
    currentRelayHash: String?,
    isAutoSelect: Boolean,
    availableRelays: List<RelayInfo>,
    onMethodChange: (String) -> Unit,
    onTryPropagationToggle: (Boolean) -> Unit,
    onAutoSelectToggle: (Boolean) -> Unit,
    onAddManualRelay: (destinationHash: String, nickname: String?) -> Unit,
    onSelectRelay: (destinationHash: String, displayName: String) -> Unit,
    // Retrieval settings
    autoRetrieveEnabled: Boolean,
    retrievalIntervalSeconds: Int,
    lastSyncTimestamp: Long?,
    isSyncing: Boolean,
    onAutoRetrieveToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onSyncNow: () -> Unit,
    onViewMoreRelays: () -> Unit = {},
    // Incoming message size limit
    incomingMessageSizeLimitKb: Int = 1024,
    onIncomingMessageSizeLimitChange: (Int) -> Unit = {},
    // Message sorting
    sortMessagesBySentTime: Boolean = false,
    onSortMessagesBySentTimeToggle: (Boolean) -> Unit = {},
) {
    var showMethodDropdown by remember { mutableStateOf(false) }
    var showCustomIntervalDialog by remember { mutableStateOf(false) }
    var showRelaySelectionDialog by remember { mutableStateOf(false) }
    var showCustomSizeLimitDialog by remember { mutableStateOf(false) }
    var customIntervalInput by remember { mutableStateOf("") }
    var customSizeLimitInput by remember { mutableStateOf("") }
    var manualHashInput by remember { mutableStateOf("") }
    var manualNicknameInput by remember { mutableStateOf("") }

    val presetIntervals = listOf(3600, 10800, 21600, 43200) // 1h, 3h, 6h, 12h

    CollapsibleSettingsCard(
        title = stringResource(R.string.msgdelivery_title),
        icon = Icons.Default.Send,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Description
        Text(
            text = stringResource(R.string.msgdelivery_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Default delivery method selector
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.msgdelivery_default_method),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Box {
                OutlinedButton(
                    onClick = { showMethodDropdown = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text =
                            when (defaultMethod) {
                                "direct" -> "Direct (Link-based)"
                                "propagated" -> "Propagated (Via Relay)"
                                else -> "Direct (Link-based)"
                            },
                    )
                }
                DropdownMenu(
                    expanded = showMethodDropdown,
                    onDismissRequest = { showMethodDropdown = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.msgdelivery_direct))
                                Text(
                                    text = stringResource(R.string.msgdelivery_direct_sub),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onMethodChange("direct")
                            showMethodDropdown = false
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.msgdelivery_propagated))
                                Text(
                                    text = stringResource(R.string.msgdelivery_propagated_sub),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onMethodChange("propagated")
                            showMethodDropdown = false
                        },
                    )
                }
            }
        }

        // Retry via propagation toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.msgdelivery_retry),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.msgdelivery_retry_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = tryPropagationOnFail,
                onCheckedChange = onTryPropagationToggle,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Relay selection section
        Text(
            text = stringResource(R.string.msgdelivery_my_relay),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        // Auto-select option
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onAutoSelectToggle(true) }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isAutoSelect,
                onClick = { onAutoSelectToggle(true) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.msgdelivery_auto_select),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isAutoSelect && currentRelayName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.msgdelivery_currently, currentRelayName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (currentRelayHops != null) {
                            Text(
                                text =
                                    pluralStringResource(
                                        R.plurals.msgdelivery_hops_paren,
                                        currentRelayHops,
                                        currentRelayHops,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Manual selection option
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onAutoSelectToggle(false) }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = !isAutoSelect,
                onClick = { onAutoSelectToggle(false) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.msgdelivery_specific),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!isAutoSelect && currentRelayName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = currentRelayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (currentRelayHops != null) {
                            Text(
                                text =
                                    pluralStringResource(
                                        R.plurals.msgdelivery_hops_paren,
                                        currentRelayHops,
                                        currentRelayHops,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (!isAutoSelect) {
                    Text(
                        text = stringResource(R.string.msgdelivery_no_relay),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Current relay display
        if (currentRelayName != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.msgdelivery_tap_select),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CurrentRelayInfo(
                relayName = currentRelayName,
                hops = currentRelayHops,
                isAutoSelected = isAutoSelect,
                onClick = { showRelaySelectionDialog = true },
            )
        } else if (isAutoSelect) {
            // Auto-select mode with no relay yet
            Text(
                text = stringResource(R.string.msgdelivery_no_relay_configured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Manual mode UI - show relay selection button and manual entry
        if (!isAutoSelect) {
            Spacer(modifier = Modifier.height(8.dp))

            // Show button to open relay selection dialog when no relay is selected
            if (currentRelayName == null) {
                OutlinedButton(
                    onClick = { showRelaySelectionDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.msgdelivery_select_available))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.msgdelivery_or_manual),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ManualRelayInput(
                hashInput = manualHashInput,
                onHashChange = { manualHashInput = it },
                nicknameInput = manualNicknameInput,
                onNicknameChange = { manualNicknameInput = it },
                onConfirm = { hash, nickname ->
                    onAddManualRelay(hash, nickname)
                    // Clear inputs after confirmation
                    manualHashInput = ""
                    manualNicknameInput = ""
                },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Message Retrieval Section
        Text(
            text = stringResource(R.string.msgdelivery_section_retrieval),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        // Auto-retrieve toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.msgdelivery_auto_retrieve),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.msgdelivery_auto_retrieve_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoRetrieveEnabled,
                onCheckedChange = onAutoRetrieveToggle,
            )
        }

        // Retrieval interval chips
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.msgdelivery_interval, formatIntervalDisplay(retrievalIntervalSeconds)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IntervalChip(
                    label = "1h",
                    selected = retrievalIntervalSeconds == 3600,
                    enabled = autoRetrieveEnabled,
                    onClick = { onIntervalChange(3600) },
                )
                IntervalChip(
                    label = "3h",
                    selected = retrievalIntervalSeconds == 10800,
                    enabled = autoRetrieveEnabled,
                    onClick = { onIntervalChange(10800) },
                )
                IntervalChip(
                    label = "6h",
                    selected = retrievalIntervalSeconds == 21600,
                    enabled = autoRetrieveEnabled,
                    onClick = { onIntervalChange(21600) },
                )
                IntervalChip(
                    label = "12h",
                    selected = retrievalIntervalSeconds == 43200,
                    enabled = autoRetrieveEnabled,
                    onClick = { onIntervalChange(43200) },
                )
                // Custom chip
                FilterChip(
                    selected = !presetIntervals.contains(retrievalIntervalSeconds),
                    onClick = {
                        customIntervalInput = retrievalIntervalSeconds.toString()
                        showCustomIntervalDialog = true
                    },
                    enabled = autoRetrieveEnabled,
                    label = {
                        Text(
                            if (presetIntervals.contains(retrievalIntervalSeconds)) {
                                stringResource(R.string.msgdelivery_custom)
                            } else {
                                stringResource(
                                    R.string.msgdelivery_custom_value,
                                    formatIntervalDisplay(retrievalIntervalSeconds),
                                )
                            },
                        )
                    },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                )
            }
        }

        // Sync Now button
        Button(
            onClick = onSyncNow,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSyncing && currentRelayName != null,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                ),
        ) {
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.msgdelivery_syncing))
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.msgdelivery_sync_now))
            }
        }

        // Last sync timestamp with periodic refresh
        if (lastSyncTimestamp != null) {
            val currentTime = rememberLifecycleTickerMillis(periodMs = 5_000L)
            Text(
                text =
                    stringResource(
                        R.string.msgdelivery_last_sync,
                        formatRelativeTime(lastSyncTimestamp, currentTime),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Incoming Message Size Limit Section
        Text(
            text = stringResource(R.string.msgdelivery_section_size),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Text(
            text = stringResource(R.string.msgdelivery_size_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Size limit chips
        val presetSizeLimitsKb = listOf(1024, 5120, 10240, 25600, 131072) // 1MB, 5MB, 10MB, 25MB, 128MB
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.msgdelivery_size_limit, formatSizeLimit(incomingMessageSizeLimitKb)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SizeLimitChip(
                    label = "1 MB",
                    selected = incomingMessageSizeLimitKb == 1024,
                    onClick = { onIncomingMessageSizeLimitChange(1024) },
                )
                SizeLimitChip(
                    label = "5 MB",
                    selected = incomingMessageSizeLimitKb == 5120,
                    onClick = { onIncomingMessageSizeLimitChange(5120) },
                )
                SizeLimitChip(
                    label = "10 MB",
                    selected = incomingMessageSizeLimitKb == 10240,
                    onClick = { onIncomingMessageSizeLimitChange(10240) },
                )
                SizeLimitChip(
                    label = "25 MB",
                    selected = incomingMessageSizeLimitKb == 25600,
                    onClick = { onIncomingMessageSizeLimitChange(25600) },
                )
                SizeLimitChip(
                    label = stringResource(R.string.msgdelivery_unlimited),
                    selected = incomingMessageSizeLimitKb == 131072,
                    onClick = { onIncomingMessageSizeLimitChange(131072) },
                )
                // Custom chip
                FilterChip(
                    selected = !presetSizeLimitsKb.contains(incomingMessageSizeLimitKb),
                    onClick = {
                        customSizeLimitInput = (incomingMessageSizeLimitKb / 1024).toString()
                        showCustomSizeLimitDialog = true
                    },
                    label = {
                        Text(
                            if (presetSizeLimitsKb.contains(incomingMessageSizeLimitKb)) {
                                stringResource(R.string.msgdelivery_custom)
                            } else {
                                stringResource(
                                    R.string.msgdelivery_custom_value,
                                    formatSizeLimit(incomingMessageSizeLimitKb),
                                )
                            },
                        )
                    },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Message Sort Order Section
        Text(
            text = stringResource(R.string.msgdelivery_section_sort),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.msgdelivery_sort_sender),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text =
                        if (sortMessagesBySentTime) {
                            stringResource(R.string.msgdelivery_sort_sender_sub)
                        } else {
                            stringResource(R.string.msgdelivery_sort_received_sub)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = sortMessagesBySentTime,
                onCheckedChange = onSortMessagesBySentTimeToggle,
            )
        }
    }

    // Custom interval dialog
    if (showCustomIntervalDialog) {
        CustomRetrievalIntervalDialog(
            customIntervalInput = customIntervalInput,
            onInputChange = { customIntervalInput = it },
            onConfirm = { value ->
                onIntervalChange(value)
                showCustomIntervalDialog = false
            },
            onDismiss = { showCustomIntervalDialog = false },
        )
    }

    // Relay selection dialog
    if (showRelaySelectionDialog) {
        RelaySelectionDialog(
            availableRelays = availableRelays,
            currentRelayHash = currentRelayHash,
            onSelectRelay = { hash, name ->
                onSelectRelay(hash, name)
                showRelaySelectionDialog = false
            },
            onViewMoreRelays = onViewMoreRelays,
            onDismiss = { showRelaySelectionDialog = false },
        )
    }

    // Custom size limit dialog
    if (showCustomSizeLimitDialog) {
        CustomSizeLimitDialog(
            customSizeLimitInput = customSizeLimitInput,
            onInputChange = { customSizeLimitInput = it },
            onConfirm = { valueMb ->
                onIncomingMessageSizeLimitChange(valueMb * 1024)
                showCustomSizeLimitDialog = false
            },
            onDismiss = { showCustomSizeLimitDialog = false },
        )
    }
}

@Composable
private fun IntervalChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
    )
}

@Composable
private fun CurrentRelayInfo(
    relayName: String,
    hops: Int?,
    isAutoSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Hub icon
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Hub,
                    contentDescription = stringResource(R.string.msgdelivery_relay_cd),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onTertiary,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = relayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isAutoSelected) {
                        Text(
                            text = stringResource(R.string.msgdelivery_auto),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (hops != null) {
                    Text(
                        text = pluralStringResource(R.plurals.msgdelivery_hops_away, hops, hops),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Format a timestamp as relative time (e.g., "2 minutes ago", "Just now").
 * @param timestamp The timestamp to format
 * @param now The current time (passed in to trigger recomposition on change)
 */
@Composable
private fun formatRelativeTime(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
): String {
    val diff = now - timestamp

    return when {
        diff < 5_000 -> stringResource(R.string.time_just_now)
        diff < 60_000 ->
            pluralStringResource(R.plurals.time_seconds_ago, (diff / 1000).toInt(), diff / 1000)
        diff < 3600_000 ->
            pluralStringResource(R.plurals.time_minutes_ago, (diff / 60_000).toInt(), diff / 60_000)
        diff < 86400_000 ->
            pluralStringResource(R.plurals.time_hours_ago, (diff / 3600_000).toInt(), diff / 3600_000)
        else ->
            pluralStringResource(R.plurals.time_days_ago, (diff / 86400_000).toInt(), diff / 86400_000)
    }
}

/**
 * Format interval in seconds to a readable string (e.g., "30s", "2min", "5min").
 */
private fun formatIntervalDisplay(seconds: Int): String =
    when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}min"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }

@Composable
private fun CustomRetrievalIntervalDialog(
    customIntervalInput: String,
    onInputChange: (String) -> Unit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msgdelivery_custom_interval_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.msgdelivery_custom_interval_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = customIntervalInput,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() } && it.length <= 5) {
                            onInputChange(it)
                        }
                    },
                    label = { Text(stringResource(R.string.msgdelivery_seconds)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = customIntervalInput.toIntOrNull()?.let { it < 3600 || it > 43200 } ?: false,
                    supportingText = {
                        val value = customIntervalInput.toIntOrNull()
                        when {
                            value == null && customIntervalInput.isNotEmpty() ->
                                Text(stringResource(R.string.msgdelivery_valid_number))
                            value != null && value < 3600 ->
                                Text(stringResource(R.string.msgdelivery_min_interval))
                            value != null && value > 43200 ->
                                Text(stringResource(R.string.msgdelivery_max_interval))
                            value != null ->
                                Text(
                                    stringResource(
                                        R.string.msgdelivery_interval_result,
                                        formatIntervalDisplay(value),
                                    ),
                                )
                            else -> {}
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = customIntervalInput.toIntOrNull()
                    if (value != null && value in 3600..43200) {
                        onConfirm(value)
                    }
                },
                enabled = customIntervalInput.toIntOrNull()?.let { it in 3600..43200 } ?: false,
            ) {
                Text(stringResource(R.string.autoannounce_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Input form for manually entering a propagation node destination hash.
 */
@Composable
private fun ManualRelayInput(
    hashInput: String,
    onHashChange: (String) -> Unit,
    nicknameInput: String,
    onNicknameChange: (String) -> Unit,
    onConfirm: (hash: String, nickname: String?) -> Unit,
) {
    val validationResult = DestinationHashValidator.validate(hashInput)
    val isValid = validationResult is DestinationHashValidator.ValidationResult.Valid
    val errorMessage = (validationResult as? DestinationHashValidator.ValidationResult.Error)?.message

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.msgdelivery_enter_hash),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = hashInput,
            onValueChange = { input ->
                // Only allow hex characters, up to 32 chars
                val filtered = input.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                if (filtered.length <= 32) {
                    onHashChange(filtered)
                }
            },
            label = { Text(stringResource(R.string.myidentity_dest_hash)) },
            placeholder = { Text(stringResource(R.string.msgdelivery_hash_placeholder)) },
            singleLine = true,
            isError = hashInput.isNotEmpty() && !isValid,
            supportingText = {
                if (hashInput.isEmpty()) {
                    Text(DestinationHashValidator.getCharacterCount(hashInput))
                } else if (!isValid && errorMessage != null) {
                    Text(errorMessage)
                } else {
                    Text(DestinationHashValidator.getCharacterCount(hashInput))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = nicknameInput,
            onValueChange = onNicknameChange,
            label = { Text(stringResource(R.string.msgdelivery_nickname)) },
            placeholder = { Text(stringResource(R.string.msgdelivery_nickname_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val normalizedHash =
                    (validationResult as DestinationHashValidator.ValidationResult.Valid).normalizedHash
                val nickname = nicknameInput.trim().takeIf { it.isNotEmpty() }
                onConfirm(normalizedHash, nickname)
            },
            enabled = isValid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.msgdelivery_set_relay))
        }
    }
}

/**
 * Dialog for selecting a relay from the list of available propagation nodes.
 */
@Composable
private fun RelaySelectionDialog(
    availableRelays: List<RelayInfo>,
    currentRelayHash: String?,
    onSelectRelay: (hash: String, name: String) -> Unit,
    onViewMoreRelays: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msgdelivery_select_relay_title)) },
        text = {
            // Skip loading state - query is fast enough. Just show relays or empty message.
            if (availableRelays.isEmpty()) {
                Text(
                    text = stringResource(R.string.msgdelivery_no_nodes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(availableRelays, key = { it.destinationHash }) { relay ->
                        RelayListItem(
                            relay = relay,
                            isSelected = relay.destinationHash == currentRelayHash,
                            onClick = { onSelectRelay(relay.destinationHash, relay.displayName) },
                        )
                    }
                    // "More..." item to view all relays in the announces screen
                    item(key = "more_relays") {
                        MoreRelaysItem(
                            onClick = {
                                onViewMoreRelays()
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * A single relay item in the selection list.
 */
@Composable
private fun RelayListItem(
    relay: RelayInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Hub icon
            Icon(
                imageVector = Icons.Default.Hub,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = relay.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    text = pluralStringResource(R.plurals.msgdelivery_hops_away, relay.hops, relay.hops),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isSelected) {
                Text(
                    text = stringResource(R.string.msgdelivery_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * A "More..." item at the end of the relay list to view all relays.
 */
@Composable
private fun MoreRelaysItem(onClick: () -> Unit) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.msgdelivery_view_all),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun SizeLimitChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

/**
 * Format size limit in KB to a readable string (e.g., "1 MB", "5.5 MB", "128 MB").
 */
private fun formatSizeLimit(limitKb: Int): String =
    when {
        limitKb >= 131072 -> "Unlimited (128 MB)"
        limitKb >= 1024 -> {
            val mb = limitKb / 1024.0
            if (mb == mb.toInt().toDouble()) {
                "${mb.toInt()} MB"
            } else {
                "%.1f MB".format(mb)
            }
        }
        else -> "$limitKb KB"
    }

@Composable
private fun CustomSizeLimitDialog(
    customSizeLimitInput: String,
    onInputChange: (String) -> Unit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msgdelivery_custom_size_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.msgdelivery_custom_size_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = customSizeLimitInput,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() } && it.length <= 3) {
                            onInputChange(it)
                        }
                    },
                    label = { Text(stringResource(R.string.msgdelivery_mb)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = customSizeLimitInput.toIntOrNull()?.let { it < 1 || it > 128 } ?: false,
                    supportingText = {
                        val value = customSizeLimitInput.toIntOrNull()
                        when {
                            value == null && customSizeLimitInput.isNotEmpty() ->
                                Text(stringResource(R.string.msgdelivery_valid_number))
                            value != null && value < 1 -> Text(stringResource(R.string.msgdelivery_min_size))
                            value != null && value > 128 -> Text(stringResource(R.string.msgdelivery_max_size))
                            value != null -> Text(stringResource(R.string.msgdelivery_size_result, value * 1024))
                            else -> {}
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = customSizeLimitInput.toIntOrNull()
                    if (value != null && value in 1..128) {
                        onConfirm(value)
                    }
                },
                enabled = customSizeLimitInput.toIntOrNull()?.let { it in 1..128 } ?: false,
            ) {
                Text(stringResource(R.string.autoannounce_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
