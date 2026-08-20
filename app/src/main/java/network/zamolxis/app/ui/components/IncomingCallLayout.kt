package network.zamolxis.app.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.zamolxis.app.R

/** Answer button green. Not a theme colour: it means "answer" the world over. */
private val AnswerCallGreen = Color(0xFF4CAF50)

/**
 * The full-screen incoming-call UI: pulsing avatar, caller name, answer and
 * decline.
 *
 * There are two places this appears — the lock-screen activity, which has no
 * Hilt graph and takes plain callbacks, and the in-app navigation screen, which
 * drives a view model and watches the call state. They differ entirely in
 * plumbing and not at all in what the user sees, so the plumbing stays in the
 * two screens and the pixels live here once.
 *
 * It was previously copied between them, 85 lines at a time, which `cpdCheck`
 * had been reporting and which had already drifted: one used a named colour for
 * the answer button and the other an inline literal.
 *
 * @param displayName who is calling
 * @param onAnswer invoked when the user answers, after any permission check
 * @param onDecline invoked when the user declines
 */
@Composable
fun IncomingCallLayout(
    displayName: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = PULSE_MS),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "scale",
    )

    val ringColor by infiniteTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.primaryContainer,
        targetValue = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = PULSE_MS),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "ringColor",
    )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .systemBarsPadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            CallerBlock(displayName = displayName, scale = scale, ringColor = ringColor)
            CallActions(onAnswer = onAnswer, onDecline = onDecline)
        }
    }
}

@Composable
private fun CallerBlock(
    displayName: String,
    scale: Float,
    ringColor: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 80.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Outer pulsing ring
            Box(
                modifier =
                    Modifier
                        .size(160.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(ringColor),
            )

            Box(
                modifier =
                    Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.incoming_voice_call),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CallActions(
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.padding(bottom = 80.dp),
    ) {
        CallAction(
            onClick = onDecline,
            containerColor = MaterialTheme.colorScheme.error,
            iconTint = MaterialTheme.colorScheme.onError,
            icon = Icons.Default.CallEnd,
            contentDescription = stringResource(R.string.call_decline_content_description),
            label = stringResource(R.string.call_decline),
        )

        Spacer(modifier = Modifier.width(80.dp))

        CallAction(
            onClick = onAnswer,
            containerColor = AnswerCallGreen,
            iconTint = Color.White,
            icon = Icons.Default.Call,
            contentDescription = stringResource(R.string.call_answer_content_description),
            label = stringResource(R.string.call_answer),
        )
    }
}

@Composable
private fun CallAction(
    onClick: () -> Unit,
    containerColor: Color,
    iconTint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor),
            shape = CircleShape,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(32.dp),
                tint = iconTint,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Half-period of the avatar pulse. */
private const val PULSE_MS = 800
