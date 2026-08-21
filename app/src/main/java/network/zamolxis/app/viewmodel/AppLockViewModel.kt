package network.zamolxis.app.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import network.zamolxis.app.security.AppLockRepository
import network.zamolxis.app.security.PinVerdict
import network.zamolxis.app.security.SecureWipe
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * Whether the app is showing its contents or a PIN pad.
 */
sealed interface AppLockState {
    /** No PIN configured — the lock is not part of this install. */
    data object Disabled : AppLockState

    /** A PIN is set and has not been entered. Nothing below is rendered. */
    data class Locked(
        val failedAttempts: Int = 0,
        val busy: Boolean = false,
    ) : AppLockState

    /** Entered correctly. Contents visible until the app next goes away. */
    data object Unlocked : AppLockState
}

/**
 * Guards the app behind a PIN, and carries out the duress PIN's promise.
 *
 * Locking is unconditional on leaving the foreground rather than timed. A
 * grace period is a convenience that exists for the case where the phone is
 * still in the owner's hand; it is worth nothing in the case this lock is for,
 * where the phone changed hands a second ago.
 */
@HiltViewModel
class AppLockViewModel
    @Inject
    constructor(
        private val appLockRepository: AppLockRepository,
        private val secureWipe: SecureWipe,
    ) : ViewModel() {
        companion object {
            private const val TAG = "AppLockViewModel"
        }

        private val _state =
            MutableStateFlow<AppLockState>(
                if (appLockRepository.isConfigured) {
                    AppLockState.Locked(appLockRepository.failedAttempts)
                } else {
                    AppLockState.Disabled
                },
            )
        val state: StateFlow<AppLockState> = _state.asStateFlow()

        /** Re-lock when the app leaves the foreground. */
        fun onMovedToBackground() {
            if (!appLockRepository.isConfigured) return
            _state.value = AppLockState.Locked(appLockRepository.failedAttempts)
        }

        /**
         * Re-read the configuration after the user adds or removes a PIN in
         * Settings, so turning the lock off takes effect without a restart.
         */
        fun refreshConfiguration() {
            if (!appLockRepository.isConfigured && _state.value !is AppLockState.Unlocked) {
                _state.value = AppLockState.Disabled
            }
        }

        /**
         * Judge a typed PIN and act on it.
         *
         * The duress branch deliberately reports nothing back to the UI. It
         * wipes and then restarts the process into what a first launch looks
         * like — see [wipeAndRestart].
         */
        fun submitPin(
            context: Context,
            pin: String,
        ) {
            val current = _state.value
            if (current !is AppLockState.Locked || current.busy) return
            _state.value = current.copy(busy = true)

            viewModelScope.launch {
                when (appLockRepository.verify(pin)) {
                    PinVerdict.UNLOCK -> _state.value = AppLockState.Unlocked
                    PinVerdict.DURESS -> wipeAndRestart(context)
                    PinVerdict.WRONG ->
                        _state.value =
                            AppLockState.Locked(
                                failedAttempts = appLockRepository.failedAttempts,
                                busy = false,
                            )
                }
            }
        }

        /**
         * Destroy everything, then come back up as a fresh install.
         *
         * The process is replaced rather than navigated, because navigating
         * would leave the old data alive in memory: Room holds an open handle to
         * a database file that no longer exists, DataStore caches the values it
         * last read, and the repositories above them hold decrypted material.
         * Restarting is the only way to be certain none of it survives the
         * moment the PIN was entered.
         *
         * The visible result is the app closing and reopening on the welcome
         * screen — which is what an app does after a restart, and what a phone
         * with nothing on it looks like.
         */
        private suspend fun wipeAndRestart(context: Context) {
            runCatching { secureWipe.wipeEverything() }
                .onFailure { Log.e(TAG, "Wipe did not complete cleanly", it) }

            val relaunch =
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (relaunch != null) {
                runCatching { context.startActivity(relaunch) }
                    .onFailure { Log.e(TAG, "Could not relaunch after wipe", it) }
            }
            exitProcess(0)
        }
    }
