package network.zamolxis.app.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether a VPN tunnel is up, so the UI can say why local-network peers
 * have gone missing.
 *
 * An active VPN takes the app's traffic by UID. Unicast keeps working — a peer
 * on the same Wi-Fi still answers a ping — but AutoInterface stops receiving
 * its own multicast probes back and quietly finds nobody. Measured on a
 * Motorola Edge 60 Pro with Hiddify: 24 "No multicast echoes received on wlan0"
 * per 100 seconds and zero peers, against zero errors and immediate peer
 * discovery the moment the tunnel came down.
 *
 * Whether an app may route around a tunnel is the VPN's decision. Where it
 * refuses, `LocalNetworkBinder`'s bypass attempt is rejected by the platform
 * and nothing in the app can change that — so the honest remedy is to tell the
 * user, who can exclude this app in their VPN's per-app settings.
 */
@Singleton
class VpnStatusMonitor
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val connectivityManager: ConnectivityManager by lazy {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }

        private var callback: ConnectivityManager.NetworkCallback? = null

        private val _vpnActive = MutableStateFlow(false)

        /**
         * True while at least one VPN transport is connected. Seeded on [start]
         * rather than at construction so the first read reflects live state even
         * if nothing has collected yet.
         */
        val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()

        /** Begin observing. Idempotent — a second call is ignored. */
        fun start() {
            if (callback != null) return
            _vpnActive.value = anyVpnConnected()
            val request =
                NetworkRequest
                    .Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    // A VPN network never carries NOT_VPN, and the default builder
                    // adds that capability — clear it or this request matches nothing.
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
            val cb =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _vpnActive.value = true
                    }

                    override fun onLost(network: Network) {
                        // Another tunnel may still be up; re-read rather than assume.
                        _vpnActive.value = anyVpnConnected()
                    }
                }
            try {
                connectivityManager.registerNetworkCallback(request, cb)
                callback = cb
                Log.d(TAG, "VPN monitor started (active=${_vpnActive.value})")
            } catch (e: Exception) {
                Log.w(TAG, "Could not register VPN network callback", e)
            }
        }

        /** Stop observing. Safe to call when never started. */
        fun stop() {
            val cb = callback ?: return
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Could not unregister VPN network callback", e)
            } finally {
                callback = null
            }
        }

        private fun anyVpnConnected(): Boolean =
            try {
                connectivityManager.allNetworks.any { network ->
                    connectivityManager
                        .getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read VPN state", e)
                false
            }

        companion object {
            private const val TAG = "VpnStatusMonitor"
        }
    }
