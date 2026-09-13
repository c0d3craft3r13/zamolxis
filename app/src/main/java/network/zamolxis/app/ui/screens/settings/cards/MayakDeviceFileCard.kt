package network.zamolxis.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import network.zamolxis.app.R
import network.zamolxis.app.rns.host.persistence.MayakFileState
import network.zamolxis.app.ui.components.CollapsibleSettingsCard
import network.zamolxis.app.viewmodel.MayakDeviceFileUiState

/**
 * Developer setting: bind the Mayak device file to this phone's hardware keystore.
 *
 * Both directions ask first, and say different things, because they cost different
 * things. Binding cannot be undone by restoring a backup — there is nothing to restore
 * onto another phone — and it does not reach copies of the file already on flash.
 * Unbinding makes every copy from then on openable with the passphrase alone.
 *
 * @param state what the `:reticulum` service last reported
 * @param onRequestBound called with the confirmed choice
 */
@Composable
fun MayakDeviceFileCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    state: MayakDeviceFileUiState,
    onRequestBound: (Boolean) -> Unit,
) {
    var confirming by remember { mutableStateOf<Boolean?>(null) }

    CollapsibleSettingsCard(
        title = stringResource(R.string.mayak_file_title),
        icon = Icons.Default.PhonelinkLock,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Text(
            text = stringResource(R.string.mayak_file_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val settled = state.pending == null && state.file in setOf(MayakFileState.PORTABLE, MayakFileState.BOUND)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.mayak_file_bind),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.pending ?: (state.file == MayakFileState.BOUND),
                onCheckedChange = { confirming = it },
                enabled = settled && (state.canBind || state.file == MayakFileState.BOUND),
            )
        }

        Text(
            text = stringResource(statusText(state)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.file == MayakFileState.PORTABLE && !state.canBind) {
            Text(
                text = stringResource(R.string.mayak_file_no_keystore),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.error?.let { error ->
            Text(
                text = stringResource(R.string.mayak_file_error, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    confirming?.let { bind ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(stringResource(if (bind) R.string.mayak_file_bind_confirm_title else R.string.mayak_file_unbind_confirm_title))
            },
            text = {
                Text(stringResource(if (bind) R.string.mayak_file_bind_confirm_body else R.string.mayak_file_unbind_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        onRequestBound(bind)
                    },
                ) {
                    Text(stringResource(if (bind) R.string.mayak_file_bind_action else R.string.mayak_file_unbind_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.mayak_file_cancel))
                }
            },
        )
    }
}

private fun statusText(state: MayakDeviceFileUiState): Int =
    when {
        state.pending != null || state.file == MayakFileState.CONVERTING -> R.string.mayak_file_status_converting
        state.file == MayakFileState.BOUND -> R.string.mayak_file_status_bound
        state.file == MayakFileState.PORTABLE -> R.string.mayak_file_status_portable
        else -> R.string.mayak_file_status_not_running
    }
