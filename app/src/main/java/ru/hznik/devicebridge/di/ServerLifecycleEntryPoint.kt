package ru.hznik.devicebridge.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.hznik.devicebridge.data.server.ServerLifecycleCoordinator

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServerLifecycleEntryPoint {
    fun coordinator(): ServerLifecycleCoordinator
}
