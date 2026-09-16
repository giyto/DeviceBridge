package ru.hznik.devicebridge.web

import java.util.Locale

/** Normalizes transport-specific loopback aliases exposed by ADB port forwarding. */
object RemoteClientAddress {
    fun canonicalIpv4(rawAddress: String): String = when (rawAddress.lowercase(Locale.ROOT)) {
        "localhost",
        "::1",
        "0:0:0:0:0:0:0:1",
        -> "127.0.0.1"
        else -> rawAddress
    }
}
