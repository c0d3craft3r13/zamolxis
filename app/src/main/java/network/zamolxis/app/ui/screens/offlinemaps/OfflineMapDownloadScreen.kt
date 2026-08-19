package network.zamolxis.app.ui.screens.offlinemaps

import android.Manifest
import android.location.Location
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import network.zamolxis.app.R
import network.zamolxis.app.map.TileDownloadManager
import network.zamolxis.app.util.LocationCompat
import network.zamolxis.app.viewmodel.AddressSearchResult
import network.zamolxis.app.viewmodel.DownloadProgress
import network.zamolxis.app.viewmodel.DownloadWizardStep
import network.zamolxis.app.viewmodel.OfflineMapDownloadViewModel
import network.zamolxis.app.viewmodel.RadiusOption

private const val TAG = "OfflineMapDownload"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapDownloadScreen(
    onNavigateBack: () -> Unit = {},
    onDownloadComplete: () -> Unit = {},
    updateRegionId: Long? = null,
    viewModel: OfflineMapDownloadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCancelDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val handleBack = {
        when (state.step) {
            DownloadWizardStep.DOWNLOADING -> showCancelDialog = true
            DownloadWizardStep.LOCATION -> onNavigateBack()
            DownloadWizardStep.RADIUS,
            DownloadWizardStep.CONFIRM,
            -> viewModel.previousStep()
        }
    }

    BackHandler(onBack = handleBack)

    // Pre-fill wizard when updating an existing region
    LaunchedEffect(updateRegionId) {
        if (updateRegionId != null) {
            viewModel.initForUpdate(updateRegionId)
        }
    }

    // Handle completion — consolidate all post-download notifications
    // into a single Toast so they don't conflict or get lost on navigation.
    LaunchedEffect(state.isComplete) {
        if (state.isComplete) {
            val message =
                when {
                    state.styleCacheWarning != null && state.httpAutoDisabled ->
                        state.styleCacheWarning +
                            " Note: HTTP was auto-disabled and must be re-enabled before retrying."
                    state.styleCacheWarning != null -> state.styleCacheWarning
                    state.httpAutoDisabled -> context.getString(R.string.offdl_http_disabled)
                    else -> null
                }
            message?.let {
                android.widget.Toast
                    .makeText(context, it, android.widget.Toast.LENGTH_LONG)
                    .show()
            }
            onDownloadComplete()
        }
    }

    // Show error in snackbar
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            DownloadWizardStep.LOCATION -> stringResource(R.string.offdl_step_location)
                            DownloadWizardStep.RADIUS -> stringResource(R.string.offdl_step_radius)
                            DownloadWizardStep.CONFIRM -> stringResource(R.string.offdl_step_confirm)
                            DownloadWizardStep.DOWNLOADING -> stringResource(R.string.offdl_step_downloading)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = handleBack,
                    ) {
                        Icon(
                            imageVector =
                                if (state.step == DownloadWizardStep.DOWNLOADING) {
                                    Icons.Default.Close
                                } else {
                                    Icons.AutoMirrored.Filled.ArrowBack
                                },
                            contentDescription =
                                if (state.step == DownloadWizardStep.DOWNLOADING) {
                                    stringResource(R.string.cancel)
                                } else {
                                    stringResource(R.string.common_back)
                                },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        // The parent Scaffold in MainActivity consumes navigation bar insets
        // but discards its paddingValues, so child Scaffolds see them as consumed.
        // Use the raw (unconsumed) WindowInsets to get the actual nav bar height.
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(bottom = navBarBottom),
        ) {
            when (state.step) {
                DownloadWizardStep.LOCATION ->
                    LocationSelectionStep(
                        hasLocation = state.hasLocation,
                        latitude = state.centerLatitude,
                        longitude = state.centerLongitude,
                        isGeocoderAvailable = state.isGeocoderAvailable,
                        addressQuery = state.addressQuery,
                        addressSearchResults = state.addressSearchResults,
                        isSearchingAddress = state.isSearchingAddress,
                        addressSearchError = state.addressSearchError,
                        httpEnabled = state.httpEnabled,
                        onLocationSet = { lat, lon -> viewModel.setLocation(lat, lon) },
                        onCurrentLocationRequest = { location ->
                            viewModel.setLocationFromCurrent(location)
                        },
                        onAddressQueryChange = { viewModel.setAddressQuery(it) },
                        onSearchAddress = { viewModel.searchAddress() },
                        onSelectAddressResult = { viewModel.selectAddressResult(it) },
                        onEnableHttp = { viewModel.enableHttp() },
                        onNext = { viewModel.nextStep() },
                    )

                DownloadWizardStep.RADIUS ->
                    RadiusSelectionStep(
                        radiusOption = state.radiusOption,
                        minZoom = state.minZoom,
                        maxZoom = state.maxZoom,
                        estimatedTileCount = state.estimatedTileCount,
                        estimatedSize = state.getEstimatedSizeString(),
                        onRadiusChange = { viewModel.setRadiusOption(it) },
                        onZoomRangeChange = { min, max -> viewModel.setZoomRange(min, max) },
                        onNext = { viewModel.nextStep() },
                        onBack = { viewModel.previousStep() },
                    )

                DownloadWizardStep.CONFIRM ->
                    ConfirmDownloadStep(
                        latitude = state.centerLatitude ?: 0.0,
                        longitude = state.centerLongitude ?: 0.0,
                        radiusKm = state.radiusOption.km,
                        minZoom = state.minZoom,
                        maxZoom = state.maxZoom,
                        estimatedTileCount = state.estimatedTileCount,
                        estimatedSize = state.getEstimatedSizeString(),
                        name = state.name,
                        httpEnabled = state.httpEnabled,
                        onNameChange = { viewModel.setName(it) },
                        onEnableHttp = { viewModel.enableHttp() },
                        onStartDownload = { viewModel.nextStep() },
                        onBack = { viewModel.previousStep() },
                    )

                DownloadWizardStep.DOWNLOADING ->
                    DownloadingStep(
                        progress = state.downloadProgress,
                        onCancel = { showCancelDialog = true },
                    )
            }
        }
    }

    // Cancel confirmation dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.offdl_cancel_title)) },
            text = {
                Text(stringResource(R.string.offdl_cancel_msg))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        viewModel.cancelDownload()
                        onNavigateBack()
                    },
                ) {
                    Text(stringResource(R.string.offdl_cancel_btn), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.flasher_continue))
                }
            },
        )
    }
}

@Composable
fun LocationSelectionStep(
    hasLocation: Boolean,
    latitude: Double?,
    longitude: Double?,
    isGeocoderAvailable: Boolean,
    addressQuery: String,
    addressSearchResults: List<AddressSearchResult>,
    isSearchingAddress: Boolean,
    addressSearchError: String?,
    httpEnabled: Boolean,
    onLocationSet: (Double, Double) -> Unit,
    onCurrentLocationRequest: (Location) -> Unit,
    onAddressQueryChange: (String) -> Unit,
    onSearchAddress: () -> Unit,
    onSelectAddressResult: (AddressSearchResult) -> Unit,
    onEnableHttp: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isGettingLocation by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (hasPermission) {
                isGettingLocation = true
                if (LocationCompat.isPlayServicesAvailable(context)) {
                    val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                    try {
                        fusedClient
                            .getCurrentLocation(
                                Priority.PRIORITY_HIGH_ACCURACY,
                                CancellationTokenSource().token,
                            ).addOnSuccessListener { location ->
                                isGettingLocation = false
                                if (location != null) {
                                    onCurrentLocationRequest(location)
                                }
                            }.addOnFailureListener {
                                isGettingLocation = false
                            }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Location permission denied", e)
                        isGettingLocation = false
                    }
                } else {
                    // Fallback to platform LocationManager (issue #456)
                    LocationCompat.getCurrentLocation(context) { location ->
                        isGettingLocation = false
                        if (location != null) {
                            onCurrentLocationRequest(location)
                        }
                    }
                }
            }
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .imePadding()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Warning banner when HTTP is disabled
            if (!httpEnabled) {
                HttpDisabledWarningBanner(
                    onEnableHttp = onEnableHttp,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = stringResource(R.string.offdl_center_hint),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Use current location button
            FilledTonalButton(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
                enabled = !isGettingLocation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isGettingLocation) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(R.string.offdl_use_location))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.offdl_or),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Manual coordinate entry
            var latText by remember(latitude) { mutableStateOf(latitude?.toString() ?: "") }
            var lonText by remember(longitude) { mutableStateOf(longitude?.toString() ?: "") }
            var latError by remember(latitude) { mutableStateOf<String?>(null) }
            var lonError by remember(longitude) { mutableStateOf<String?>(null) }

            fun validateAndSetLocation(
                lat: Double?,
                lon: Double?,
            ) {
                if (lat == null || lon == null) return
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
                if (latError != null || lonError != null) return
                onLocationSet(lat, lon)
            }

            val invalidNumberMsg = stringResource(R.string.offdl_invalid_number)
            val latRangeMsg = stringResource(R.string.offdl_lat_range)
            val lonRangeMsg = stringResource(R.string.offdl_lon_range)

            OutlinedTextField(
                value = latText,
                onValueChange = {
                    latText = it
                    val lat = it.toDoubleOrNull()
                    latError =
                        when {
                            it.isEmpty() || it == "-" -> null
                            lat == null -> invalidNumberMsg
                            lat !in -90.0..90.0 -> latRangeMsg
                            else -> null
                        }
                    validateAndSetLocation(lat, lonText.toDoubleOrNull())
                },
                label = { Text(stringResource(R.string.offdl_latitude)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = latError != null,
                supportingText =
                    latError?.let {
                        { Text(it) }
                    },
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = lonText,
                onValueChange = {
                    lonText = it
                    val lon = it.toDoubleOrNull()
                    lonError =
                        when {
                            it.isEmpty() || it == "-" -> null
                            lon == null -> invalidNumberMsg
                            lon !in -180.0..180.0 -> lonRangeMsg
                            else -> null
                        }
                    validateAndSetLocation(latText.toDoubleOrNull(), lon)
                },
                label = { Text(stringResource(R.string.offdl_longitude)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = lonError != null,
                supportingText =
                    lonError?.let {
                        { Text(it) }
                    },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Address/City search - only show if geocoder is available (requires Google Play Services)
            if (isGeocoderAvailable) {
                Text(
                    text = stringResource(R.string.offdl_or),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = addressQuery,
                    onValueChange = onAddressQueryChange,
                    label = { Text(stringResource(R.string.offdl_search_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearchAddress() }),
                    trailingIcon = {
                        if (isSearchingAddress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else if (addressQuery.isNotEmpty()) {
                            IconButton(onClick = onSearchAddress) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.offdl_search_cd),
                                )
                            }
                        }
                    },
                    supportingText = {
                        addressSearchError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    isError = addressSearchError != null,
                )

                // Search results
                if (addressSearchResults.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            addressSearchResults.forEach { result ->
                                TextButton(
                                    onClick = { onSelectAddressResult(result) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = result.displayName,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = stringResource(R.string.offdl_or),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Geohash entry
            var geohashText by remember { mutableStateOf("") }
            var geohashError by remember { mutableStateOf<String?>(null) }
            val invalidGeohashMsg = stringResource(R.string.offdl_invalid_geohash)

            OutlinedTextField(
                value = geohashText,
                onValueChange = { input ->
                    geohashText = input
                    if (input.isNotEmpty()) {
                        val coords = TileDownloadManager.decodeGeohashCenter(input)
                        if (coords != null) {
                            geohashError = null
                            latText = String.format(Locale.US, "%.6f", coords.first)
                            lonText = String.format(Locale.US, "%.6f", coords.second)
                            onLocationSet(coords.first, coords.second)
                        } else {
                            geohashError = invalidGeohashMsg
                        }
                    } else {
                        geohashError = null
                    }
                },
                label = { Text(stringResource(R.string.offdl_geohash)) },
                placeholder = { Text(stringResource(R.string.offdl_geohash_placeholder)) },
                supportingText = {
                    if (geohashError != null) {
                        Text(geohashError!!, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.offdl_geohash_hint))
                    }
                },
                isError = geohashError != null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (hasLocation) {
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.offdl_location_set,
                                    String.format(Locale.US, "%.4f", latitude),
                                    String.format(Locale.US, "%.4f", longitude),
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNext,
            enabled = hasLocation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.rnode_wiz_next))
        }
    }
}

@Composable
fun RadiusSelectionStep(
    radiusOption: RadiusOption,
    minZoom: Int,
    maxZoom: Int,
    estimatedTileCount: Long,
    estimatedSize: String,
    onRadiusChange: (RadiusOption) -> Unit,
    onZoomRangeChange: (Int, Int) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.offdl_area_title),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Radius options
        Column(modifier = Modifier.selectableGroup()) {
            RadiusOption.entries.forEach { option ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = radiusOption == option,
                                onClick = { onRadiusChange(option) },
                                role = Role.RadioButton,
                            ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = radiusOption == option,
                        onClick = null,
                    )
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Zoom range
        Text(
            text = stringResource(R.string.offdl_zoom_range),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.offdl_min_zoom, minZoom),
            style = MaterialTheme.typography.bodyMedium,
        )

        Slider(
            value = minZoom.toFloat(),
            onValueChange = { onZoomRangeChange(it.toInt(), maxZoom) },
            valueRange = 0f..14f,
            steps = 13,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.offdl_max_zoom, maxZoom),
            style = MaterialTheme.typography.bodyMedium,
        )

        Slider(
            value = maxZoom.toFloat(),
            onValueChange = { onZoomRangeChange(minZoom, it.toInt()) },
            valueRange = 0f..14f,
            steps = 13,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Estimate card
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.offdl_estimated),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "$estimatedTileCount tiles",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "~$estimatedSize",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.common_back))
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.rnode_wiz_next))
            }
        }
    }
}

@Composable
fun ConfirmDownloadStep(
    latitude: Double,
    longitude: Double,
    radiusKm: Int,
    minZoom: Int,
    maxZoom: Int,
    estimatedTileCount: Long,
    estimatedSize: String,
    name: String,
    httpEnabled: Boolean,
    onNameChange: (String) -> Unit,
    onEnableHttp: () -> Unit,
    onStartDownload: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        // Warning banner when HTTP is disabled
        if (!httpEnabled) {
            HttpDisabledWarningBanner(
                onEnableHttp = onEnableHttp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = stringResource(R.string.offdl_name_title),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.offdl_region_name)) },
            placeholder = { Text(stringResource(R.string.offdl_name_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.offdl_summary),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryRow(
                    stringResource(R.string.offdl_location),
                    "${String.format(Locale.US, "%.4f", latitude)}, " +
                        String.format(Locale.US, "%.4f", longitude),
                )
                SummaryRow(stringResource(R.string.offdl_radius), stringResource(R.string.offdl_km_value, radiusKm))
                SummaryRow(
                    stringResource(R.string.offdl_zoom_range),
                    stringResource(R.string.offdl_zoom_value, minZoom, maxZoom),
                )
                SummaryRow(stringResource(R.string.offdl_tiles), "$estimatedTileCount")
                SummaryRow(
                    stringResource(R.string.offdl_est_size),
                    stringResource(R.string.offdl_est_size_value, estimatedSize),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text =
                stringResource(R.string.offdl_download_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.common_back))
            }
            Button(
                onClick = onStartDownload,
                enabled = name.isNotBlank() && httpEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.offdl_download_btn))
            }
        }
    }
}

/**
 * Warning banner shown when HTTP map source is disabled.
 * Downloads require HTTP to fetch tiles from the internet.
 */
@Composable
fun HttpDisabledWarningBanner(
    onEnableHttp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.offdl_internet_required),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.offdl_http_source_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            FilledTonalButton(
                onClick = onEnableHttp,
            ) {
                Text(stringResource(R.string.migr_notif_enable))
            }
        }
    }
}

@Composable
fun SummaryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun DownloadingStep(
    progress: DownloadProgress?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (progress == null) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.offdl_preparing))
        } else {
            val statusText =
                when {
                    progress.isComplete -> stringResource(R.string.offdl_complete)
                    progress.errorMessage != null -> stringResource(R.string.rnode_wiz_error)
                    progress.statusMessage != null -> progress.statusMessage
                    progress.progress > 0 -> stringResource(R.string.offdl_downloading)
                    else -> stringResource(R.string.offdl_preparing2)
                }

            Text(
                text = statusText,
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(modifier = Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = { progress.progress },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "${(progress.progress * 100).toInt()}%",
                style = MaterialTheme.typography.displaySmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${progress.completedResources} / ${progress.requiredResources} resources",
                style = MaterialTheme.typography.bodyLarge,
            )

            if (progress.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Show cancel button while downloading
            if (!progress.isComplete && progress.errorMessage == null) {
                Spacer(modifier = Modifier.height(32.dp))

                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
