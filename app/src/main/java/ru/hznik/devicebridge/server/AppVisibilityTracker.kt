package ru.hznik.devicebridge.server

import android.app.Activity
import android.app.Application
import android.os.Bundle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a DeviceBridge screen is in front of the person. Event notifications stay quiet while
 * it is: the app already shows the event.
 *
 * Follows resume and pause rather than start and stop: Android stops an activity only after the
 * launcher animation, so an event that arrives right after "Home" would otherwise count as seen.
 * Counts activities rather than tracking one, so an overlap between two never looks like none.
 */
@Singleton
class AppVisibilityTracker @Inject constructor() : Application.ActivityLifecycleCallbacks {
    private var resumedActivities = 0
    private val mutableVisible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = mutableVisible.asStateFlow()

    fun activityResumed() {
        resumedActivities += 1
        mutableVisible.value = true
    }

    fun activityPaused() {
        resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
        mutableVisible.value = resumedActivities > 0
    }

    override fun onActivityResumed(activity: Activity) = activityResumed()

    override fun onActivityPaused(activity: Activity) = activityPaused()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
