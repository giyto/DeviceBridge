package ru.hznik.devicebridge.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidNetworkCallbackRegistrar @Inject constructor(
    @ApplicationContext context: Context,
) : NetworkCallbackRegistrar {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private var registration: Registration? = null

    override fun register(callback: LanNetworkCallback) {
        check(registration == null) { "A network callback is already registered" }

        val androidCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                callback.onCapabilitiesChanged(
                    isSuitableWifi =
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                )
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                callback.onLinkPropertiesChanged(
                    interfaceName = linkProperties.interfaceName,
                    addresses = linkProperties.linkAddresses
                        .mapNotNull { it.address.hostAddress }
                        .map { it.substringBefore('%') }
                        .toSet(),
                )
            }

            override fun onLost(network: Network) {
                callback.onLost()
            }
        }

        connectivityManager.registerDefaultNetworkCallback(androidCallback)
        registration = Registration(callback, androidCallback)
    }

    override fun unregister(callback: LanNetworkCallback) {
        val current = registration ?: return
        if (current.callback !== callback) {
            return
        }

        registration = null
        connectivityManager.unregisterNetworkCallback(current.androidCallback)
    }

    private data class Registration(
        val callback: LanNetworkCallback,
        val androidCallback: ConnectivityManager.NetworkCallback,
    )
}
