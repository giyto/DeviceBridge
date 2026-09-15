package ru.hznik.devicebridge.feature.home

import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface HomeUptimeTicker {
    fun ticks(): Flow<Unit>
}

class DefaultHomeUptimeTicker @Inject constructor() : HomeUptimeTicker {
    override fun ticks(): Flow<Unit> = flow {
        while (true) {
            delay(1_000)
            emit(Unit)
        }
    }
}
