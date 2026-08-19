package network.zamolxis.app.ui.screens.offlinemaps

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import network.zamolxis.app.R
import network.zamolxis.app.data.repository.OfflineMapRegion
import network.zamolxis.app.viewmodel.OfflineMapsViewModel
import network.zamolxis.app.viewmodel.UpdateCheckResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToDownload: () -> Unit = {},
    onNavigateToUpdate: (Long) -> Unit = {},
    viewModel: OfflineMapsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // File picker launcher for MBTiles import
    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri?.let { viewModel.importMbtilesFile(it) }
        }

    // Show error in snackbar
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Show import success in snackbar
    LaunchedEffect(state.importSuccessMessage) {
        state.importSuccessMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearImportSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.offmap_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        enabled = !state.isImporting,
                    ) {
                        if (state.isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = stringResource(R.string.offmap_import_cd),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToDownload,
                modifier = Modifier.navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.offmap_download_cd),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.regions.isEmpty()) {
            EmptyOfflineMapsState(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            )
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .consumeWindowInsets(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Storage summary
                item {
                    StorageSummaryCard(
                        totalStorage = state.getTotalStorageString(),
                        regionCount = state.regions.size,
                    )
                }

                // Region list
                items(
                    state.regions,
                    key = { it.id },
                ) { region ->
                    OfflineMapRegionCard(
                        region = region,
                        onDelete = { viewModel.deleteRegion(region) },
                        isDeleting = state.isDeleting,
                        updateCheckResult = state.updateCheckResults[region.id],
                        onCheckForUpdates = { viewModel.checkForUpdates(region) },
                        onUpdateNow = {
                            onNavigateToUpdate(region.id)
                        },
                        onToggleDefault = {
                            if (region.isDefault) {
                                viewModel.clearDefaultRegion()
                            } else {
                                viewModel.setDefaultRegion(region.id)
                            }
                        },
                    )
                }

                // Bottom spacing
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
fun StorageSummaryCard(
    totalStorage: String,
    regionCount: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.offmap_total_storage),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = totalStorage,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.offmap_regions),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = regionCount.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun OfflineMapRegionCard(
    region: OfflineMapRegion,
    onDelete: () -> Unit,
    isDeleting: Boolean,
    updateCheckResult: UpdateCheckResult? = null,
    onCheckForUpdates: () -> Unit = {},
    onUpdateNow: () -> Unit = {},
    onToggleDefault: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            // Map icon
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(40.dp)
                        .padding(end = 12.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            // Region info
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = region.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Status, default badge, and size
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(status = region.status)
                    if (region.isDefault) {
                        Text(
                            text = stringResource(R.string.rnode_region_default),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = region.getSizeString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Progress bar for downloading
                if (region.status == OfflineMapRegion.Status.DOWNLOADING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { region.downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.offmap_progress_detail,
                                (region.downloadProgress * 100).toInt(),
                                pluralStringResource(R.plurals.offmap_tiles, region.tileCount, region.tileCount),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Error message
                val errorMsg = region.errorMessage
                if (region.status == OfflineMapRegion.Status.ERROR && errorMsg != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                // Details
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.offmap_radius_zoom, region.radiusKm, region.minZoom, region.maxZoom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Version and update info
                if (region.status == OfflineMapRegion.Status.COMPLETE) {
                    Spacer(modifier = Modifier.height(4.dp))

                    // Show version or download date
                    val versionText =
                        region.tileVersion?.let { version ->
                            // Parse version like "20260107_001001_pt" to show as date
                            val dateStr = version.take(8)
                            val formattedDate =
                                runCatching {
                                    val year = dateStr.substring(0, 4)
                                    val month = dateStr.substring(4, 6)
                                    val day = dateStr.substring(6, 8)
                                    "$year-$month-$day"
                                }.getOrNull() ?: version
                            stringResource(R.string.offmap_map_data, formattedDate)
                        } ?: region.completedAt?.let { stringResource(R.string.offmap_downloaded, formatDate(it)) }

                    versionText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Update check button and status (only for regions with version tracking)
                    if (region.tileVersion != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            when {
                                updateCheckResult?.isChecking == true -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        text = stringResource(R.string.offmap_checking),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                updateCheckResult?.hasUpdate == true -> {
                                    Icon(
                                        imageVector = Icons.Default.Update,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.offmap_update_available),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    TextButton(
                                        onClick = { showUpdateDialog = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        Text(
                            stringResource(R.string.offmap_update_now),
                            style = MaterialTheme.typography.labelSmall,
                        )
                                    }
                                }
                                updateCheckResult?.error != null -> {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        text = updateCheckResult.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(
                                        onClick = onCheckForUpdates,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                            stringResource(R.string.common_retry),
                            style = MaterialTheme.typography.labelSmall,
                        )
                                    }
                                }
                                updateCheckResult?.latestVersion != null && !updateCheckResult.hasUpdate -> {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                    Text(
                                        text = stringResource(R.string.offmap_up_to_date),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                else -> {
                                    TextButton(
                                        onClick = onCheckForUpdates,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                            stringResource(R.string.offmap_check_updates),
                            style = MaterialTheme.typography.labelSmall,
                        )
                                    }
                                }
                            }
                        }
                    } // end tileVersion != null check
                }
            }

            // Action buttons column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Default map center toggle (star) - only for completed regions
                if (region.status == OfflineMapRegion.Status.COMPLETE) {
                    IconButton(
                        onClick = onToggleDefault,
                    ) {
                        Icon(
                            imageVector = if (region.isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription =
                            stringResource(
                                if (region.isDefault) R.string.offmap_remove_default else R.string.offmap_set_default,
                            ),
                            tint =
                                if (region.isDefault) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }

                // Delete button
                IconButton(
                    onClick = { showDeleteDialog = true },
                    enabled = !isDeleting,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.offmap_delete_title)) },
            text = {
                Text(stringResource(R.string.offmap_delete_msg, region.name, region.getSizeString()))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Update confirmation dialog
    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text(stringResource(R.string.offmap_update_title)) },
            text = {
                Text(
                    stringResource(R.string.offmap_update_msg, region.name),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUpdateDialog = false
                        onUpdateNow()
                    },
                ) {
                    Text(stringResource(R.string.rnode_wiz_update))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun StatusChip(
    status: OfflineMapRegion.Status,
    modifier: Modifier = Modifier,
) {
    val (text, color) =
        when (status) {
            OfflineMapRegion.Status.PENDING ->
                stringResource(R.string.offmap_status_pending) to MaterialTheme.colorScheme.tertiary
            OfflineMapRegion.Status.DOWNLOADING ->
                stringResource(R.string.offmap_status_downloading) to MaterialTheme.colorScheme.primary
            OfflineMapRegion.Status.COMPLETE ->
                stringResource(R.string.offmap_status_complete) to MaterialTheme.colorScheme.secondary
            OfflineMapRegion.Status.ERROR ->
                stringResource(R.string.offmap_status_error) to MaterialTheme.colorScheme.error
        }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}

@Composable
fun EmptyOfflineMapsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Map,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.offmap_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.offmap_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.offmap_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
