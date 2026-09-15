package ru.hznik.devicebridge.data.server

fun interface MonotonicClock {
    fun nowMs(): Long
}
