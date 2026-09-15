package ru.hznik.devicebridge.data.permission

interface PermissionRevocationObserver {
    fun start(onRevoked: () -> Unit)

    fun stop()
}

interface PermissionChangeRegistrar {
    val appUid: Int

    fun register(listener: (Int) -> Unit)

    fun unregister()
}
