package network.zamolxis.app.rns.host.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A VPN takes over the default route, and AutoInterface then stops hearing its
 * own multicast echoes — measured on a Motorola Edge 60 Pro as 24
 * "No multicast echoes received on wlan0" per 100 seconds with Hiddify running,
 * and zero the moment the tunnel came down.
 *
 * Whether an app may route around a tunnel is the VPN's decision, not ours, so
 * the binder attempts the bypass and reports honestly when the system refuses.
 * These tests pin the decision: bind only when a tunnel is present, only to a
 * network that is local and not itself the VPN, and never touch routing
 * otherwise.
 */
class LocalNetworkBinderTest {
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifi: Network
    private lateinit var vpn: Network

    @Before
    fun setup() {
        context = mockk()
        connectivityManager = mockk()
        wifi = mockk()
        vpn = mockk()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
    }

    private fun caps(
        transport: Int,
        notVpn: Boolean,
    ): NetworkCapabilities =
        mockk<NetworkCapabilities>().also {
            every { it.hasTransport(any()) } returns false
            every { it.hasTransport(transport) } returns true
            every { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) } returns notVpn
        }

    @Test
    fun `no tunnel means routing is left alone`() {
        every { connectivityManager.allNetworks } returns arrayOf(wifi)
        every { connectivityManager.getNetworkCapabilities(wifi) } returns
            caps(NetworkCapabilities.TRANSPORT_WIFI, notVpn = true)

        val binder = LocalNetworkBinder(context)

        // Without a tunnel the system default is already the local network;
        // rebinding could only make things worse.
        assertFalse(binder.bindIfVpnActive())
        verify(exactly = 0) { connectivityManager.bindProcessToNetwork(any()) }
    }

    @Test
    fun `a tunnel makes the binder pin the process to the local network`() {
        every { connectivityManager.allNetworks } returns arrayOf(vpn, wifi)
        every { connectivityManager.getNetworkCapabilities(vpn) } returns
            caps(NetworkCapabilities.TRANSPORT_VPN, notVpn = false)
        every { connectivityManager.getNetworkCapabilities(wifi) } returns
            caps(NetworkCapabilities.TRANSPORT_WIFI, notVpn = true)
        every { connectivityManager.bindProcessToNetwork(wifi) } returns true

        val binder = LocalNetworkBinder(context)

        assertTrue(binder.bindIfVpnActive())
        verify(exactly = 1) { connectivityManager.bindProcessToNetwork(wifi) }
    }

    @Test
    fun `a refused bypass is reported rather than assumed to have worked`() {
        every { connectivityManager.allNetworks } returns arrayOf(vpn, wifi)
        every { connectivityManager.getNetworkCapabilities(vpn) } returns
            caps(NetworkCapabilities.TRANSPORT_VPN, notVpn = false)
        every { connectivityManager.getNetworkCapabilities(wifi) } returns
            caps(NetworkCapabilities.TRANSPORT_WIFI, notVpn = true)
        // Observed with Hiddify: no always-on lockdown set, yet the platform
        // still refuses to let a covered UID leave the tunnel.
        every { connectivityManager.bindProcessToNetwork(wifi) } returns false

        val binder = LocalNetworkBinder(context)

        assertFalse(binder.bindIfVpnActive())
    }

    @Test
    fun `a tunnel with no local network to fall back to changes nothing`() {
        every { connectivityManager.allNetworks } returns arrayOf(vpn)
        every { connectivityManager.getNetworkCapabilities(vpn) } returns
            caps(NetworkCapabilities.TRANSPORT_VPN, notVpn = false)

        val binder = LocalNetworkBinder(context)

        assertFalse(binder.bindIfVpnActive())
        verify(exactly = 0) { connectivityManager.bindProcessToNetwork(any()) }
    }
}
