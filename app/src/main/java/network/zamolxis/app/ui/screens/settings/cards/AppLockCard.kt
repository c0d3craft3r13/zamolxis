package network.zamolxis.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import network.zamolxis.app.R
import kotlinx.coroutines.launch
import network.zamolxis.app.security.AppLockRepository
import network.zamolxis.app.ui.components.CollapsibleSettingsCard
import network.zamolxis.app.ui.screens.settings.dialogs.PinEntryDialog

/**
 * Configures the unlock PIN and the duress PIN.
 *
 * The duress option only appears once an unlock PIN exists, because on its own
 * it would be a PIN that does nothing but destroy data.
 */
@Composable
fun AppLockCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    repository: AppLockRepository,
    onConfigurationChanged: () -> Unit,
) {
    // Read through a counter so the card redraws after a dialog writes to the
    // repository — the repository is backed by SharedPreferences, which is not
    // observable on its own.
    var revision by remember { mutableStateOf(0) }
    val configured = remember(revision) { repository.isConfigured }
    val hasDuress = remember(revision) { repository.hasDuressPin }

    var editing by remember { mutableStateOf<PinEditTarget?>(null) }

    editing?.let { target ->
        PinEntryDialog(
            title =
                stringResource(
                    when (target) {
                        PinEditTarget.UNLOCK ->
                            if (configured) R.string.app_lock_change_unlock else R.string.app_lock_set_unlock
                        PinEditTarget.DURESS ->
                            if (hasDuress) R.string.app_lock_change_duress else R.string.app_lock_set_duress
                    },
                ),
            explainer = if (target == PinEditTarget.DURESS) stringResource(R.string.app_lock_duress_explainer) else null,
            warning = if (target == PinEditTarget.DURESS) stringResource(R.string.app_lock_duress_warning) else null,
            onConfirm = { pin ->
                val accepted =
                    when (target) {
                        PinEditTarget.UNLOCK -> repository.setUnlockPin(pin)
                        PinEditTarget.DURESS -> repository.setDuressPin(pin)
                    }
                if (accepted) {
                    revision++
                    editing = null
                    onConfigurationChanged()
                }
                accepted
            },
            onDismiss = { editing = null },
        )
    }

    CollapsibleSettingsCard(
        title = stringResource(R.string.app_lock_settings_title),
        icon = Icons.Default.Lock,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.app_lock_settings_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = { editing = PinEditTarget.UNLOCK },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (configured) R.string.app_lock_change_unlock else R.string.app_lock_set_unlock,
                    ),
                )
            }

            if (configured) {
                HorizontalDivider()

                Text(
                    text = stringResource(R.string.app_lock_duress_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    onClick = { editing = PinEditTarget.DURESS },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (hasDuress) R.string.app_lock_change_duress else R.string.app_lock_set_duress,
                        ),
                    )
                }

                if (hasDuress) {
                    TextButton(
                        onClick = {
                            repository.clearDuressPin()
                            revision++
                            onConfigurationChanged()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.app_lock_remove_duress))
                    }
                }

                TextButton(
                    onClick = {
                        repository.clearAll()
                        revision++
                        onConfigurationChanged()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.app_lock_disable))
                }
            }
        }
    }
}

/** Which of the two PINs a dialog is currently editing. */
enum class PinEditTarget {
    UNLOCK,
    DURESS,
}
