package ru.hznik.devicebridge.data.permission

class LocalNetworkPermissionObserver(
    private val registrar: PermissionChangeRegistrar,
    private val isPermissionRequired: () -> Boolean,
    private val isLocalNetworkGranted: () -> Boolean,
) : PermissionRevocationObserver {

    private var started = false
    private var revocationReported = false
    private var callback: (() -> Unit)? = null

    override fun start(onRevoked: () -> Unit) {
        if (started) {
            return
        }
        started = true
        revocationReported = false
        callback = onRevoked
        registrar.register(::onPermissionsChanged)
    }

    override fun stop() {
        if (!started) {
            return
        }
        registrar.unregister()
        started = false
        callback = null
    }

    private fun onPermissionsChanged(uid: Int) {
        if (
            uid != registrar.appUid ||
            revocationReported ||
            !isPermissionRequired()
        ) {
            return
        }
        val granted = runCatching(isLocalNetworkGranted).getOrDefault(false)
        if (!granted) {
            revocationReported = true
            callback?.invoke()
        }
    }
}
