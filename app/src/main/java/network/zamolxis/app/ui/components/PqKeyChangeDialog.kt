package network.zamolxis.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.zamolxis.app.R

/**
 * Raised when a contact offers a post-quantum key that contradicts the stored one.
 *
 * The dialog exists because the app genuinely cannot tell the two explanations
 * apart. A reinstall, a new phone, or a deliberate key rotation all look
 * identical on the wire to someone substituting themselves into the
 * conversation. Guessing would mean either nagging honest users or silently
 * accepting an impostor, so the decision goes to the person who can actually
 * check.
 *
 * Both fingerprints are shown, not just the new one: a user comparing two values
 * can spot a change they did not expect, and can read the new one back to the
 * contact over a channel that is already trusted.
 *
 * @param peerName who the change is attributed to
 * @param currentFingerprint the key sealing has been using
 * @param newFingerprint the key now being offered
 * @param onAccept trust the new key — sealing resumes with it
 * @param onReject discard the offer — the previous key stands
 * @param onDismiss leave it unresolved; sealing stays paused
 */
@Composable
fun PqKeyChangeDialog(
    peerName: String,
    currentFingerprint: ByteArray,
    newFingerprint: ByteArray,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.pq_key_change_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Named explicitly. A warning about "this contact" is ambiguous the
                // moment the user has more than one conversation open in their head,
                // and this is the screen where being sure who it is about matters.
                Text(
                    text = stringResource(R.string.pq_key_change_peer, peerName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                Text(
                    text = stringResource(R.string.pq_key_change_warning),
                    style = MaterialTheme.typography.bodyMedium,
                )

                FingerprintBlock(
                    label = stringResource(R.string.pq_key_change_current),
                    fingerprint = currentFingerprint,
                )
                FingerprintBlock(
                    label = stringResource(R.string.pq_key_change_new),
                    fingerprint = newFingerprint,
                    emphasise = true,
                )

                Text(
                    text = stringResource(R.string.pq_key_change_verify),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // Rejecting is the confirm slot on purpose: it is the safe outcome, and
            // the one a user should land on when they are unsure or tapping quickly.
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.pq_key_change_reject))
            }
        },
        dismissButton = {
            TextButton(onClick = onAccept) {
                Text(
                    text = stringResource(R.string.pq_key_change_accept),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/**
 * Reports a key that was rejected because it contradicted its own announcement.
 *
 * Distinct from [PqKeyChangeDialog] because there is nothing to decide. A key
 * that does not match the fingerprint its owner broadcast was never stored and
 * nothing was sealed to it, so the app has already done the safe thing. What
 * remains is telling the user, because the only explanations are that the
 * announce was tampered with or the message was — and they are the only party
 * who can check the real key against the real person.
 *
 * @param peerName who sent the mismatching key
 * @param onDismiss acknowledge the warning; it does not reappear for this event
 */
@Composable
fun PqKeyMismatchDialog(
    peerName: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.pq_key_mismatch_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.pq_key_change_peer, peerName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.pq_key_mismatch_warning),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pq_key_mismatch_dismiss))
            }
        },
    )
}

@Composable
private fun FingerprintBlock(
    label: String,
    fingerprint: ByteArray,
    emphasise: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color =
                if (emphasise) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
        ) {
            Text(
                modifier = Modifier.padding(10.dp),
                text = formatFingerprint(fingerprint),
                style = MaterialTheme.typography.bodyMedium,
                // Monospaced so the groups line up between the two blocks and a
                // difference is visible rather than something to hunt for.
                fontFamily = FontFamily.Monospace,
                color =
                    if (emphasise) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

/**
 * Render a fingerprint as space-separated groups of four hex characters.
 *
 * Grouping is for the human step this dialog depends on: an unbroken 32-character
 * string is close to impossible to read accurately down a phone line, which is
 * exactly what the user is being asked to do.
 */
internal fun formatFingerprint(fingerprint: ByteArray): String =
    fingerprint
        .joinToString("") { "%02X".format(it) }
        .chunked(4)
        .joinToString(" ")
