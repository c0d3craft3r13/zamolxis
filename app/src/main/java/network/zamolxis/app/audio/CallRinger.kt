package network.zamolxis.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Rings the phone for an incoming call, using whatever ringtone the user chose
 * for the phone itself.
 *
 * Deliberately the system ringtone rather than one of our own: a call from this
 * app should sound like a call, and the tone someone picked for their phone is
 * the one they recognise from the next room. `RingtoneManager.TYPE_RINGTONE`
 * follows that choice, including later changes, with no setting of ours to keep
 * in sync.
 *
 * The device's ringer mode is honoured the way the dialer honours it: silent
 * rings and vibrates not at all, vibrate-only vibrates, normal does both.
 *
 * Lives here rather than in an Activity because there are two incoming-call
 * surfaces — the lock-screen [network.zamolxis.app.IncomingCallActivity] and the
 * in-app screen reached when Zamolxis is already open. Only the first used to
 * ring, so a call arriving while the user had the app open was announced by a
 * silent screen.
 */
class CallRinger(
    private val context: Context,
) {
    companion object {
        private const val TAG = "CallRinger"

        /**
         * Wait 0ms, buzz 1s, pause 1s, repeat — the cadence a phone call has.
         * Index 0 is the repeat point passed to `vibrate`.
         */
        private val VIBRATION_PATTERN = longArrayOf(0, 1000, 1000)

        /** Pre-P has no looping Ringtone; poll this often and restart it. */
        private const val LOOP_POLL_MS = 1000L
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    /** True while a pre-P ringtone needs manual restarting. See [needsManualLoop]. */
    val needsManualLoop: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.P && ringtone != null

    /** How often [restartIfStopped] should be called on pre-P devices. */
    val loopPollMillis: Long = LOOP_POLL_MS

    /**
     * Start ringing and vibrating, as the ringer mode allows.
     *
     * Safe to call twice: the second call stops the first, so a re-delivered
     * intent cannot leave two ringtones overlapping.
     */
    fun start() {
        stop()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerMode = audioManager.ringerMode

        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) startRingtone()
        if (ringerMode != AudioManager.RINGER_MODE_SILENT) startVibration()
    }

    private fun startRingtone() {
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone =
                RingtoneManager.getRingtone(context, ringtoneUri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isLooping = true
                    }
                    audioAttributes =
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    play()
                }
            Log.d(TAG, "Ringtone started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting ringtone", e)
        }
    }

    private fun startVibration() {
        try {
            vibrator =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager =
                        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(VIBRATION_PATTERN, 0)
            }
            Log.d(TAG, "Vibration started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting vibration", e)
        }
    }

    /**
     * Restart the ringtone if it has finished.
     *
     * Only needed below API 28, where `Ringtone.isLooping` does not exist and a
     * ringtone plays through once. The caller polls this every
     * [loopPollMillis] while [needsManualLoop] holds.
     */
    fun restartIfStopped() {
        val current = ringtone ?: return
        if (!current.isPlaying) current.play()
    }

    /** Stop both. Idempotent — calling it without having started is fine. */
    fun stop() {
        try {
            ringtone?.stop()
            ringtone = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone", e)
        }
        try {
            vibrator?.cancel()
            vibrator = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration", e)
        }
    }
}
