package network.zamolxis.app.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import network.zamolxis.app.service.BootstrapHubHealth
import network.zamolxis.app.service.MeshReachability
import javax.inject.Inject

/**
 * Hands the UI what the mesh has actually delivered.
 *
 * Nothing but a seam: [BootstrapHubHealth] is an application-scoped singleton that
 * already listens to the announce stream, so the state is shared by every screen that
 * shows it and costs nothing extra to display.
 */
@HiltViewModel
class MeshStatusViewModel
    @Inject
    constructor(
        bootstrapHubHealth: BootstrapHubHealth,
    ) : ViewModel() {
        val reachability: StateFlow<MeshReachability> = bootstrapHubHealth.reachability
    }
