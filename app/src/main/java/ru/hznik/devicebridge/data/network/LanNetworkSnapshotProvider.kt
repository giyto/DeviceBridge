package ru.hznik.devicebridge.data.network

fun interface LanNetworkSnapshotProvider {
    fun snapshot(): LanNetworkSnapshot
}
