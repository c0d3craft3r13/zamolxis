package network.zamolxis.app.rns.host.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Pins this process's sockets to the real local network when a VPN is up.
 *
 * An active VPN becomes Android's default network, so every socket the app
 * opens without asking for a specific one goes into the tunnel. For the mesh
 * that is fatal in a way that is easy to misread: AutoInterface still sends its
 * multicast probe out of `wlan0`, a peer on the same Wi-Fi still receives it,
 * but the echo never comes back to us — the interface logs
 * "No multicast echoes received on wlan0" every few seconds and finds nobody.
 * Measured on a Motorola Edge 60 Pro with Hiddify running: 24 such errors and
 * zero peers per capture, against zero errors on the same Wi-Fi without a VPN.
 *
 * Binding is deliberately limited to a network that is Wi-Fi (or Ethernet) and
 * explicitly NOT a VPN, and only applied while a VPN is actually present —
 * with no tunnel there is nothing to route around and the system default is
 * already correct.
 */
class LocalNetworkBinder(
    private val context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var boundNetwork: Network? = null

    /**
     * Bind the process to a non-VPN local network if a VPN is currently active.
     *
     * @return true when a binding was applied.
     */
    fun bindIfVpnActive(): Boolean {
        if (!isVpnActive()) {
            Log.d(TAG, "No VPN active, leaving process on the system default network")
            return false
        }
        val local = findLocalNetwork()
        if (local == null) {
            Log.w(TAG, "VPN active but no non-VPN local network to bind to")
            return false
        }
        return try {
            val bound = connectivityManager.bindProcessToNetwork(local)
            if (bound) {
                boundNetwork = local
                Log.i(TAG, "VPN active — bound process to local network $local")
            } else {
                Log.w(TAG, "bindProcessToNetwork refused for $local")
            }
            bound
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind process to local network", e)
            false
        }
    }

    /** Undo [bindIfVpnActive], returning the process to system default routing. */
    fun unbind() {
        if (boundNetwork == null) return
        try {
            connectivityManager.bindProcessToNetwork(null)
            Log.i(TAG, "Released local-network binding")
        } catch (e: Exception) {
            Log.w(TAG, "Could not release local-network binding", e)
        } finally {
            boundNetwork = null
        }
    }

    private fun isVpnActive(): Boolean =
        connectivityManager.allNetworks.any { network ->
            connectivityManager
                .getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

    /** The first connected Wi-Fi/Ethernet network that is not itself a VPN. */
    private fun findLocalNetwork(): Network? =
        connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            val isLocalTransport =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            isLocalTransport && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }

    companion object {
        private const val TAG = "LocalNetworkBinder"

        /** Unused today; kept so callers can express intent without a magic null. */
        val NO_REQUEST: NetworkRequest? = null
    }
}
