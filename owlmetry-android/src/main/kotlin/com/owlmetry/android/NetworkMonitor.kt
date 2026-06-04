package com.owlmetry.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicReference

/**
 * Live network-reachability classifier. The Android analog of the Swift SDK's
 * `NetworkMonitor` (which wraps `NWPathMonitor`).
 *
 * Swift exposes a `NetworkStatus` of wifi / cellular / ethernet / offline /
 * unknown and derives `isConnected` as `status != .offline`. We mirror that
 * surface exactly, deriving the status from [ConnectivityManager] +
 * [NetworkCapabilities] transports.
 *
 * The status is held in an [AtomicReference] (the analog of Swift's
 * `OSAllocatedUnfairLock<NetworkStatus>`) and updated from a registered
 * [ConnectivityManager.NetworkCallback], so reads from the transport's flush
 * loop are lock-free and always see the latest classification.
 *
 * Divergence from Swift, recorded deliberately:
 *  - Requires `ACCESS_NETWORK_STATE` (a normal, install-time permission the host
 *    app declares). If the permission is absent or [ConnectivityManager] is
 *    unavailable, the monitor reports [NetworkStatus.UNKNOWN] — which is
 *    `isConnected == true` — so the transport optimistically attempts a send
 *    and falls back to the offline queue on failure, rather than silently
 *    dropping events because reachability couldn't be determined. This matches
 *    Swift's initial `.unknown` state being treated as connected.
 */
/**
 * The reachability seam the [EventTransport] reads. [NetworkMonitor] is the
 * production implementation; tests substitute a fake to drive the offline path
 * deterministically (the analog of injecting a `NetworkMonitor` into the Swift
 * `EventTransport`).
 */
internal interface Reachability {
    val isConnected: Boolean
}

internal class NetworkMonitor private constructor(
    private val connectivityManager: ConnectivityManager?,
) : Reachability {
    /** Mirrors Swift's `NetworkMonitor.NetworkStatus` cases + raw wire strings. */
    enum class NetworkStatus(val wire: String) {
        WIFI("wifi"),
        CELLULAR("cellular"),
        ETHERNET("ethernet"),
        OFFLINE("offline"),
        UNKNOWN("unknown"),
    }

    private val statusRef = AtomicReference(NetworkStatus.UNKNOWN)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            statusRef.set(classify(connectivityManager?.getNetworkCapabilities(network)))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            statusRef.set(classify(capabilities))
        }

        override fun onLost(network: Network) {
            statusRef.set(NetworkStatus.OFFLINE)
        }

        override fun onUnavailable() {
            statusRef.set(NetworkStatus.OFFLINE)
        }
    }

    /** The current network status. Mirrors Swift's `NetworkMonitor.status`. */
    val status: NetworkStatus
        get() = statusRef.get()

    /** `true` unless explicitly offline. Mirrors Swift's `isConnected`. */
    override val isConnected: Boolean
        get() = status != NetworkStatus.OFFLINE

    private fun start() {
        val cm = connectivityManager ?: return
        // Seed the current status synchronously so the first flush after
        // configure() doesn't have to wait for a callback. activeNetwork +
        // getNetworkCapabilities is the snapshot analog of NWPathMonitor's
        // initial path.
        statusRef.set(
            runCatching { classify(cm.getNetworkCapabilities(cm.activeNetwork)) }
                .getOrDefault(NetworkStatus.UNKNOWN),
        )
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    /** Stop observing. Mirrors Swift's `deinit { monitor.cancel() }`. */
    fun stop() {
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }

    private fun classify(capabilities: NetworkCapabilities?): NetworkStatus {
        // Mirror Swift's `NetworkMonitor`, which decides connectivity purely on
        // `path.status == .satisfied` (a usable transport-bearing path) and then
        // picks the interface type — it does NOT require an internet-capability
        // equivalent. An active network with capabilities is the `.satisfied`
        // analog; no active network (null) is `.unsatisfied` → offline. The
        // transport classifies wifi/cellular/ethernet, else `.unknown` (still
        // connected). We deliberately do not hard-gate on NET_CAPABILITY_INTERNET:
        // that would down-classify captive-portal / local-only networks that
        // Swift would treat as satisfied, and the transport's optimistic-send +
        // offline-queue fallback already handles a network that can't actually
        // reach the ingest endpoint.
        if (capabilities == null) return NetworkStatus.OFFLINE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus.ETHERNET
            else -> NetworkStatus.UNKNOWN
        }
    }

    companion object {
        /**
         * Build a monitor for [context] and begin observing the default
         * network. Returns a started instance; call [stop] to tear down.
         */
        fun create(context: Context): NetworkMonitor {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            return NetworkMonitor(cm).also { it.start() }
        }
    }
}
