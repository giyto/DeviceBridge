package ru.hznik.devicebridge.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityTrackerTest {

    @Test
    fun hiddenUntilAnActivityIsResumedAndAgainAsSoonAsItPauses() {
        val tracker = AppVisibilityTracker()

        assertFalse(tracker.visible.value)
        tracker.activityResumed()
        assertTrue(tracker.visible.value)
        // "Home" pauses at once; the stop comes only after the launcher animation.
        tracker.activityPaused()
        assertFalse(tracker.visible.value)
    }

    @Test
    fun overlappingActivitiesKeepTheAppVisible() {
        val tracker = AppVisibilityTracker()
        tracker.activityResumed()

        tracker.activityResumed()
        assertTrue(tracker.visible.value)
        tracker.activityPaused()
        assertTrue(tracker.visible.value)
    }

    @Test
    fun anUnmatchedPauseDoesNotHideALaterResume() {
        val tracker = AppVisibilityTracker()

        tracker.activityPaused()
        tracker.activityResumed()

        assertTrue(tracker.visible.value)
    }
}
