package network.zamolxis.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import network.zamolxis.app.rns.host.persistence.MayakFileState
import network.zamolxis.app.rns.host.persistence.ServiceSettingsAccessor
import javax.inject.Inject

/** What the developer card shows about the Mayak device file. */
data class MayakDeviceFileUiState(
    val file: MayakFileState = MayakFileState.NOT_RUNNING,
    val canBind: Boolean = false,
    /** A request the service has not acted on yet: true bind, false unbind. */
    val pending: Boolean? = null,
    /** Why the last conversion or start failed, in Mayak's own words. */
    val error: String? = null,
)

/**
 * The developer setting that binds the Mayak device file to this phone's hardware, or
 * unbinds it.
 *
 * The file is opened only in the `:reticulum` service, so this does not change it: it
 * leaves a request in the cross-process preferences and shows what the service reports
 * back. Those preferences carry no change notifications between processes, so the
 * report is read again every [POLL_MS] while the card is on screen — a file read, with
 * no Python and no binder call in it.
 */
@HiltViewModel
class MayakDeviceFileViewModel
    @Inject
    constructor(
        private val settings: ServiceSettingsAccessor,
    ) : ViewModel() {
        private companion object {
            const val POLL_MS = 1_000L
            const val STOP_TIMEOUT_MS = 5_000L
        }

        val state: StateFlow<MayakDeviceFileUiState> =
            flow {
                while (true) {
                    emit(
                        MayakDeviceFileUiState(
                            file = settings.getMayakFileState(),
                            canBind = settings.getMayakCanBind(),
                            pending = settings.getMayakBindRequest(),
                            error = settings.getMayakFileError(),
                        ),
                    )
                    delay(POLL_MS)
                }
            }.flowOn(Dispatchers.IO)
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MayakDeviceFileUiState())

        /** Ask the service to bind ([bound] true) or unbind the file. Confirmed by the card first. */
        fun requestBound(bound: Boolean) {
            viewModelScope.launch(Dispatchers.IO) { settings.requestMayakBound(bound) }
        }
    }
