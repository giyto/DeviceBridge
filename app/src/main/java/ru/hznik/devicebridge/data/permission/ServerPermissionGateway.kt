package ru.hznik.devicebridge.data.permission

interface ServerPermissionGateway {
    fun snapshot(localNetworkCanAskAgain: Boolean = true): ServerPermissionSnapshot
}
