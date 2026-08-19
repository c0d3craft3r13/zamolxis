package network.zamolxis.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import network.zamolxis.app.R
import network.zamolxis.app.data.model.PqProtection

/**
 * Per-message post-quantum marker, shown next to the timestamp.
 *
 * Deliberately three distinct states rather than a boolean. A message whose text
 * was sealed while its photo travelled in the clear is not the same thing as a
 * fully sealed message, and saying so would be the kind of reassuring
 * simplification that gets someone hurt. Nothing is drawn for
 * [PqProtection.NONE] — the overwhelming majority of traffic — so ordinary
 * conversations do not acquire a badge that means "not protected" and therefore
 * means nothing.
 */
@Composable
fun PqProtectionMarker(
    protection: PqProtection,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    when (protection) {
        PqProtection.NONE -> Unit

        PqProtection.SEALED ->
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(R.string.pq_message_sealed_cd),
                tint = tint,
                modifier = modifier.size(12.dp),
            )

        PqProtection.SEALED_PARTIAL ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier,
            ) {
                Icon(
                    imageVector = Icons.Default.LockOpen,
                    contentDescription = stringResource(R.string.pq_message_partial_cd),
                    tint = tint,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = stringResource(R.string.pq_indicator_partial),
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
            }

        PqProtection.UNOPENED ->
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(R.string.pq_message_unopened_cd),
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(12.dp),
            )
    }
}

/**
 * Body of a message that arrived sealed and could not be opened.
 *
 * Rendered instead of the empty content slot such a message carries. The row is
 * kept rather than dropped so the two ends of a conversation cannot silently
 * disagree about whether a message exists — the sender already holds a delivery
 * proof — and because the ciphertext is still stored, so resolving a key change
 * can make it readable later.
 */
@Composable
fun PqUnreadableMessageBody(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.pq_message_unreadable),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.pq_message_unreadable_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
