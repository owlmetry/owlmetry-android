package com.owlmetry.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * [NetworkMonitor] reachability classifier, mirroring the Swift `NetworkMonitor`
 * (which wraps `NWPathMonitor`). Swift's surface:
 *  - `NetworkStatus` of wifi / cellular / ethernet / offline / unknown,
 *  - `isConnected == status != .offline`,
 *  - initial state `.unknown` (treated as connected).
 *
 * We pin the wire-string parity (these strings appear nowhere else, so a rename
 * would slip past every other test) and drive the classification logic by
 * installing transports + the INTERNET capability on Robolectric's active
 * network. Robolectric's *default* active network has no INTERNET capability, so
 * an un-configured `create()` correctly classifies as OFFLINE — that's the
 * absence-of-internet path, asserted explicitly below.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private fun connectivityManager(): ConnectivityManager =
        context().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Install [capabilities] on a fresh active network in the shadow CM. */
    private fun installActiveNetwork(capabilities: NetworkCapabilities): Network {
        val cm = connectivityManager()
        val shadow = shadowOf(cm)
        val network: Network = ShadowNetwork.newInstance(42)
        shadow.setNetworkCapabilities(network, capabilities)
        // Robolectric's getActiveNetwork() returns ShadowNetwork.newInstance(0)
        // by default; setNetworkCapabilities keys off the Network instance, so
        // also wire capabilities onto the default active network the monitor
        // will actually look up.
        shadow.setNetworkCapabilities(cm.activeNetwork, capabilities)
        return network
    }

    private fun caps(vararg transports: Int): NetworkCapabilities {
        val nc = ShadowNetworkCapabilities.newInstance()
        val shadow = shadowOf(nc)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        for (t in transports) shadow.addTransportType(t)
        return nc
    }

    @Test
    fun `network status wire strings match the Swift raw values`() {
        // Swift: enum NetworkStatus: String { case wifi, cellular, ethernet,
        // offline, unknown }. The raw values must stay byte-identical.
        assertEquals("wifi", NetworkMonitor.NetworkStatus.WIFI.wire)
        assertEquals("cellular", NetworkMonitor.NetworkStatus.CELLULAR.wire)
        assertEquals("ethernet", NetworkMonitor.NetworkStatus.ETHERNET.wire)
        assertEquals("offline", NetworkMonitor.NetworkStatus.OFFLINE.wire)
        assertEquals("unknown", NetworkMonitor.NetworkStatus.UNKNOWN.wire)
    }

    @Test
    fun `enum covers exactly the five Swift cases`() {
        assertEquals(5, NetworkMonitor.NetworkStatus.entries.size)
    }

    @Test
    fun `wifi transport with internet classifies as WIFI and is connected`() {
        installActiveNetwork(caps(NetworkCapabilities.TRANSPORT_WIFI))
        val monitor = NetworkMonitor.create(context())
        try {
            assertEquals(NetworkMonitor.NetworkStatus.WIFI, monitor.status)
            assertTrue(monitor.isConnected)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `cellular transport classifies as CELLULAR`() {
        installActiveNetwork(caps(NetworkCapabilities.TRANSPORT_CELLULAR))
        val monitor = NetworkMonitor.create(context())
        try {
            assertEquals(NetworkMonitor.NetworkStatus.CELLULAR, monitor.status)
            assertTrue(monitor.isConnected)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `ethernet transport classifies as ETHERNET`() {
        installActiveNetwork(caps(NetworkCapabilities.TRANSPORT_ETHERNET))
        val monitor = NetworkMonitor.create(context())
        try {
            assertEquals(NetworkMonitor.NetworkStatus.ETHERNET, monitor.status)
            assertTrue(monitor.isConnected)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `internet without a known transport classifies as UNKNOWN and is connected`() {
        // INTERNET present but no wifi/cellular/ethernet transport — Swift's
        // `else { newStatus = .unknown }` branch, which is still connected.
        installActiveNetwork(caps(/* no transports */))
        val monitor = NetworkMonitor.create(context())
        try {
            assertEquals(NetworkMonitor.NetworkStatus.UNKNOWN, monitor.status)
            assertTrue("unknown is treated as connected", monitor.isConnected)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `no active network classifies as OFFLINE and is not connected`() {
        // Swift: `path.status != .satisfied → .offline`. The Android analog of an
        // unsatisfied path is "no active network" (null capabilities) — NOT a
        // network that merely lacks NET_CAPABILITY_INTERNET. A satisfied,
        // transport-bearing path is connected even on a captive-portal/local-only
        // network (the transport's optimistic send + offline-queue fallback
        // handles a path that can't actually reach the ingest endpoint).
        val cm = connectivityManager()
        shadowOf(cm).clearAllNetworks()
        val monitor = NetworkMonitor.create(context())
        try {
            assertEquals(NetworkMonitor.NetworkStatus.OFFLINE, monitor.status)
            assertFalse(monitor.isConnected)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun `isConnected is true for every status except offline`() {
        // Mirror Swift's `isConnected: Bool { status != .offline }`.
        for (status in NetworkMonitor.NetworkStatus.entries) {
            val connected = status != NetworkMonitor.NetworkStatus.OFFLINE
            if (status == NetworkMonitor.NetworkStatus.OFFLINE) {
                assertFalse(connected)
            } else {
                assertTrue("$status should be connected", connected)
            }
        }
    }

    @Test
    fun `stop is idempotent and survives repeated calls`() {
        val monitor = NetworkMonitor.create(context())
        // Mirrors Swift's deinit { monitor.cancel() } being safe; unregistering
        // an already-unregistered callback must not throw out of the SDK.
        monitor.stop()
        monitor.stop()
    }
}
