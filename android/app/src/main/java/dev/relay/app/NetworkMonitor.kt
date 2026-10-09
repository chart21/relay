package dev.relay.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The current default network (null = none). The gateway client waits on this instead of retrying while offline. */
class NetworkMonitor(ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val _network = MutableStateFlow<Network?>(runCatching { cm.activeNetwork }.getOrNull())
    val network: StateFlow<Network?> = _network

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { _network.value = n }
                override fun onLost(n: Network) { if (_network.value == n) _network.value = null }
            })
        }
    }
}
