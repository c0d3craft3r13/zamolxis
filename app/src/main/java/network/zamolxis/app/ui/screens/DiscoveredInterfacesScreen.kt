@file:Suppress("TooManyFunctions")

package network.zamolxis.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import network.zamolxis.app.R
import network.zamolxis.app.rns.api.model.DiscoveredInterface
import network.zamolxis.app.ui.components.LocalCapabilities
import network.zamolxis.app.ui.components.ServiceRestartBanner
import network.zamolxis.app.ui.components.SortModeSelector
import network.zamolxis.app.ui.theme.MaterialDesignIcons
import network.zamolxis.app.util.LocationCompat
import network.zamolxis.app.viewmodel.DiscoveredInterfaceTypeFilter
import network.zamolxis.app.viewmodel.DiscoveredInterfacesViewModel

/**
 * Material Design Icons font family for custom icons like incognito.
 */
private val MdiFont = FontFamily(Font(R.font.materialdesignicons))

/**
 * Screen for displaying discovered interfaces from RNS 1.1.x discovery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveredInterfacesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTcpClientWizard: (host: String, port: Int, name: String, ifacNetname: String?, ifacNetkey: String?) -> Unit = { _, _, _, _, _ -> },
    onNavigateToMapWithInterface: (details: FocusInterfaceDetails) -> Unit = { _ -> },
    onNavigateToRNodeWizardWithParams: (
        frequency: Long?,
        bandwidth: Int?,
        spreadingFactor: Int?,
        codingRate: Int?,
    ) -> Unit = { _, _, _, _ -> },
    viewModel: DiscoveredInterfacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val tcpOnlyToast = stringResource(R.string.disciface_tcp_only)
    val loraCopiedToast = stringResource(R.string.disciface_lora_copied)
    val clipboardManager = LocalClipboardManager.current

    // Fetch user location for proximity sorting (if permission granted)
    LaunchedEffect(Unit) {
        val hasCoarsePermission =
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasFinePermission =
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasCoarsePermission || hasFinePermission) {
            if (LocationCompat.isPlayServicesAvailable(context)) {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                fusedClient
                    .getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        CancellationTokenSource().token,
                    ).addOnSuccessListener { location ->
                        location?.let {
                            viewModel.setUserLocation(it.latitude, it.longitude)
                        }
                    }
            } else {
                // Fallback to platform LocationManager (issue #456)
                LocationCompat.getCurrentLocation(context) { location ->
                    location?.let {
                        viewModel.setUserLocation(it.latitude, it.longitude)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.disciface_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadDiscoveredInterfaces() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Discovery settings card
                        item {
                            DiscoverySettingsCard(
                                isRuntimeEnabled = state.isDiscoveryEnabled,
                                isSettingEnabled = state.discoverInterfacesEnabled,
                                autoconnectCount = state.autoconnectCount,
                                autoconnectIfacOnly = state.autoconnectIfacOnly,
                                bootstrapInterfaceNames = state.bootstrapInterfaceNames,
                                isRestarting = state.isRestarting,
                                onToggleDiscovery = { viewModel.toggleDiscovery() },
                                onAutoconnectCountChange = { viewModel.setAutoconnectCount(it) },
                                onToggleAutoconnectIfacOnly = { viewModel.toggleAutoconnectIfacOnly() },
                            )
                        }

                        // Status summary (counts reflect ALL discovered interfaces, not filtered)
                        if (state.originalInterfaces.isNotEmpty()) {
                            item {
                                DiscoveryStatusSummary(
                                    totalCount = state.originalInterfaces.size,
                                    availableCount = state.availableCount,
                                    unknownCount = state.unknownCount,
                                    staleCount = state.staleCount,
                                )
                            }

                            // Sort mode selector
                            item {
                                SortModeSelector(
                                    currentMode = state.sortMode,
                                    hasUserLocation = state.userLatitude != null && state.userLongitude != null,
                                    onModeSelected = { viewModel.setSortMode(it) },
                                )
                            }

                            // Search + type filters
                            item {
                                DiscoveredInterfaceSearchAndFilter(
                                    searchQuery = state.searchQuery,
                                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                                    typeFilters = state.typeFilters,
                                    onToggleTypeFilter = { viewModel.toggleTypeFilter(it) },
                                    ifacOnly = state.ifacOnly,
                                    onToggleIfacOnly = { viewModel.toggleIfacOnlyFilter() },
                                    onClearFilters = { viewModel.clearFilters() },
                                    filteredCount = state.interfaces.size,
                                    totalCount = state.originalInterfaces.size,
                                )
                            }
                        }

                        // Show empty state or interfaces
                        if (state.interfaces.isEmpty()) {
                            item {
                                if (state.originalInterfaces.isEmpty()) {
                                    EmptyDiscoveredCard()
                                } else {
                                    NoFilterMatchesCard(onClearFilters = { viewModel.clearFilters() })
                                }
                            }
                        } else {
                            items(
                                state.interfaces,
                                key = { iface ->
                                    // discoveryHash (hex SHA256 of transportId + name) is stable across re-announces;
                                    // fall back to a composite for announces that don't carry one. Including
                                    // name keeps radio interfaces (no reachableOn/port) unique.
                                    iface.discoveryHash
                                        ?: "${iface.networkId}:${iface.reachableOn ?: ""}:${iface.port ?: ""}:${iface.name}"
                                },
                            ) { iface ->
                                val reachableHost = iface.reachableOn
                                DiscoveredInterfaceCard(
                                    iface = iface,
                                    distanceKm = viewModel.calculateDistance(iface),
                                    isConnected = viewModel.isAutoconnected(iface),
                                    onAddToConfig = {
                                        if (iface.isTcpInterface && reachableHost != null) {
                                            onNavigateToTcpClientWizard(
                                                reachableHost,
                                                iface.port ?: 4242,
                                                iface.name,
                                                iface.ifacNetname,
                                                iface.ifacNetkey,
                                            )
                                        } else {
                                            Toast
                                                .makeText(
                                                    context,
                                                    tcpOnlyToast,
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                        }
                                    },
                                    onOpenLocation = {
                                        // Open in Zamolxis's map with focus on this location
                                        val lat = iface.latitude ?: return@DiscoveredInterfaceCard
                                        val lon = iface.longitude ?: return@DiscoveredInterfaceCard
                                        val details =
                                            FocusInterfaceDetails(
                                                name = iface.name,
                                                type = iface.type,
                                                latitude = lat,
                                                longitude = lon,
                                                height = iface.height,
                                                reachableOn = iface.reachableOn,
                                                port = iface.port,
                                                frequency = iface.frequency,
                                                bandwidth = iface.bandwidth,
                                                spreadingFactor = iface.spreadingFactor,
                                                codingRate = iface.codingRate,
                                                modulation = iface.modulation,
                                                status = iface.status,
                                                lastHeard = iface.lastHeard,
                                                hops = iface.hops,
                                            )
                                        onNavigateToMapWithInterface(details)
                                    },
                                    onCopyLoraParams = {
                                        val params = formatLoraParamsForClipboard(iface)
                                        clipboardManager.setText(AnnotatedString(params))
                                        Toast
                                            .makeText(
                                                context,
                                                loraCopiedToast,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    },
                                    onUseForNewRNode = {
                                        onNavigateToRNodeWizardWithParams(
                                            iface.frequency,
                                            iface.bandwidth,
                                            iface.spreadingFactor,
                                            iface.codingRate,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card showing discovery settings and status.
 */
@Composable
internal fun DiscoverySettingsCard(
    isRuntimeEnabled: Boolean,
    isSettingEnabled: Boolean,
    autoconnectCount: Int = 0,
    autoconnectIfacOnly: Boolean = false,
    bootstrapInterfaceNames: List<String> = emptyList(),
    isRestarting: Boolean = false,
    onToggleDiscovery: () -> Unit = {},
    onAutoconnectCountChange: (Int) -> Unit = {},
    onToggleAutoconnectIfacOnly: () -> Unit = {},
) {
    val isEnabled = isRuntimeEnabled || isSettingEnabled

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            // Discovery toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = RoundedCornerShape(50),
                        color =
                            if (isRuntimeEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else if (isSettingEnabled) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                    ) {}
                    Column {
                        Text(
                            text = stringResource(R.string.disciface_discovery),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color =
                                if (isEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                        Text(
                            text =
                                if (isRestarting) {
                                    stringResource(R.string.disciface_restarting)
                                } else if (isRuntimeEnabled) {
                                    stringResource(R.string.disciface_active)
                                } else {
                                    stringResource(R.string.disciface_disabled)
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (isEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                },
                        )
                    }
                }
                Switch(
                    checked = isSettingEnabled,
                    onCheckedChange = { onToggleDiscovery() },
                    enabled = !isRestarting,
                )
            }

            // Restarting message
            if (isRestarting) {
                Spacer(modifier = Modifier.height(8.dp))
                ServiceRestartBanner()
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Info text
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint =
                        if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                )
                Text(
                    text =
                        if (isSettingEnabled) {
                            if (autoconnectCount > 0) {
                                stringResource(R.string.disciface_desc_autoconnect, autoconnectCount)
                            } else {
                                stringResource(R.string.disciface_desc_no_autoconnect)
                            }
                        } else {
                            stringResource(R.string.disciface_desc_disabled)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                )
            }

            // Autoconnect count slider (only shown when discovery is enabled)
            if (isSettingEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.disciface_autoconnect_limit),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text =
                                if (autoconnectCount == 0) {
                                    stringResource(R.string.disciface_off)
                                } else {
                                    "$autoconnectCount"
                                },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    // Use a separate state to track slider changes and only commit on release
                    var sliderValue by remember { mutableStateOf(autoconnectCount.toFloat()) }
                    // Update local state when external state changes
                    LaunchedEffect(autoconnectCount) {
                        sliderValue = autoconnectCount.toFloat()
                    }
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            val newCount = sliderValue.toInt()
                            if (newCount != autoconnectCount) {
                                onAutoconnectCountChange(newCount)
                            }
                        },
                        valueRange = 0f..10f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRestarting,
                    )

                    // IFAC-only sub-toggle. Visible only when (a) auto-connect
                    // is on AND (b) the backend actually enforces the IFAC
                    // filter — reticulum-kt does (NativeRnsBackendImpl
                    // .createAutoconnectInterface skips when ifac netname is
                    // blank), upstream Python RNS does not. Hiding the toggle
                    // on the Python flavor avoids a UI lie. Mirrors the
                    // "hide entirely" capability-gate pattern from
                    // BatteryOptimizationCard. The DataStore value still
                    // persists across backend swaps; only the UI control is
                    // gated.
                    val ifacFilterSupported = LocalCapabilities.current.interfaces.autoconnectIfacOnlyFilter
                    if (autoconnectCount > 0 && ifacFilterSupported) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.disciface_ifac_only),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.disciface_ifac_only_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                )
                            }
                            Switch(
                                checked = autoconnectIfacOnly,
                                onCheckedChange = { onToggleAutoconnectIfacOnly() },
                                enabled = !isRestarting,
                            )
                        }
                    }
                }
            }

            // Bootstrap interfaces section
            if (bootstrapInterfaceNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.disciface_bootstrap),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Spacer(modifier = Modifier.height(4.dp))
                bootstrapInterfaceNames.forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(6.dp),
                            shape = RoundedCornerShape(50),
                            color =
                                if (isEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                        ) {}
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (isEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.disciface_bootstrap_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Card shown when no interfaces are discovered.
 */
@Composable
internal fun NoFilterMatchesCard(onClearFilters: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.disciface_no_matches),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.disciface_no_matches_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onClearFilters) {
                Text(stringResource(R.string.disciface_clear_filters))
            }
        }
    }
}

/**
 * Search field + type-filter chip row for the discovered interfaces list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveredInterfaceSearchAndFilter(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    typeFilters: Set<DiscoveredInterfaceTypeFilter>,
    onToggleTypeFilter: (DiscoveredInterfaceTypeFilter) -> Unit,
    ifacOnly: Boolean,
    onToggleIfacOnly: () -> Unit,
    onClearFilters: () -> Unit,
    filteredCount: Int,
    totalCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.disciface_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.common_clear_search),
                            )
                        }
                    }
                },
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DiscoveredInterfaceTypeFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter in typeFilters,
                        onClick = { onToggleTypeFilter(filter) },
                        label = { Text(filter.label) },
                    )
                }
                FilterChip(
                    selected = ifacOnly,
                    onClick = onToggleIfacOnly,
                    label = { Text(stringResource(R.string.disciface_ifac_filter)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }

            val hasActiveFilters = searchQuery.isNotBlank() || typeFilters.isNotEmpty() || ifacOnly
            if (hasActiveFilters) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.disciface_count_of, filteredCount, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearFilters) {
                        Text(stringResource(R.string.common_clear))
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyDiscoveredCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.disciface_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.disciface_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Summary of discovered interface statuses.
 */
@Composable
internal fun DiscoveryStatusSummary(
    totalCount: Int,
    availableCount: Int,
    unknownCount: Int,
    staleCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$totalCount",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.disciface_total),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$availableCount",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.disciface_available),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$unknownCount",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = stringResource(R.string.disciface_unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$staleCount",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = stringResource(R.string.disciface_stale),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Card showing details for a single discovered interface.
 */
@Composable
internal fun DiscoveredInterfaceCard(
    iface: DiscoveredInterface,
    distanceKm: Double?,
    isConnected: Boolean,
    onAddToConfig: () -> Unit,
    onOpenLocation: () -> Unit,
    onCopyLoraParams: () -> Unit,
    onUseForNewRNode: () -> Unit,
) {
    val statusColor =
        when (iface.status) {
            "available" -> MaterialTheme.colorScheme.primary
            "unknown" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.outline
        }

    // Check if this is a special network type that needs explanation
    val isYggdrasil = iface.isTcpInterface && isYggdrasilAddress(iface.reachableOn)
    val isI2p = iface.type == "I2PInterface"
    var showNetworkInfoDialog by remember { mutableStateOf(false) }

    // Network info dialogs
    if (showNetworkInfoDialog) {
        when {
            isYggdrasil -> {
                AlertDialog(
                    onDismissRequest = { showNetworkInfoDialog = false },
                    title = { Text(stringResource(R.string.disciface_ygg_title)) },
                    text = {
                        Text(stringResource(R.string.disciface_ygg_body))
                    },
                    confirmButton = {
                        TextButton(onClick = { showNetworkInfoDialog = false }) {
                            Text(stringResource(R.string.common_ok))
                        }
                    },
                )
            }
            isI2p -> {
                AlertDialog(
                    onDismissRequest = { showNetworkInfoDialog = false },
                    title = { Text(stringResource(R.string.disciface_i2p_title)) },
                    text = {
                        Text(stringResource(R.string.disciface_i2p_body))
                    },
                    confirmButton = {
                        TextButton(onClick = { showNetworkInfoDialog = false }) {
                            Text(stringResource(R.string.common_ok))
                        }
                    },
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            // Header: Name and Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    InterfaceTypeIcon(
                        type = iface.type,
                        host = iface.reachableOn,
                        size = 20.dp,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Column {
                        Text(
                            text = iface.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = if (isYggdrasil) "Yggdrasil" else formatInterfaceType(iface.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Info icon for special networks (Yggdrasil, I2P)
                            if (isYggdrasil || isI2p) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = stringResource(R.string.disciface_network_info_cd),
                                    modifier =
                                        Modifier
                                            .size(14.dp)
                                            .clickable { showNetworkInfoDialog = true },
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                // Badges row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Connected badge (shown when auto-connected)
                    if (isConnected) {
                        val connectedColor = MaterialTheme.colorScheme.primary
                        Surface(
                            color = connectedColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.size(8.dp),
                                    shape = RoundedCornerShape(50),
                                    color = connectedColor,
                                ) {}
                                Text(
                                    text = stringResource(R.string.disciface_connected),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = connectedColor,
                                )
                            }
                        }
                    }
                    // Status badge
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Surface(
                                modifier = Modifier.size(8.dp),
                                shape = RoundedCornerShape(50),
                                color = statusColor,
                            ) {}
                            Text(
                                text = iface.status.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                            )
                        }
                    }
                }
            }

            // Transport ID (truncated)
            iface.transportId?.let { transportId ->
                Text(
                    text = stringResource(R.string.disciface_transport, transportId.take(12)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // Type-specific details
            when {
                iface.type == "I2PInterface" -> {
                    I2pInterfaceDetails(iface)
                }
                iface.isTcpInterface -> {
                    TcpInterfaceDetails(iface)
                }
                iface.isRadioInterface -> {
                    RadioInterfaceDetails(iface)
                }
            }

            // IFAC indicator — the remote is publishing its IFAC network identity,
            // so connecting to it requires the matching network_name / passphrase
            // (which will be auto-filled into the Add flow).
            iface.ifacNetname?.takeIf { it.isNotBlank() }?.let { ifacNet ->
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.disciface_ifac_prefix),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ifacNet,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!iface.ifacNetkey.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "🔑",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Location if available
            val lat = iface.latitude
            val lon = iface.longitude
            if (lat != null && lon != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LocationDetails(
                    latitude = lat,
                    longitude = lon,
                    height = iface.height,
                    distanceKm = distanceKm,
                    onClick = onOpenLocation,
                )
            }

            // Last heard and hops
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.disciface_last_heard, formatLastHeard(iface.lastHeard)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (iface.hops > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.msgdetail_hops, iface.hops, iface.hops),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Expandable "all announced fields" section
            Spacer(modifier = Modifier.height(8.dp))
            DiscoveredInterfaceAllFieldsSection(iface = iface)

            // Add to Config button (only for TCP interfaces with host info)
            if (iface.isTcpInterface && iface.reachableOn != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onAddToConfig,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.disciface_add_config))
                }
            }

            // LoRa params buttons (only for radio interfaces with frequency info)
            if (iface.isRadioInterface && iface.frequency != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Copy button
                    OutlinedButton(
                        onClick = onCopyLoraParams,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.disciface_copy_params))
                    }
                    // Use for New RNode button
                    Button(
                        onClick = onUseForNewRNode,
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_antenna),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.disciface_use_rnode))
                    }
                }
            }
        }
    }
}

/**
 * Collapsible "all announced fields" section. Shows every field the remote
 * published in its discovery announce as a key/value list — useful for
 * diagnosing unexpected interfaces and confirming radio parameters.
 */
@Composable
internal fun DiscoveredInterfaceAllFieldsSection(iface: DiscoveredInterface) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                contentDescription =
                    if (expanded) {
                        stringResource(R.string.disciface_collapse_details)
                    } else {
                        stringResource(R.string.disciface_expand_details)
                    },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text =
                    if (expanded) {
                        stringResource(R.string.disciface_hide_fields)
                    } else {
                        stringResource(R.string.disciface_show_fields)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val rows =
                        buildList<Pair<String, String?>> {
                            add("name" to iface.name)
                            add("type" to iface.type)
                            add("status" to iface.status)
                            add("transport node" to if (iface.transport) "yes" else "no")
                            add("hops" to iface.hops.toString())
                            add("heard count" to iface.heardCount.toString())
                            add("stamp value" to iface.stampValue.toString())
                            add("network id" to iface.networkId)
                            add("transport id" to iface.transportId)
                            add("discovery hash" to iface.discoveryHash)
                            add("reachable on" to iface.reachableOn)
                            add("port" to iface.port?.toString())
                            add("frequency" to iface.frequency?.let { "$it Hz" })
                            add("bandwidth" to iface.bandwidth?.let { "$it Hz" })
                            add("spreading factor" to iface.spreadingFactor?.toString())
                            add("coding rate" to iface.codingRate?.toString())
                            add("modulation" to iface.modulation)
                            add("channel" to iface.channel?.toString())
                            add("latitude" to iface.latitude?.toString())
                            add("longitude" to iface.longitude?.toString())
                            add("height" to iface.height?.let { "$it m" })
                            add("ifac network name" to iface.ifacNetname)
                            // The passphrase was sent in the cleartext announce — anyone on
                            // the discovery network already has it — so there's nothing to
                            // obfuscate here and showing the value is useful for diagnostics.
                            add("ifac passphrase" to iface.ifacNetkey?.takeIf { it.isNotBlank() })
                            add("received at" to iface.receivedAt.takeIf { it > 0 }?.let { formatUnixSeconds(it) })
                            add("discovered at" to iface.discoveredAt.takeIf { it > 0 }?.let { formatUnixSeconds(it) })
                            add("last heard" to iface.lastHeard.takeIf { it > 0 }?.let { formatUnixSeconds(it) })
                        }
                    rows.forEach { (key, value) ->
                        if (value.isNullOrBlank()) return@forEach
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(120.dp),
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatUnixSeconds(seconds: Long): String {
    val date = Date(seconds * 1000L)
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return fmt.format(date)
}

/**
 * Details for TCP-based interfaces.
 */
@Composable
internal fun TcpInterfaceDetails(iface: DiscoveredInterface) {
    val hostPort =
        buildString {
            iface.reachableOn?.let { append(it) }
            iface.port?.let { port ->
                if (isNotEmpty()) append(":$port") else append("port $port")
            }
        }
    if (hostPort.isNotEmpty()) {
        Text(
            text = hostPort,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Details for I2P interfaces showing the b32 address.
 */
@Composable
internal fun I2pInterfaceDetails(iface: DiscoveredInterface) {
    iface.reachableOn?.let { b32Address ->
        Text(
            text = "$b32Address.b32.i2p",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Details for radio-based interfaces (RNode, Weave, KISS).
 */
@Composable
internal fun RadioInterfaceDetails(iface: DiscoveredInterface) {
    val parts = mutableListOf<String>()

    iface.frequency?.let { freq ->
        val mhz = freq / 1_000_000.0
        parts.add("$mhz MHz")
    }
    iface.bandwidth?.let { bw ->
        val khz = bw / 1000
        parts.add("$khz kHz")
    }
    iface.spreadingFactor?.let { sf ->
        parts.add("SF$sf")
    }
    iface.codingRate?.let { cr ->
        parts.add("CR 4/$cr")
    }
    iface.modulation?.let { mod ->
        parts.add(mod)
    }
    iface.channel?.let { ch ->
        parts.add("CH$ch")
    }

    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Location details with optional distance. Tappable to open in maps.
 */
@Composable
internal fun LocationDetails(
    latitude: Double,
    longitude: Double,
    height: Double?,
    distanceKm: Double?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = stringResource(R.string.disciface_open_maps_cd),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        val locationText =
            buildString {
                append("%.4f, %.4f".format(latitude, longitude))
                height?.let { append(" (${it.toInt()}m)") }
                distanceKm?.let { dist ->
                    append(" - ")
                    if (dist < 1.0) {
                        append("${(dist * 1000).toInt()}m away")
                    } else {
                        append("%.1f km away".format(dist))
                    }
                }
            }
        Text(
            text = locationText,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    textDecoration = TextDecoration.Underline,
                ),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Renders the appropriate icon for an interface type.
 * Uses Lucide Antenna for radio interfaces, MDI incognito for I2P, and Material icons for others.
 */

/**
 * Check if a host address is a Yggdrasil network address (IPv6 in 0200::/7 space).
 * Yggdrasil uses addresses starting with 02xx or 03xx.
 */
internal fun isYggdrasilAddress(host: String?): Boolean {
    // Early exit for null
    if (host == null) return false

    // Clean host, check IPv6, parse first segment, and validate range in one chain
    val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
    val firstSegment = cleanHost.takeIf { it.contains(":") }?.split(":")?.firstOrNull()
    val value = firstSegment?.toIntOrNull(16)

    // 0200::/7 means first 7 bits are 0000001, covering 0x0200-0x03FF
    return value != null && value in 0x0200..0x03FF
}

/**
 * Renders the appropriate icon for an interface type.
 * Uses Lucide Antenna for radio interfaces, MDI incognito for I2P,
 * TreePine for Yggdrasil, and Material icons for others.
 */
@Composable
internal fun InterfaceTypeIcon(
    type: String,
    host: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    when (type) {
        "TCPServerInterface", "TCPClientInterface", "BackboneInterface" -> {
            if (isYggdrasilAddress(host)) {
                // Use TreePine for Yggdrasil network addresses
                Icon(
                    imageVector = ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_tree_pine),
                    contentDescription = stringResource(R.string.disciface_ygg_cd),
                    modifier = modifier.size(size),
                    tint = tint,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = stringResource(R.string.disciface_tcp_cd),
                    modifier = modifier.size(size),
                    tint = tint,
                )
            }
        }
        "I2PInterface" -> {
            val i2pNetworkCd = stringResource(R.string.disciface_i2p_cd)
            // Use MDI incognito icon for I2P (anonymity network)
            val codepoint = MaterialDesignIcons.getCodepointOrNull("incognito")
            if (codepoint != null) {
                Text(
                    text = codepoint,
                    fontFamily = MdiFont,
                    fontSize = (size.value * 1.2f).sp, // MDI icons render slightly smaller
                    color = tint,
                    modifier = modifier.semantics { contentDescription = i2pNetworkCd },
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.disciface_i2p_cd),
                    modifier = modifier.size(size),
                    tint = tint,
                )
            }
        }
        "RNodeInterface", "WeaveInterface", "KISSInterface" -> {
            // Use Lucide Antenna for radio interfaces (matches PeerCard)
            Icon(
                imageVector = ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_antenna),
                contentDescription = stringResource(R.string.disciface_radio_cd),
                modifier = modifier.size(size),
                tint = tint,
            )
        }
        else -> {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.disciface_unknown_cd),
                modifier = modifier.size(size),
                tint = tint,
            )
        }
    }
}

/**
 * Format interface type for display.
 */
@Composable
internal fun formatInterfaceType(type: String): String =
    when (type) {
        "TCPServerInterface" -> stringResource(R.string.disciface_type_tcp_server)
        "TCPClientInterface" -> stringResource(R.string.disciface_type_tcp_client)
        "BackboneInterface" -> stringResource(R.string.disciface_type_backbone)
        "I2PInterface" -> stringResource(R.string.disciface_type_i2p)
        "RNodeInterface" -> stringResource(R.string.disciface_type_rnode)
        "WeaveInterface" -> stringResource(R.string.disciface_type_weave)
        "KISSInterface" -> stringResource(R.string.disciface_type_kiss)
        else -> type
    }

/**
 * Format last heard timestamp as relative time.
 */
@Composable
internal fun formatLastHeard(timestamp: Long): String {
    if (timestamp == 0L) return stringResource(R.string.disciface_never)

    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp

    return when {
        diff < 60 -> stringResource(R.string.disciface_just_now)
        diff < 3600 -> stringResource(R.string.disciface_min_ago, diff / 60)
        diff < 86400 -> stringResource(R.string.disciface_hours_ago, diff / 3600)
        diff < 604800 -> stringResource(R.string.disciface_days_ago, diff / 86400)
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp * 1000))
        }
    }
}

/**
 * Format LoRa parameters for clipboard.
 */
internal fun formatLoraParamsForClipboard(iface: DiscoveredInterface): String =
    buildString {
        appendLine("LoRa Parameters from: ${iface.name}")
        appendLine("---")
        iface.frequency?.let { freq ->
            appendLine("Frequency: ${freq / 1_000_000.0} MHz")
        }
        iface.bandwidth?.let { bw ->
            appendLine("Bandwidth: ${bw / 1000} kHz")
        }
        iface.spreadingFactor?.let { sf ->
            appendLine("Spreading Factor: SF$sf")
        }
        iface.codingRate?.let { cr ->
            appendLine("Coding Rate: 4/$cr")
        }
        iface.modulation?.let { mod ->
            appendLine("Modulation: $mod")
        }
    }.trim()
