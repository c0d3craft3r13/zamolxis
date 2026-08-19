package network.zamolxis.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.zamolxis.app.R
import network.zamolxis.app.viewmodel.MainViewModel
import network.zamolxis.app.viewmodel.UiState

/**
 * Main screen of the Zamolxis application.
 * Demonstrates integration with Reticulum protocol through the abstraction layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.main_title)) },
                actions = {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(R.string.main_network_status_cd),
                        tint = Color(viewModel.getNetworkStatusColor()),
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Text(
                text = stringResource(R.string.main_hello),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 32.dp),
            )

            Text(
                text = stringResource(R.string.main_demo_desc),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Network Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.main_network_status),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = networkStatus.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            // Action Buttons
            Button(
                onClick = { viewModel.initializeReticulum() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.main_init))
            }

            Button(
                onClick = { viewModel.createIdentity() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.main_create_identity))
            }

            Button(
                onClick = { viewModel.testSendPacket() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.main_test_send))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status/Result Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    when (uiState) {
                        is UiState.Error ->
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            )
                        is UiState.Success ->
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                        else -> CardDefaults.cardColors()
                    },
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    Text(
                        text =
                            when (uiState) {
                                is UiState.Initial -> stringResource(R.string.main_ready)
                                is UiState.Loading -> stringResource(R.string.iface_stats_status)
                                is UiState.Success -> stringResource(R.string.rnode_disc_success_cd)
                                is UiState.Error -> stringResource(R.string.rnode_wiz_error)
                            },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    when (val state = uiState) {
                        is UiState.Initial -> {
                            Text(stringResource(R.string.main_demo_hint))
                        }
                        is UiState.Loading -> {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier
                                        .padding(8.dp)
                                        .size(24.dp),
                            )
                            Text(state.message)
                        }
                        is UiState.Success -> {
                            Text(state.message)
                        }
                        is UiState.Error -> {
                            Text(state.message)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Footer
            Text(
                text = stringResource(R.string.main_powered),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
