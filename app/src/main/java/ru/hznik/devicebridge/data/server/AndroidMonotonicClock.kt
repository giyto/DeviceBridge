package ru.hznik.devicebridge.data.server

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidMonotonicClock @Inject constructor() : MonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
