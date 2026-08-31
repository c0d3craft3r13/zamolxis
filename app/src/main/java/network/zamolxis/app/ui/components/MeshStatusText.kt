package network.zamolxis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import network.zamolxis.app.R
import network.zamolxis.app.service.MeshLinkKind
import network.zamolxis.app.service.MeshReachability
import network.zamolxis.app.service.MeshStatus

/**
 * The connection state in the words a person would use.
 *
 * The first question anyone asks is "why isn't my message sending", and today the answer
 * is buried in interface statistics. This says it in one line: what is getting through,
 * or that nothing is.
 *
 * @param nowMs a monotonic clock, ticked by the caller while the screen is visible, so
 *   the line goes stale on its own without any timer running in the background
 */
@Composable
fun meshStatusText(
    reachability: MeshReachability,
    nowMs: Long,
): String =
    when (val status = reachability.describe(nowMs)) {
        is MeshStatus.Connected -> {
            // Resolved before joining: joinToString takes a nullable transform, so its
            // lambda is not inlined and cannot host a @Composable call. map's can.
            val names = status.kinds.map { stringResource(it.labelRes()) }
            stringResource(R.string.mesh_status_via, names.joinToString(", "))
        }
        MeshStatus.Searching -> stringResource(R.string.mesh_status_searching)
        MeshStatus.Offline -> stringResource(R.string.mesh_status_offline)
    }

private fun MeshLinkKind.labelRes(): Int =
    when (this) {
        MeshLinkKind.BLUETOOTH -> R.string.mesh_link_bluetooth
        MeshLinkKind.LOCAL_NETWORK -> R.string.mesh_link_local
        MeshLinkKind.INTERNET -> R.string.mesh_link_internet
        MeshLinkKind.RADIO -> R.string.mesh_link_radio
    }
