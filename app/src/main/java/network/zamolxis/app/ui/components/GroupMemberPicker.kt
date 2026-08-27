package network.zamolxis.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import network.zamolxis.app.R
import network.zamolxis.app.data.model.EnrichedContact
import network.zamolxis.app.util.HexUtils.hexStringToByteArray

/**
 * Multi-select contact picker shared by the new-group screen and the
 * group-details "add members" sheet. Callers pass the pre-filtered list of
 * sendable contacts and own the selection state.
 */
@Composable
fun GroupMemberPicker(
    contacts: List<EnrichedContact>,
    selectedHashes: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (contacts.isEmpty()) {
        Text(
            text = stringResource(R.string.group_picker_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(modifier = modifier) {
        items(contacts, key = { it.destinationHash }) { contact ->
            val isSelected = contact.destinationHash in selectedHashes
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(contact.destinationHash) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggle(contact.destinationHash) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                ProfileIcon(
                    iconName = contact.iconName,
                    foregroundColor = contact.iconForegroundColor,
                    backgroundColor = contact.iconBackgroundColor,
                    size = 40.dp,
                    fallbackHash = contact.publicKey ?: contact.destinationHash.hexStringToByteArray(),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = contact.destinationHash.take(16),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
