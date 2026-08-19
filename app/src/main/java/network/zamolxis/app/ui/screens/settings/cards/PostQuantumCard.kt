package network.zamolxis.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import network.zamolxis.app.R
import network.zamolxis.app.ui.components.CollapsibleSettingsCard
import network.zamolxis.crypto.pq.PqMode

/**
 * Settings card for the hybrid post-quantum layer.
 *
 * Each mode carries its own consequence text rather than a bare label. The
 * trade-offs here are not obvious from the names — "Always" makes ordinary
 * Reticulum contacts unreachable, which looks like a bug unless the user was
 * told — so the card states what each choice costs before it is made.
 *
 * @param isExpanded whether the card is currently expanded
 * @param onExpandedChange callback when expansion state changes
 * @param selectedMode the currently active mode
 * @param onModeChange callback when the user picks a different mode
 * @param onRotateKey regenerate this identity's hybrid key pair
 * @param rotationMessage result of the last rotation, shown until dismissed
 * @param onRotationMessageShown clear [rotationMessage] once the user has read it
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostQuantumCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedMode: PqMode,
    onModeChange: (PqMode) -> Unit,
    onRotateKey: () -> Unit = {},
    rotationMessage: String? = null,
    onRotationMessageShown: () -> Unit = {},
) {
    CollapsibleSettingsCard(
        title = stringResource(R.string.pq_title),
        icon = Icons.Default.Lock,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Text(
            text = stringResource(R.string.pq_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PqMode.entries.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = { onModeChange(mode) },
                    label = { Text(stringResource(mode.labelRes())) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )
            }
        }

        Text(
            text = stringResource(selectedMode.descriptionRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Stated for any mode that seals: someone who believes every message is
        // protected should know the opening one is not.
        if (selectedMode != PqMode.OFF) {
            FirstMessageNote()
        }

        // Rotation is offered because the ML-KEM half of the construction is
        // long-lived: anything sealed to it stays readable to whoever later obtains
        // it. Replacing the key is the only mitigation, so it has to be reachable
        // by a user rather than a note in a source comment.
        Text(
            text = stringResource(R.string.pq_rotate_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.pq_rotate_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRotateKey) {
            Text(stringResource(R.string.pq_rotate_action))
        }
        rotationMessage?.let { message ->
            LaunchedEffect(message) {
                // Long enough to read, then gone: it reports a completed action, not
                // a state the user has to act on.
                delay(ROTATION_MESSAGE_MS)
                onRotationMessageShown()
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** How long the rotation result stays on screen. */
private const val ROTATION_MESSAGE_MS = 5_000L

@Composable
private fun FirstMessageNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.pq_first_message_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun PqMode.labelRes(): Int =
    when (this) {
        PqMode.OFF -> R.string.pq_mode_off
        PqMode.OPPORTUNISTIC -> R.string.pq_mode_opportunistic
        PqMode.REQUIRED -> R.string.pq_mode_required
    }

private fun PqMode.descriptionRes(): Int =
    when (this) {
        PqMode.OFF -> R.string.pq_mode_off_desc
        PqMode.OPPORTUNISTIC -> R.string.pq_mode_opportunistic_desc
        PqMode.REQUIRED -> R.string.pq_mode_required_desc
    }
