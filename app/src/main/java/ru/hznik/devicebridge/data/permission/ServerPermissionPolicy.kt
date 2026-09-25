package ru.hznik.devicebridge.data.permission

/** First Android SDK level that makes the app ask for local network access. */
const val LOCAL_NETWORK_PERMISSION_MIN_SDK = 37

data class ServerPermissionRequirements(
    val requiresLocalNetwork: Boolean,
    val requiresNotifications: Boolean,
)

data class ServerPermissionEvaluation(
    val canStart: Boolean,
    val requestLocalNetwork: Boolean,
    val requestNotifications: Boolean,
    val showNotificationWarning: Boolean,
)

class ServerPermissionPolicy {

    fun requirements(sdkInt: Int): ServerPermissionRequirements =
        ServerPermissionRequirements(
            requiresLocalNetwork = sdkInt >= LOCAL_NETWORK_PERMISSION_MIN_SDK,
            requiresNotifications = sdkInt >= 33,
        )

    fun evaluate(
        sdkInt: Int,
        localNetworkGranted: Boolean,
        notificationsGranted: Boolean,
    ): ServerPermissionEvaluation {
        val requirements = requirements(sdkInt)
        val needsLocalNetwork =
            requirements.requiresLocalNetwork && !localNetworkGranted
        val needsNotifications =
            requirements.requiresNotifications && !notificationsGranted

        return ServerPermissionEvaluation(
            canStart = !needsLocalNetwork,
            requestLocalNetwork = needsLocalNetwork,
            requestNotifications = needsNotifications,
            showNotificationWarning = needsNotifications,
        )
    }
}
