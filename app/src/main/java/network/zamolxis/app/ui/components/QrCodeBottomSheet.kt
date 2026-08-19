package network.zamolxis.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import network.zamolxis.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeBottomSheet(
    onDismiss: () -> Unit,
    onScanQrCode: () -> Unit,
    onShowQrCode: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.common_qr_code),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Scan QR Code option
            ListItem(
                headlineContent = { Text(stringResource(R.string.qr_scan_title)) },
                supportingContent = { Text(stringResource(R.string.qr_scan_subtitle)) },
                leadingContent = {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = null,
                    )
                },
                modifier =
                    Modifier.clickable {
                        onDismiss()
                        onScanQrCode()
                    },
            )

            // Show QR Code option
            ListItem(
                headlineContent = { Text(stringResource(R.string.onboarding_complete_show_qr)) },
                supportingContent = { Text(stringResource(R.string.qr_show_subtitle)) },
                leadingContent = {
                    Icon(
                        Icons.Default.QrCode,
                        contentDescription = null,
                    )
                },
                modifier =
                    Modifier.clickable {
                        onDismiss()
                        onShowQrCode()
                    },
            )
        }
    }
}
