package network.zamolxis.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import network.zamolxis.app.R
import network.zamolxis.app.rns.host.ble.model.BlePowerPreset
import network.zamolxis.app.util.validation.ValidationConstants
import network.zamolxis.app.viewmodel.InterfaceConfigState

/**
 * Dialog for adding or editing a Reticulum network interface configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceConfigDialog(
    configState: InterfaceConfigState,
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isEditing) {
                    stringResource(R.string.ifacecfg_edit)
                } else {
                    stringResource(R.string.ifacecfg_add)
                },
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Interface Name
                OutlinedTextField(
                    value = configState.name,
                    onValueChange = { newValue ->
                        // VALIDATION: Enforce interface name length limit
                        if (newValue.length <= ValidationConstants.MAX_INTERFACE_NAME_LENGTH) {
                            onConfigUpdate(configState.copy(name = newValue))
                        }
                    },
                    label = { Text(stringResource(R.string.ifacecfg_name)) },
                    placeholder = { Text(stringResource(R.string.ifacecfg_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = configState.nameError != null,
                    supportingText = {
                        val nameError = configState.nameError
                        if (nameError != null) {
                            Text(nameError)
                        } else {
                            Text("${configState.name.length}/${ValidationConstants.MAX_INTERFACE_NAME_LENGTH}")
                        }
                    },
                )

                // Interface Type Selector
                InterfaceTypeSelector(
                    selectedType = configState.type,
                    // Can't change type when editing
                    enabled = !isEditing,
                    onTypeChange = { onConfigUpdate(configState.copy(type = it)) },
                )

                // TCP Client Target Host (required field, shown by default)
                if (configState.type == "TCPClient") {
                    OutlinedTextField(
                        value = configState.targetHost,
                        onValueChange = { host ->
                            // Strip scheme prefixes and whitespace — only bare hostnames are valid
                            val cleaned =
                                host
                                    .trim()
                                    .removePrefix("http://")
                                    .removePrefix("https://")
                            onConfigUpdate(configState.copy(targetHost = cleaned))
                        },
                        label = { Text(stringResource(R.string.ifacecfg_target_host)) },
                        placeholder = { Text(stringResource(R.string.ifacecfg_target_host_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = configState.targetHostError != null,
                        supportingText = configState.targetHostError?.let { { Text(it) } },
                    )
                }

                // Enabled Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.ifacemgmt_enabled),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = configState.enabled,
                        onCheckedChange = { onConfigUpdate(configState.copy(enabled = it)) },
                    )
                }

                // Advanced Options (Expandable)
                var showAdvanced by remember { mutableStateOf(false) }

                Divider()

                OutlinedButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.ifacecfg_advanced))
                }

                AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Type-specific configuration
                        when (configState.type) {
                            "AutoInterface" -> AutoInterfaceFields(configState, onConfigUpdate)
                            "TCPClient" -> TCPClientFields(configState, onConfigUpdate)
                            "TCPServer" -> TCPServerFields(configState, onConfigUpdate)
                            "AndroidBLE" -> AndroidBLEFields(configState, onConfigUpdate, scrollState)
                            "RNode" -> RNodeFields(configState, onConfigUpdate)
                        }

                        Divider()

                        // Network restriction (Wi-Fi / Cellular / Any) — IP-only.
                        // Hidden for AndroidBLE (out-of-band) and for RNode unless connection
                        // mode is TCP. The filter ignores the field for non-IP interfaces, but
                        // showing the selector would be misleading.
                        if (configState.networkRestrictionApplies) {
                            NetworkRestrictionSelector(
                                selectedRestriction = configState.effectiveNetworkRestriction,
                                onRestrictionChange = {
                                    onConfigUpdate(configState.copy(networkRestriction = it))
                                },
                            )
                        }

                        // Interface Mode
                        InterfaceModeSelector(
                            selectedMode = configState.mode,
                            onModeChange = { onConfigUpdate(configState.copy(mode = it)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(
                    if (isEditing) {
                        stringResource(R.string.ifacecfg_update)
                    } else {
                        stringResource(R.string.common_add)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceTypeSelector(
    selectedType: String,
    enabled: Boolean,
    onTypeChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val types =
        listOf(
            "AutoInterface" to stringResource(R.string.ifacemgmt_type_auto),
            "TCPClient" to stringResource(R.string.ifacemgmt_type_tcp_client),
            "TCPServer" to stringResource(R.string.ifacemgmt_type_tcp_server),
            "AndroidBLE" to stringResource(R.string.ifacemgmt_type_ble),
        )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = types.find { it.first == selectedType }?.second ?: stringResource(R.string.common_unknown),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.ifacecfg_type_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            types.forEach { (type, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onTypeChange(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun AutoInterfaceFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    Text(
        stringResource(R.string.ifacecfg_auto_config),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = configState.groupId,
        onValueChange = { onConfigUpdate(configState.copy(groupId = it)) },
        label = { Text(stringResource(R.string.ifacecfg_group_id)) },
        placeholder = { Text(stringResource(R.string.ifacecfg_group_id_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )

    DiscoveryScopeSelector(
        selectedScope = configState.discoveryScope,
        onScopeChange = { onConfigUpdate(configState.copy(discoveryScope = it)) },
    )

    OutlinedTextField(
        value = configState.discoveryPort,
        onValueChange = { onConfigUpdate(configState.copy(discoveryPort = it)) },
        label = { Text(stringResource(R.string.ifacecfg_discovery_port)) },
        placeholder = { Text("29716 (default)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.discoveryPortError != null,
        supportingText = configState.discoveryPortError?.let { { Text(it) } },
    )

    OutlinedTextField(
        value = configState.dataPort,
        onValueChange = { onConfigUpdate(configState.copy(dataPort = it)) },
        label = { Text(stringResource(R.string.ifacecfg_data_port)) },
        placeholder = { Text("42671 (default)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.dataPortError != null,
        supportingText = configState.dataPortError?.let { { Text(it) } },
    )
}

@Composable
fun TCPClientFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    Text(
        stringResource(R.string.ifacecfg_tcp_config),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = configState.targetPort,
        onValueChange = { onConfigUpdate(configState.copy(targetPort = it)) },
        label = { Text(stringResource(R.string.ifacecfg_target_port)) },
        placeholder = { Text("4242") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.targetPortError != null,
        supportingText = configState.targetPortError?.let { { Text(it) } },
    )

    IfacConfigCard(
        networkName = configState.networkName,
        passphrase = configState.passphrase,
        passphraseVisible = configState.passphraseVisible,
        onNetworkNameChange = { onConfigUpdate(configState.copy(networkName = it)) },
        onPassphraseChange = { onConfigUpdate(configState.copy(passphrase = it)) },
        onPassphraseVisibilityToggle = {
            onConfigUpdate(configState.copy(passphraseVisible = !configState.passphraseVisible))
        },
    )

    Divider()

    // SOCKS5 Proxy Toggle
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.ifacecfg_socks5),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.ifacecfg_socks5_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = configState.socksProxyEnabled,
            onCheckedChange = { onConfigUpdate(configState.copy(socksProxyEnabled = it)) },
        )
    }

    AnimatedVisibility(visible = configState.socksProxyEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = configState.socksProxyHost,
                onValueChange = { host ->
                    onConfigUpdate(configState.copy(socksProxyHost = host.trim()))
                },
                label = { Text(stringResource(R.string.ifacecfg_proxy_host)) },
                placeholder = { Text("127.0.0.1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = configState.socksProxyHostError != null,
                supportingText = {
                    val error = configState.socksProxyHostError
                    if (error != null) {
                        Text(error)
                    } else {
                        Text(
                            stringResource(R.string.ifacecfg_proxy_host_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )

            OutlinedTextField(
                value = configState.socksProxyPort,
                onValueChange = { onConfigUpdate(configState.copy(socksProxyPort = it)) },
                label = { Text(stringResource(R.string.ifacecfg_proxy_port)) },
                placeholder = { Text("9050") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = configState.socksProxyPortError != null,
                supportingText = {
                    val error = configState.socksProxyPortError
                    if (error != null) {
                        Text(error)
                    } else {
                        Text(
                            stringResource(R.string.ifacecfg_orbot_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}

@Composable
fun RNodeFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    // RNode dialog has no per-interface settings beyond IFAC (radio config
    // lives in the full RNode wizard); the shared IFAC card already owns its
    // own header and explanation so we let it stand alone here.
    IfacConfigCard(
        networkName = configState.networkName,
        passphrase = configState.passphrase,
        passphraseVisible = configState.passphraseVisible,
        onNetworkNameChange = { onConfigUpdate(configState.copy(networkName = it)) },
        onPassphraseChange = { onConfigUpdate(configState.copy(passphrase = it)) },
        onPassphraseVisibilityToggle = {
            onConfigUpdate(configState.copy(passphraseVisible = !configState.passphraseVisible))
        },
        description = stringResource(R.string.ifacecfg_ifac_hint),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScopeSelector(
    selectedScope: String,
    onScopeChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val scopes =
        listOf(
            "link" to stringResource(R.string.ifacecfg_scope_link),
            "admin" to stringResource(R.string.ifacecfg_scope_admin),
            "site" to stringResource(R.string.ifacecfg_scope_site),
            "organisation" to stringResource(R.string.ifacecfg_scope_org),
            "global" to stringResource(R.string.ifacecfg_scope_global),
        )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = scopes.find { it.first == selectedScope }?.second ?: "Link",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.ifacecfg_scope_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            scopes.forEach { (scope, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onScopeChange(scope)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceModeSelector(
    selectedMode: String,
    onModeChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val modes =
        listOf(
            "full" to stringResource(R.string.ifacecfg_mode_full),
            "gateway" to stringResource(R.string.ifacecfg_mode_gateway),
            "access_point" to stringResource(R.string.ifacecfg_mode_ap),
            "roaming" to stringResource(R.string.ifacecfg_mode_roaming),
            "boundary" to stringResource(R.string.ifacecfg_mode_boundary),
        )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = modes.find { it.first == selectedMode }?.second ?: stringResource(R.string.ifacecfg_mode_roaming),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.ifacecfg_mode_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modes.forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeChange(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun AndroidBLEFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
    scrollState: ScrollState? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    Text(
        stringResource(R.string.ifacecfg_ble_config),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = configState.deviceName,
        onValueChange = { newValue ->
            // VALIDATION: Enforce device name length limit
            if (newValue.length <= ValidationConstants.MAX_DEVICE_NAME_LENGTH) {
                onConfigUpdate(configState.copy(deviceName = newValue))
            }
        },
        label = { Text(stringResource(R.string.ifacecfg_device_name)) },
        placeholder = { Text(stringResource(R.string.ifacecfg_device_name_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.deviceNameError != null,
        supportingText = {
            Column {
                configState.deviceNameError?.let { Text(it) }
                if (configState.deviceName.isNotEmpty()) {
                    Text(
                        "${configState.deviceName.length}/${ValidationConstants.MAX_DEVICE_NAME_LENGTH} characters",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.ifacecfg_device_name_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    OutlinedTextField(
        value = configState.maxConnections,
        onValueChange = { onConfigUpdate(configState.copy(maxConnections = it)) },
        label = { Text(stringResource(R.string.ifacecfg_max_connections)) },
        placeholder = { Text("7") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.maxConnectionsError != null,
        supportingText = {
            Column {
                configState.maxConnectionsError?.let { Text(it) }
                Text(
                    stringResource(R.string.ifacecfg_max_connections_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        stringResource(R.string.ifacecfg_power_profile),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    // Preset selector
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val presets = listOf("performance", "balanced", "battery_saver", "custom")
        val labels = listOf("Performance", "Balanced", "Battery Saver", "Custom")
        presets.forEachIndexed { index, preset ->
            SegmentedButton(
                selected = configState.blePowerPreset == preset,
                onClick = {
                    val newState =
                        if (preset != "custom") {
                            val s = BlePowerPreset.getSettings(BlePowerPreset.fromString(preset))
                            configState.copy(
                                blePowerPreset = preset,
                                bleDiscoveryIntervalMs = s.discoveryIntervalMs.toString(),
                                bleDiscoveryIntervalIdleMs = s.discoveryIntervalIdleMs.toString(),
                                bleScanDurationMs = s.scanDurationMs.toString(),
                                bleAdvertisingRefreshIntervalMs = s.advertisingRefreshIntervalMs.toString(),
                            )
                        } else {
                            configState.copy(blePowerPreset = preset)
                        }
                    onConfigUpdate(newState)
                    if (preset == "custom" && scrollState != null) {
                        coroutineScope.launch {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                icon = {},
                label = { Text(labels[index], maxLines = 1, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    // Helper text per preset
    Text(
        when (configState.blePowerPreset) {
            "performance" -> "Fastest device discovery, higher battery usage"
            "balanced" -> "Default discovery speed and battery usage"
            "battery_saver" -> "Reduced scanning to conserve battery"
            "custom" -> "Manually configure scan and advertising intervals"
            else -> ""
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Custom sliders (only enabled when preset is "custom")
    val isCustom = configState.blePowerPreset == "custom"

    Text(
        stringResource(
            R.string.ifacecfg_scan_active,
            configState.bleDiscoveryIntervalMs.toLongOrNull()?.let { "${it / 1000}s" } ?: "5s",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.ifacecfg_scan_active_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = (configState.bleDiscoveryIntervalMs.toFloatOrNull() ?: 5000f) / 1000f,
        onValueChange = { onConfigUpdate(configState.copy(bleDiscoveryIntervalMs = (it * 1000).toLong().toString())) },
        valueRange = 3f..30f,
        steps = 26,
        enabled = isCustom,
    )

    Text(
        stringResource(
            R.string.ifacecfg_scan_idle,
            configState.bleDiscoveryIntervalIdleMs.toLongOrNull()?.let { "${it / 1000}s" } ?: "30s",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.ifacecfg_scan_idle_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = (configState.bleDiscoveryIntervalIdleMs.toFloatOrNull() ?: 30000f) / 1000f,
        onValueChange = { onConfigUpdate(configState.copy(bleDiscoveryIntervalIdleMs = (it * 1000).toLong().toString())) },
        valueRange = 15f..300f,
        steps = 56,
        enabled = isCustom,
    )

    Text(
        stringResource(
            R.string.ifacecfg_scan_duration,
            configState.bleScanDurationMs.toLongOrNull()?.let { "${it / 1000}s" } ?: "10s",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.ifacecfg_scan_duration_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = (configState.bleScanDurationMs.toFloatOrNull() ?: 10000f) / 1000f,
        onValueChange = { onConfigUpdate(configState.copy(bleScanDurationMs = (it * 1000).toLong().toString())) },
        valueRange = 3f..15f,
        steps = 11,
        enabled = isCustom,
    )

    // Warn when scan duration is close to or exceeds the active scan interval (high duty-cycle)
    val scanDuration = configState.bleScanDurationMs.toLongOrNull() ?: 10000L
    val activeInterval = configState.bleDiscoveryIntervalMs.toLongOrNull() ?: 5000L
    if (isCustom && scanDuration >= activeInterval) {
        Text(
            stringResource(R.string.ifacecfg_scan_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Text(
        stringResource(
            R.string.ifacecfg_ad_refresh,
            configState.bleAdvertisingRefreshIntervalMs.toLongOrNull()?.let { "${it / 1000}s" } ?: "60s",
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.ifacecfg_ad_refresh_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = (configState.bleAdvertisingRefreshIntervalMs.toFloatOrNull() ?: 60000f) / 1000f,
        onValueChange = { onConfigUpdate(configState.copy(bleAdvertisingRefreshIntervalMs = (it * 1000).toLong().toString())) },
        valueRange = 30f..300f,
        steps = 26,
        enabled = isCustom,
    )
}

/**
 * Three-option segmented selector for the per-interface network restriction:
 * Any / Wi-Fi only / Cellular only. Used inside the Advanced Options section of
 * `InterfaceConfigDialog` and the wizard review steps for IP-based interface types.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkRestrictionSelector(
    selectedRestriction: String,
    onRestrictionChange: (String) -> Unit,
) {
    val netRestrictionCd = stringResource(R.string.ifacecfg_netrestriction_cd)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = netRestrictionCd },
    ) {
        Text(
            stringResource(R.string.ifacecfg_active_on_network),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        // Values match the on-disk JSON form (NetworkRestriction.value) so the round
        // trip through configStateToInterfaceConfig is a straight string lookup.
        val options =
            listOf(
                "any" to stringResource(R.string.ifacecfg_restriction_any),
                "wifi_only" to stringResource(R.string.ifacecfg_restriction_wifi),
                "cellular_only" to stringResource(R.string.ifacecfg_restriction_cellular),
            )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = selectedRestriction == value,
                    onClick = { onRestrictionChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {},
                    label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text(
            stringResource(R.string.ifacecfg_restriction_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun TCPServerFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    Text(
        stringResource(R.string.ifacecfg_tcpserver_config),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    Text(
        stringResource(R.string.ifacecfg_tcpserver_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = configState.listenIp,
        onValueChange = { ip ->
            val cleaned =
                ip
                    .trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
            onConfigUpdate(configState.copy(listenIp = cleaned))
        },
        label = { Text(stringResource(R.string.ifacecfg_listen_ip)) },
        placeholder = { Text("0.0.0.0") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.listenIpError != null,
        supportingText = {
            Column {
                configState.listenIpError?.let { Text(it) }
                Text(
                    stringResource(R.string.ifacecfg_listen_ip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    OutlinedTextField(
        value = configState.listenPort,
        onValueChange = { onConfigUpdate(configState.copy(listenPort = it)) },
        label = { Text(stringResource(R.string.ifacecfg_listen_port)) },
        placeholder = { Text("4242") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.listenPortError != null,
        supportingText = {
            Column {
                configState.listenPortError?.let { Text(it) }
                Text(
                    stringResource(R.string.ifacecfg_listen_port_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    IfacConfigCard(
        networkName = configState.networkName,
        passphrase = configState.passphrase,
        passphraseVisible = configState.passphraseVisible,
        onNetworkNameChange = { onConfigUpdate(configState.copy(networkName = it)) },
        onPassphraseChange = { onConfigUpdate(configState.copy(passphrase = it)) },
        onPassphraseVisibilityToggle = {
            onConfigUpdate(configState.copy(passphraseVisible = !configState.passphraseVisible))
        },
        description = stringResource(R.string.ifacecfg_ifac_inbound_hint),
    )
}
