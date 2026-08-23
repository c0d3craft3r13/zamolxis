package network.zamolxis.app.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Rings the phone for as long as [ringing] holds, and stops on the way out.
 *
 * The stop is in a `DisposableEffect`, not only in the state change: a call
 * screen can leave the composition by any route — answered, declined, the
 * caller giving up, the user navigating away, the process being torn down — and
 * a ringtone that only stops on the paths somebody remembered to handle is a
 * ringtone that eventually will not stop.
 */
@Composable
fun CallRingtoneEffect(ringing: Boolean) {
    val context = LocalContext.current
    val ringer = remember(context) { CallRinger(context.applicationContext) }

    DisposableEffect(ringer, ringing) {
        if (ringing) ringer.start()
        onDispose { ringer.stop() }
    }

    // Pre-P has no looping Ringtone, so it has to be nudged. The loop only
    // exists on those versions and ends with the effect.
    LaunchedEffect(ringer, ringing) {
        if (!ringing || !ringer.needsManualLoop) return@LaunchedEffect
        while (isActive) {
            delay(ringer.loopPollMillis)
            ringer.restartIfStopped()
        }
    }
}

/**
 * Plays the ringback for as long as [ringing] holds.
 *
 * Same disposal reasoning as [CallRingtoneEffect]: the tone belongs to the
 * screen, so it dies with the screen no matter how the screen goes away.
 */
@Composable
fun RingbackToneEffect(ringing: Boolean) {
    val tone = remember { RingbackTone() }

    DisposableEffect(tone, ringing) {
        if (ringing) tone.start()
        onDispose { tone.stop() }
    }
}
