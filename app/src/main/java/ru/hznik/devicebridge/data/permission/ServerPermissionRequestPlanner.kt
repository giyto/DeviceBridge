package ru.hznik.devicebridge.data.permission

private const val LocalNetworkPermission =
    "android.permission.ACCESS_LOCAL_NETWORK"
private const val NotificationPermission =
    "android.permission.POST_NOTIFICATIONS"

data class ServerPermissionSnapshot(
    val sdkInt: Int,
    val localNetworkGranted: Boolean,
    val notificationsGranted: Boolean,
    val localNetworkCanAskAgain: Boolean = true,
)

data class ServerPermissionRequestPlan(
    val permissions: List<String>,
    val canStart: Boolean,
    val localNetworkBlockedPermanently: Boolean,
    val showNotificationWarning: Boolean,
)

class ServerPermissionRequestPlanner(
    private val policy: ServerPermissionPolicy,
) {
    fun plan(snapshot: ServerPermissionSnapshot): ServerPermissionRequestPlan {
        val evaluation = policy.evaluate(
            sdkInt = snapshot.sdkInt,
            localNetworkGranted = snapshot.localNetworkGranted,
            notificationsGranted = snapshot.notificationsGranted,
        )
        val permissions = buildList {
            if (
                evaluation.requestLocalNetwork &&
                snapshot.localNetworkCanAskAgain
            ) {
                add(LocalNetworkPermission)
            }
            if (evaluation.requestNotifications) {
                add(NotificationPermission)
            }
        }

        return ServerPermissionRequestPlan(
            permissions = permissions,
            canStart = evaluation.canStart,
            localNetworkBlockedPermanently =
                evaluation.requestLocalNetwork &&
                    !snapshot.localNetworkCanAskAgain,
            showNotificationWarning = evaluation.showNotificationWarning,
        )
    }
}
