package network.zamolxis.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import network.zamolxis.app.R
import network.zamolxis.app.security.AppLockRepository
import java.util.Locale

/**
 * The PIN pad drawn over the app's contents while it is locked.
 *
 * Deliberately plain. It says "Enter PIN" and nothing else — no hint about
 * how long the PIN is, no mention that a second PIN exists, and no distinct
 * response to a duress entry. Someone looking over the owner's shoulder
 * should learn nothing from this screen beyond the fact that it wants a PIN.
 *
 * @param onSubmit called with the digits entered; the caller decides what they
 *   mean. The field clears on every submission, correct or not, so a wrong
 *   entry leaves nothing on screen to compare against the next attempt.
 * @param lockoutRemainingMs how long until a PIN will be judged again, or zero.
 *   Shown as a countdown, but the keypad stays live throughout: the duress PIN
 *   is honoured during a lockout, and a pad that refused input would take that
 *   escape away from someone who is being made to unlock the phone — the exact
 *   situation the second PIN exists for.
 */
@Composable
fun AppLockScreen(
    failedAttempts: Int,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    lockoutRemainingMs: Long = 0L,
) {
    var pin by remember { mutableStateOf("") }

    // Clearing on every attempt, not only on failure: leaving the digits up
    // after a submission would let a bystander read the PIN off the screen.
    val submit = {
        val entered = pin
        pin = ""
        onSubmit(entered)
    }

    Surface(
        modifier =
            modifier
                .fillMaxSize()
                // Makes the overlay hit-testable, so touches stop here instead
                // of falling through to the navigation host still composed
                // underneath. Consuming happens on the Final pass, after the
                // children have had theirs: the keypad needs its own taps, and
                // swallowing on Initial would block the very buttons the user
                // has to press to get in.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Final)
                                .changes
                                .forEach { it.consume() }
                        }
                    }
                },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_lock_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text =
                    when {
                        lockoutRemainingMs > 0L ->
                            stringResource(R.string.app_lock_locked_out, formatRemaining(lockoutRemainingMs))
                        failedAttempts > 0 -> stringResource(R.string.app_lock_incorrect)
                        else -> stringResource(R.string.app_lock_subtitle)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (lockoutRemainingMs > 0L) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

            Spacer(Modifier.height(32.dp))

            PinDots(length = pin.length)

            Spacer(Modifier.height(32.dp))

            if (busy) {
                CircularProgressIndicator()
            } else {
                Keypad(
                    onDigit = { d ->
                        if (pin.length < AppLockRepository.MAX_PIN_LENGTH) pin += d
                    },
                    onBackspace = { pin = pin.dropLast(1) },
                    onConfirm = { if (pin.length >= AppLockRepository.MIN_PIN_LENGTH) submit() },
                    confirmEnabled = pin.length >= AppLockRepository.MIN_PIN_LENGTH,
                )
            }
        }
    }
}

/**
 * A remaining duration as `M:SS`.
 *
 * Rounded up, so the final second reads `0:01` rather than `0:00`: a countdown
 * showing zero while the app is still refusing looks like a bug, and this
 * screen is the wrong place to make someone wonder whether the app is broken.
 */
internal fun formatRemaining(remainingMs: Long): String {
    val seconds = ((remainingMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(0L)
    return String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

/**
 * One filled dot per digit entered.
 *
 * The row grows with what has been typed instead of showing a fixed number of
 * empty slots, because a fixed row would advertise the PIN's length to anyone
 * watching.
 */
@Composable
private fun PinDots(length: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.height(16.dp),
    ) {
        repeat(length) {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { digit -> KeypadKey(digit.toString()) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KeypadIconKey(
                icon = Icons.AutoMirrored.Filled.Backspace,
                description = stringResource(R.string.app_lock_backspace),
                onClick = onBackspace,
            )
            KeypadKey("0") { onDigit('0') }
            KeypadIconKey(
                icon = Icons.Default.Check,
                description = stringResource(R.string.app_lock_confirm),
                enabled = confirmEnabled,
                onClick = onConfirm,
            )
        }
    }
}

@Composable
private fun KeypadKey(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
    ) {
        Text(text = label, fontSize = 24.sp)
    }
}

@Composable
private fun KeypadIconKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(72.dp).semantics { contentDescription = description },
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}
