package network.zamolxis.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import network.zamolxis.app.ui.components.IncomingCallLayout

/**
 * Standalone incoming call screen composable for IncomingCallActivity.
 *
 * Unlike IncomingCallScreen (which uses hiltViewModel), this composable
 * takes simple callbacks and doesn't depend on Hilt. It's designed to be
 * used in the lightweight IncomingCallActivity that shows over the lock screen.
 */
@Composable
fun IncomingCallActivityScreen(
    identityHash: String,
    callerName: String?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current
    val displayName = callerName ?: formatIncomingHash(identityHash)

    // Permission state
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    // Permission launcher - answers call after permission granted
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasAudioPermission = isGranted
            if (isGranted) {
                onAnswer()
            }
        }

    // Function to handle answer with permission check
    val handleAnswer: () -> Unit = {
        if (hasAudioPermission) {
            onAnswer()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    IncomingCallLayout(
        displayName = displayName,
        onAnswer = handleAnswer,
        onDecline = onDecline,
    )
}
