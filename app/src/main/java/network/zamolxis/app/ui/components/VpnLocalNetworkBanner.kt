package network.zamolxis.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import network.zamolxis.app.R

/**
 * Tells the user why nobody shows up on the local network while a VPN is on.
 *
 * A tunnel claims the app's traffic by UID, and AutoInterface stops receiving
 * its own multicast probes back — peers on the same Wi-Fi simply stop being
 * discovered, with nothing visible except silence. The app cannot route around
 * it: whether an app may leave a tunnel is the VPN's decision, and where it
 * says no the platform refuses the bypass outright.
 *
 * So this states the situation and the one action that resolves it — excluding
 * this app in the VPN's per-app settings. Shown only while a tunnel is up, and
 * it disappears by itself when the tunnel does.
 */
@Composable
fun VpnLocalNetworkBanner(
    vpnActive: Boolean,
    /**
     * True when a banner above this one already sits under the status bar and
     * has consumed that inset. Without this the two would each reserve the
     * status-bar height and leave a gap; with it wrong in the other direction
     * the text draws underneath the clock and icons, which is what the first
     * on-device check caught.
     */
    insetConsumedAbove: Boolean = false,
) {
    AnimatedVisibility(
        visible = vpnActive,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(if (insetConsumedAbove) Modifier else Modifier.statusBarsPadding())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.vpn_local_network_banner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
