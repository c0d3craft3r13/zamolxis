package network.zamolxis.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.zamolxis.app.ui.util.getInterfaceInfo
import network.zamolxis.app.ui.util.getRssiInfo
import network.zamolxis.app.ui.util.getSnrInfo
import network.zamolxis.app.viewmodel.MessageDetailViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import network.zamolxis.app.R
import network.zamolxis.app.data.model.PqProtection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    messageId: String,
    onBackClick: () -> Unit,
    viewModel: MessageDetailViewModel = hiltViewModel(),
) {
    val message by viewModel.message.collectAsState()

    LaunchedEffect(messageId) {
        viewModel.loadMessage(messageId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.msgdetail_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        val msg = message
        if (msg == null) {
            // Loading or not found state
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Timestamp cards
                if (msg.isFromMe) {
                    // Sent messages: show sent time
                    MessageInfoCard(
                        icon = Icons.Default.AccessTime,
                        title = stringResource(R.string.msgdetail_sent),
                        content = formatFullTimestamp(msg.timestamp),
                    )
                } else {
                    // Received messages: show received time prominently, sent time as secondary
                    MessageInfoCard(
                        icon = Icons.Default.AccessTime,
                        title = stringResource(R.string.msgdetail_received),
                        content = formatFullTimestamp(msg.receivedAt ?: msg.timestamp),
                    )
                    // Show sender's claimed time (may differ if their clock is wrong)
                    MessageInfoCard(
                        icon = Icons.AutoMirrored.Filled.Send,
                        title = stringResource(R.string.msgdetail_sent_by_sender),
                        content = formatFullTimestamp(msg.timestamp),
                        subtitle =
                            if (msg.receivedAt != null && msg.receivedAt != msg.timestamp) {
                                "Sender's local time (may differ from yours)"
                            } else {
                                null
                            },
                    )
                }

                // Status, delivery method, and error cards only apply to sent messages
                if (msg.isFromMe) {
                    // Status card
                    val statusInfo = getStatusInfo(msg.status)
                    MessageInfoCard(
                        icon = statusInfo.icon,
                        iconTint = statusInfo.color,
                        title = stringResource(R.string.msgdetail_status),
                        content = statusInfo.text,
                        subtitle = statusInfo.subtitle,
                    )

                    // Delivery method card (only if available)
                    msg.deliveryMethod?.let { method ->
                        val methodInfo = getDeliveryMethodInfo(method)
                        MessageInfoCard(
                            icon = methodInfo.icon,
                            title = stringResource(R.string.msgdetail_delivery_method),
                            content = methodInfo.text,
                            subtitle = methodInfo.subtitle,
                        )
                    }

                    // Sent interface card (only if available)
                    msg.sentInterface?.let { interfaceName ->
                        val interfaceInfo = getInterfaceInfo(interfaceName)
                        MessageInfoCard(
                            icon = interfaceInfo.icon,
                            title = stringResource(R.string.msgdetail_sent_via),
                            content = interfaceInfo.text,
                            subtitle = interfaceInfo.subtitle,
                        )
                    }

                    // Error card (only if failed and has error message)
                    if (msg.status == "failed" && !msg.errorMessage.isNullOrBlank()) {
                        MessageInfoCard(
                            icon = Icons.Default.Error,
                            iconTint = MaterialTheme.colorScheme.error,
                            title = stringResource(R.string.msgdetail_error_details),
                            content = msg.errorMessage,
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    // Received message info: delivery method, hop count, receiving interface.

                    // Delivery method card. Especially important for propagation-fetched
                    // messages — hop count / interface / signal metrics are all null for
                    // those, so without this card the detail screen renders empty below
                    // the two timestamps.
                    msg.deliveryMethod?.let { method ->
                        val methodInfo = getDeliveryMethodInfo(method)
                        MessageInfoCard(
                            icon = methodInfo.icon,
                            title = stringResource(R.string.msgdetail_delivery_method),
                            content = methodInfo.text,
                            subtitle = methodInfo.subtitle,
                        )
                    }

                    // Hop count card (only if available)
                    msg.receivedHopCount?.let { hops ->
                        val hopInfo = getHopCountInfo(hops)
                        MessageInfoCard(
                            icon = Icons.Default.Hub,
                            title = stringResource(R.string.msgdetail_hop_count),
                            content = hopInfo.text,
                            subtitle = hopInfo.subtitle,
                        )
                    }

                    // Receiving interface card (only if available)
                    msg.receivedInterface?.let { interfaceName ->
                        val interfaceInfo = getInterfaceInfo(interfaceName)
                        MessageInfoCard(
                            icon = interfaceInfo.icon,
                            title = stringResource(R.string.msgdetail_received_via),
                            content = interfaceInfo.text,
                            subtitle = interfaceInfo.subtitle,
                        )
                    }

                    // Signal strength (RSSI) card (only if available)
                    msg.receivedRssi?.let { rssi ->
                        val rssiInfo = getRssiInfo(rssi)
                        MessageInfoCard(
                            icon = rssiInfo.icon,
                            iconTint = rssiInfo.color,
                            title = stringResource(R.string.msgdetail_signal_strength),
                            content = rssiInfo.text,
                            subtitle = rssiInfo.subtitle,
                        )
                    }

                    // Signal quality (SNR) card (only if available)
                    msg.receivedSnr?.let { snr ->
                        val snrInfo = getSnrInfo(snr)
                        MessageInfoCard(
                            icon = snrInfo.icon,
                            iconTint = snrInfo.color,
                            title = stringResource(R.string.msgdetail_signal_quality),
                            content = snrInfo.text,
                            subtitle = snrInfo.subtitle,
                        )
                    }
                }

                // Encryption, last: this is the screen where one message gets
                // audited, and "was this actually protected" is a question the
                // conversation badge cannot answer about a message from last week —
                // the answer is stored per row. Placed after the delivery cards so
                // it does not push them below the fold.
                val pqInfo = getPqProtectionInfo(msg.pqProtection)
                MessageInfoCard(
                    icon = pqInfo.icon,
                    iconTint = pqInfo.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    title = stringResource(R.string.msgdetail_encryption),
                    content = pqInfo.text,
                    subtitle = pqInfo.subtitle,
                )
            }
        }
    }
}

@Composable
private fun MessageInfoCard(
    icon: ImageVector,
    title: String,
    content: String,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Icon
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )

                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    fontWeight = FontWeight.Medium,
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class StatusInfo(
    val icon: ImageVector,
    val color: Color,
    val text: String,
    val subtitle: String,
)

@Composable
private fun getStatusInfo(status: String): StatusInfo =
    when (status) {
        "delivered" ->
            StatusInfo(
                icon = Icons.Default.CheckCircle,
                // Green
                color = Color(0xFF4CAF50),
                text = stringResource(R.string.msgdetail_status_delivered),
                subtitle = stringResource(R.string.msgdetail_status_delivered_sub),
            )
        "failed" ->
            StatusInfo(
                icon = Icons.Default.Error,
                color = MaterialTheme.colorScheme.error,
                text = stringResource(R.string.msgdetail_status_failed),
                subtitle = stringResource(R.string.msgdetail_status_failed_sub),
            )
        "pending" ->
            StatusInfo(
                icon = Icons.Default.HourglassEmpty,
                color = MaterialTheme.colorScheme.tertiary,
                text = stringResource(R.string.msgdetail_status_pending),
                subtitle = stringResource(R.string.msgdetail_status_pending_sub),
            )
        else ->
            StatusInfo(
                icon = Icons.AutoMirrored.Filled.Send,
                color = MaterialTheme.colorScheme.primary,
                text = stringResource(R.string.msgdetail_status_sent),
                subtitle = stringResource(R.string.msgdetail_status_sent_sub),
            )
    }

/**
 * How one message's post-quantum state reads on the detail screen.
 *
 * @property tint null for the neutral states; only the unopened case is coloured,
 *   because it is the only one that means something went wrong.
 */
private data class PqProtectionInfo(
    val icon: ImageVector,
    val tint: Color?,
    val text: String,
    val subtitle: String,
)

@Composable
private fun getPqProtectionInfo(protection: PqProtection): PqProtectionInfo =
    when (protection) {
        PqProtection.NONE ->
            PqProtectionInfo(
                icon = Icons.Default.LockOpen,
                tint = null,
                text = stringResource(R.string.pq_state_none),
                subtitle = stringResource(R.string.pq_state_none_sub),
            )

        PqProtection.SEALED ->
            PqProtectionInfo(
                icon = Icons.Default.Lock,
                tint = MaterialTheme.colorScheme.primary,
                text = stringResource(R.string.pq_state_sealed),
                subtitle = stringResource(R.string.pq_state_sealed_sub),
            )

        PqProtection.SEALED_PARTIAL ->
            PqProtectionInfo(
                icon = Icons.Default.LockOpen,
                tint = null,
                text = stringResource(R.string.pq_state_partial),
                subtitle = stringResource(R.string.pq_state_partial_sub),
            )

        PqProtection.UNOPENED ->
            PqProtectionInfo(
                icon = Icons.Default.Error,
                tint = MaterialTheme.colorScheme.error,
                text = stringResource(R.string.pq_state_unopened),
                subtitle = stringResource(R.string.pq_state_unopened_sub),
            )
    }

private data class DeliveryMethodInfo(
    val icon: ImageVector,
    val text: String,
    val subtitle: String,
)

@Composable
private fun getDeliveryMethodInfo(method: String): DeliveryMethodInfo =
    when (method) {
        "opportunistic" ->
            DeliveryMethodInfo(
                icon = Icons.AutoMirrored.Filled.Send,
                text = stringResource(R.string.msgdetail_method_opportunistic),
                subtitle = stringResource(R.string.msgdetail_method_opportunistic_sub),
            )
        "direct" ->
            DeliveryMethodInfo(
                icon = Icons.Default.Link,
                text = stringResource(R.string.msgdetail_method_direct),
                subtitle = stringResource(R.string.msgdetail_method_direct_sub),
            )
        "propagated" ->
            DeliveryMethodInfo(
                icon = Icons.Default.Hub,
                text = stringResource(R.string.msgdetail_method_propagated),
                subtitle = stringResource(R.string.msgdetail_method_propagated_sub),
            )
        else ->
            DeliveryMethodInfo(
                icon = Icons.AutoMirrored.Filled.Send,
                text = method.replaceFirstChar { it.uppercase() },
                subtitle = stringResource(R.string.msgdetail_method_unknown_sub),
            )
    }

@Composable
private fun formatFullTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val pattern = stringResource(R.string.time_full_ts_pattern)
    val format = SimpleDateFormat(pattern, Locale.getDefault())
    return format.format(date)
}

private data class HopCountInfo(
    val text: String,
    val subtitle: String,
)

@Composable
private fun getHopCountInfo(hops: Int): HopCountInfo =
    when {
        hops < 0 ->
            HopCountInfo(
                text = stringResource(R.string.common_unknown),
                subtitle = stringResource(R.string.msgdetail_hops_unavailable_sub),
            )
        hops == 0 ->
            HopCountInfo(
                text = stringResource(R.string.msgdetail_method_direct),
                subtitle = stringResource(R.string.msgdetail_received_direct_sub),
            )
        else ->
            HopCountInfo(
                text = pluralStringResource(R.plurals.msgdetail_hops, hops, hops),
                subtitle = pluralStringResource(R.plurals.msgdetail_relays_sub, hops, hops),
            )
    }
