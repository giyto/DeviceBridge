package ru.hznik.devicebridge.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.NetworkInterface
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidLanNetworkSnapshotProvider @Inject constructor(
    @ApplicationContext context: Context,
) : LanNetworkSnapshotProvider {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    override fun snapshot(): LanNetworkSnapshot =
        LanNetworkSnapshot(
            activeWifiAddresses = activeWifiAddresses(),
            interfaceAddresses = interfaceAddresses(),
        )

    private fun activeWifiAddresses(): List<LanAddressCandidate> =
        runCatching {
            val network = connectivityManager.activeNetwork
                ?: return@runCatching emptyList()
            val capabilities =
                connectivityManager.getNetworkCapabilities(network)
                    ?: return@runCatching emptyList()
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return@runCatching emptyList()
            }
            val linkProperties = connectivityManager.getLinkProperties(network)
                ?: return@runCatching emptyList()
            val interfaceName = linkProperties.interfaceName
                ?: return@runCatching emptyList()

            linkProperties.linkAddresses.mapNotNull { linkAddress ->
                linkAddress.address.hostAddress?.let { host ->
                    LanAddressCandidate(
                        host = host.substringBefore('%'),
                        interfaceName = interfaceName,
                        isUp = true,
                    )
                }
            }
        }.getOrDefault(emptyList())

    private fun interfaceAddresses(): List<LanAddressCandidate> =
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?: return@runCatching emptyList()
            Collections.list(interfaces).flatMap { networkInterface ->
                val isUp = runCatching { networkInterface.isUp }
                    .getOrDefault(false)
                Collections.list(networkInterface.inetAddresses).mapNotNull { address ->
                    address.hostAddress?.let { host ->
                        LanAddressCandidate(
                            host = host.substringBefore('%'),
                            interfaceName = networkInterface.name,
                            isUp = isUp,
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
}
