package network.zamolxis.app.ui.screens.settings.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import network.zamolxis.app.R
import kotlinx.coroutines.launch
import network.zamolxis.app.security.AppLockRepository

/**
 * Takes a new PIN twice and hands it to the caller.
 *
 * @param onConfirm returns false when the repository refused the PIN — too
 *   short, not digits, or equal to the other PIN. The dialog stays open and
 *   says so, because the difference between "rejected" and "saved" is not
 *   something to leave the user guessing about when one of these PINs erases
 *   their data.
 *
 *   Suspends: storing a PIN costs 600k rounds of PBKDF2, twice over, and doing
 *   that on the main thread froze the app long enough for Android to offer to
 *   kill it. The buttons disable while it runs so the work cannot be started
 *   twice.
 */
@Composable
fun PinEntryDialog(
    title: String,
    onConfirm: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
    explainer: String? = null,
    warning: String? = null,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val mismatch = stringResource(R.string.app_lock_mismatch)
    val rejected = stringResource(R.string.app_lock_rejected)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                explainer?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                warning?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedTextField(
                    value = first,
                    onValueChange = { new ->
                        if (new.length <= AppLockRepository.MAX_PIN_LENGTH && new.all { it.isDigit() }) {
                            first = new
                            error = null
                        }
                    },
                    label = {
                        Text(
                            stringResource(
                                R.string.app_lock_enter_new,
                                AppLockRepository.MIN_PIN_LENGTH,
                                AppLockRepository.MAX_PIN_LENGTH,
                            ),
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = second,
                    onValueChange = { new ->
                        if (new.length <= AppLockRepository.MAX_PIN_LENGTH && new.all { it.isDigit() }) {
                            second = new
                            error = null
                        }
                    },
                    label = { Text(stringResource(R.string.app_lock_repeat)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled =
                    !saving &&
                        first.length >= AppLockRepository.MIN_PIN_LENGTH &&
                        second.isNotEmpty(),
                onClick = {
                    if (first != second) {
                        error = mismatch
                    } else {
                        saving = true
                        scope.launch {
                            if (!onConfirm(first)) error = rejected
                            saving = false
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.common_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.common_back))
            }
        },
    )
}
