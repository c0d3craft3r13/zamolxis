package network.zamolxis.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.zamolxis.app.audio.CallRingtoneEffect
import network.zamolxis.app.rns.api.model.CallState
import network.zamolxis.app.ui.components.IncomingCallLayout
import network.zamolxis.app.viewmodel.CallViewModel
import network.zamolxis.app.ui.model.CallPeerLabel

/**
 * Incoming call screen with answer/decline options.
 *
 * Material 3 full-screen UI with:
 * - Pulsing avatar animation
 * - Caller name/identity
 * - Answer (green) and Decline (red) buttons
 */
@Composable
fun IncomingCallScreen(
    identityHash: String,
    onCallAnswered: () -> Unit,
    onCallDeclined: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val peerName by viewModel.peerName.collectAsStateWithLifecycle()

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
                android.util.Log.i("IncomingCallScreen", "📞 Permission granted, answering call...")
                viewModel.answerCall()
            } else {
                android.util.Log.w("IncomingCallScreen", "📞 Permission denied, cannot answer call")
            }
        }

    // Function to handle answer with permission check
    val handleAnswer: () -> Unit = {
        if (hasAudioPermission) {
            viewModel.answerCall()
        } else {
            android.util.Log.i("IncomingCallScreen", "📞 Requesting microphone permission to answer...")
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Handle call state changes
    LaunchedEffect(callState) {
        when (callState) {
            is CallState.Active -> {
                // Call was answered, navigate to active call screen
                onCallAnswered()
            }
            is CallState.Ended,
            is CallState.Rejected,
            is CallState.Idle,
            -> {
                // Call ended or was declined
                kotlinx.coroutines.delay(500)
                onCallDeclined()
            }
            else -> {}
        }
    }

    // Ring while this is still an unanswered call. The lock-screen activity
    // rang and this screen did not, so a call arriving with Zamolxis already
    // open announced itself in silence.
    CallRingtoneEffect(ringing = callState is CallState.Incoming)

    IncomingCallLayout(
        // The peer name arrives asynchronously; until it does, the hash stands in.
        displayName = peerName ?: CallPeerLabel.shortenHash(identityHash),
        onAnswer = handleAnswer,
        onDecline = { viewModel.declineCall() },
    )
}

